package dev.epicc.slime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class WorldLoadQueueTest {
    private final Queue<Runnable> workers = new ArrayDeque<>();
    private final List<RuntimeException> failures = new ArrayList<>();
    private final WorldLoadQueue<String, String> queue = new WorldLoadQueue<>(
            workers::add, 2, 3, "unavailable", failures::add);
    private final AtomicInteger registrations = new AtomicInteger();

    private CompletableFuture<String> submit(UUID owner) {
        return queue.submit(owner, () -> "prepared", value -> {
            registrations.incrementAndGet();
            return value;
        }, value -> fail("Unexpected late registration"));
    }

    @Test
    void cancellingPreparedBoardNeverRegistersIt() {
        CompletableFuture<String> future = submit(UUID.randomUUID());
        queue.tick();
        workers.remove().run();
        future.cancel(false);
        queue.tick();
        assertEquals(0, registrations.get());
        assertTrue(queue.isIdle());
    }

    @Test
    void limitsPreparationAndRegistersAtMostOneWorldPerTick() {
        submit(UUID.randomUUID());
        submit(UUID.randomUUID());
        submit(UUID.randomUUID());
        queue.tick();
        assertEquals(2, workers.size());
        workers.remove().run();
        workers.remove().run();
        assertEquals(0, registrations.get());
        queue.tick();
        assertEquals(1, registrations.get());
        assertEquals(1, workers.size());
        workers.remove().run();
        queue.tick();
        assertEquals(2, registrations.get());
        queue.tick();
        assertEquals(3, registrations.get());
        assertTrue(queue.isIdle());
    }

    @Test
    void rejectsOverflowAndReclaimsCancelledPendingRequests() {
        CompletableFuture<String> first = submit(UUID.randomUUID());
        submit(UUID.randomUUID());
        submit(UUID.randomUUID());
        assertEquals("unavailable", submit(UUID.randomUUID()).join());
        first.cancel(false);
        assertFalse(submit(UUID.randomUUID()).isDone());
    }

    @Test
    void cancellingOwnerStopsPendingAndRunningLoadsWithoutAffectingOtherParties() {
        UUID owner = UUID.randomUUID();
        CompletableFuture<String> running = submit(owner);
        CompletableFuture<String> other = submit(UUID.randomUUID());
        CompletableFuture<String> pending = submit(owner);
        queue.tick();
        queue.cancel(owner);
        workers.remove().run();
        workers.remove().run();
        queue.tick();
        queue.tick();
        assertTrue(running.isCancelled());
        assertTrue(pending.isCancelled());
        assertEquals("prepared", other.join());
        assertEquals(1, registrations.get());
        assertTrue(queue.isIdle());
    }

    @Test
    void shutdownDiscardsLatePreparation() {
        CompletableFuture<String> future = submit(UUID.randomUUID());
        queue.tick();
        queue.close();
        workers.remove().run();
        queue.tick();
        assertTrue(future.isCancelled());
        assertEquals(0, registrations.get());
        assertTrue(queue.isIdle());
        assertEquals("unavailable", submit(UUID.randomUUID()).join());
    }

    @Test
    void cancellationDuringRegistrationDisposesTheLoadedWorld() {
        UUID owner = UUID.randomUUID();
        List<String> discarded = new ArrayList<>();
        CompletableFuture<String> future = queue.submit(owner, () -> "prepared", value -> {
            queue.cancel(owner);
            return "loaded";
        }, discarded::add);
        queue.tick();
        workers.remove().run();
        queue.tick();
        assertTrue(future.isCancelled());
        assertEquals(List.of("loaded"), discarded);
        assertTrue(queue.isIdle());
    }

    @Test
    void preparationFailureCompletesCallerAndReleasesCapacity() {
        IllegalStateException failure = new IllegalStateException("read failed");
        CompletableFuture<String> future = queue.submit(UUID.randomUUID(), () -> { throw failure; }, value -> value, value -> fail("Unexpected registration"));
        queue.tick();
        workers.remove().run();
        queue.tick();
        assertEquals("unavailable", future.join());
        assertEquals(List.of(failure), failures);
        assertTrue(queue.isIdle());
    }
}
