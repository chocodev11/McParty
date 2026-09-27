package dev.epicc.slime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Main-thread queue; workers only prepare data, and each tick registers at most one world. */
final class WorldLoadQueue<P, R> {
    private final Executor executor;
    private final int concurrency;
    private final int capacity;
    private final R unavailable;
    private final Consumer<RuntimeException> onFailure;
    private final Queue<Request> pending = new ArrayDeque<>();
    private final Set<Request> running = new HashSet<>();
    private final Queue<Runnable> completed = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;

    WorldLoadQueue(Executor executor, int concurrency, int capacity, R unavailable,
                   Consumer<RuntimeException> onFailure) {
        this.executor = executor;
        this.concurrency = concurrency;
        this.capacity = capacity;
        this.unavailable = unavailable;
        this.onFailure = onFailure;
    }

    CompletableFuture<R> submit(UUID owner, Supplier<P> prepare, Function<P, R> register, Consumer<R> discard) {
        pending.removeIf(request -> request.future.isDone());
        if (closed || pending.size() + running.size() >= capacity) {
            return CompletableFuture.completedFuture(unavailable);
        }
        Request request = new Request(owner, prepare, register, discard);
        pending.add(request);
        return request.future;
    }

    void tick() {
        if (closed) return;
        Runnable completion = completed.poll();
        if (completion != null) completion.run();
        while (running.size() < concurrency && !pending.isEmpty()) {
            Request request = pending.remove();
            if (request.future.isDone()) continue;
            running.add(request);
            try {
                executor.execute(() -> {
                    P prepared = null;
                    RuntimeException failure = null;
                    try {
                        if (!request.future.isDone()) prepared = request.prepare.get();
                    } catch (RuntimeException exception) {
                        failure = exception;
                    }
                    P result = prepared;
                    RuntimeException error = failure;
                    // Serialize publication with close so shutdown cannot retain a late clone.
                    synchronized (completed) {
                        if (!closed) completed.add(() -> complete(request, result, error));
                    }
                });
            } catch (RuntimeException exception) {
                complete(request, null, exception);
            }
        }
    }

    private void complete(Request request, P prepared, RuntimeException failure) {
        try {
            if (request.future.isDone()) return;
            if (failure != null) {
                onFailure.accept(failure);
                request.future.complete(unavailable);
                return;
            }
            R result = request.register.apply(prepared);
            // WorldLoadEvent can end the party while registration is on the stack.
            if (!request.future.complete(result)) request.discard.accept(result);
        } catch (RuntimeException exception) {
            onFailure.accept(exception);
            request.future.complete(unavailable);
        } finally {
            running.remove(request);
        }
    }

    void cancel(UUID owner) {
        for (Request request : new ArrayList<>(pending)) {
            if (request.owner.equals(owner)) request.future.cancel(false);
        }
        pending.removeIf(request -> request.future.isDone());
        for (Request request : new ArrayList<>(running)) {
            if (request.owner.equals(owner)) request.future.cancel(false);
        }
    }

    boolean isIdle() {
        return pending.isEmpty() && running.isEmpty();
    }

    void close() {
        synchronized (completed) {
            closed = true;
            completed.clear();
        }
        for (Request request : new ArrayList<>(pending)) request.future.cancel(false);
        for (Request request : new ArrayList<>(running)) request.future.cancel(false);
        pending.clear();
        running.clear();
    }

    private final class Request {
        private final UUID owner;
        private final Supplier<P> prepare;
        private final Function<P, R> register;
        private final Consumer<R> discard;
        private final CompletableFuture<R> future = new CompletableFuture<>();

        private Request(UUID owner, Supplier<P> prepare, Function<P, R> register, Consumer<R> discard) {
            this.owner = owner;
            this.prepare = prepare;
            this.register = register;
            this.discard = discard;
        }
    }
}
