package dev.epicc.minigame;

import dev.epicc.containment.SlotBoundary;
import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyPlayArea;
import dev.epicc.party.PartyState;
import dev.epicc.slime.SlimeWorldService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns one party's reveal animation and mutable minigame session. */
public final class MinigameRunner {
    private final MinigameManager manager;
    private MinigameSession active;
    private MinigameRevealAnimator reveal;
    private long generation;
    private UUID arenaOwner;
    private World arenaWorld;

    MinigameRunner(MinigameManager manager) { this.manager = manager; }

    public void runRandom(PartyInstance instance, List<Player> players, ArenaTransitions transitions, Consumer<MinigameResult> done) {
        run(manager.registry().pickRandom(), instance, players, transitions, done);
    }

    public void run(Minigame definition, PartyInstance instance, List<Player> players, ArenaTransitions transitions,
                    Consumer<MinigameResult> done) {
        cancel();
        long runGeneration = ++generation;
        List<Player> online = List.copyOf(players);
        if (online.isEmpty()) { done.accept(new MinigameResult()); return; }

        AtomicBoolean arenaReady = new AtomicBoolean(true);
        AtomicBoolean revealFinished = new AtomicBoolean(false);
        if (!loadArena(definition, instance, runGeneration, arenaReady, revealFinished, online, transitions, done)) {
            return; // arena unusable — done already accepted, must not also start the reveal
        }
        if (manager.reveal().skip()) {
            revealFinished.set(true);
            if (arenaReady.get()) startIfCurrent(definition, instance, runGeneration, online, transitions, done);
            return;
        }
        reveal = new MinigameRevealAnimator(manager.plugin(), manager.messages(), manager.reveal());
        reveal.start(online, definition, manager.registry().displayNames(), () -> {
            if (!isCurrent(runGeneration)) return;
            reveal = null;
            revealFinished.set(true);
            if (arenaReady.get()) startIfCurrent(definition, instance, runGeneration, online, transitions, done);
        });
    }

    public void cancel() {
        generation++;
        if (reveal != null) { reveal.cancel(); reveal = null; }
        if (active != null) { active.cancel(); active = null; }
        unloadArena();
    }

    /** @return false when the arena is unusable and {@code done} has already been accepted. */
    private boolean loadArena(Minigame definition, PartyInstance instance, long runGeneration, AtomicBoolean arenaReady,
                              AtomicBoolean revealFinished, List<Player> online, ArenaTransitions transitions,
                              Consumer<MinigameResult> done) {
        Optional<MinigameArenaSpec> specOpt = definition.arenaSpec();
        if (specOpt.isEmpty()) return true;
        MinigameArenaSpec spec = specOpt.get();
        SlimeWorldService slime = manager.slime();
        if (!spec.isValid() || slime == null || !slime.isReady()) {
            done.accept(new MinigameResult());
            return false;
        }
        arenaReady.set(false);
        UUID owner = instance != null ? instance.id() : UUID.randomUUID();
        slime.loadCloneAsync(owner, spec.template()).thenAccept(worldOpt -> {
            if (!isCurrent(runGeneration)) {
                worldOpt.ifPresent(world -> slime.unloadWorldForInstance(owner, world));
                return;
            }
            if (worldOpt.isEmpty()) {
                cancel(); // stop the reveal already running, then end the round once
                done.accept(new MinigameResult());
                return;
            }
            arenaOwner = owner;
            arenaWorld = worldOpt.get();
            arenaReady.set(true);
            if (revealFinished.get()) startIfCurrent(definition, instance, runGeneration, online, transitions, done);
        });
        return true;
    }

    private void startIfCurrent(Minigame definition, PartyInstance instance, long runGeneration, List<Player> online,
                                ArenaTransitions transitions, Consumer<MinigameResult> done) {
        if (!isCurrent(runGeneration) || (instance != null && instance.state() != PartyState.PLAYING)) return;
        List<Player> stillOnline = online.stream().filter(Player::isOnline).toList();
        if (stillOnline.isEmpty()) { finish(runGeneration, transitions, done, new MinigameResult()); return; }
        MinigameArena arena = createArena(definition.arenaSpec().orElse(null));
        if (arena != null) transitions.enter().accept(arena);
        MinigameSession session = definition.createSession();
        active = session;
        MinigameContext context = new MinigameContext(
                manager.plugin(), manager.messages(), manager.events(), instance, stillOnline, arena
        );
        session.start(context, result -> {
            if (!isCurrent(runGeneration) || active != session) return;
            active = null;
            finish(runGeneration, transitions, done, result);
        });
    }

    private MinigameArena createArena(MinigameArenaSpec spec) {
        if (spec == null || arenaWorld == null) return null;
        Location spawn = new Location(arenaWorld, spec.spawnX(), spec.spawnY(), spec.spawnZ(), spec.spawnYaw(), spec.spawnPitch());
        SlotBoundary boundary = new SlotBoundary(arenaWorld, spec.minX(), spec.minY(), spec.minZ(), spec.maxX(), spec.maxY(), spec.maxZ());
        return new MinigameArena(spec.template(), new PartyPlayArea(arenaWorld, spawn, boundary));
    }

    private void finish(long runGeneration, ArenaTransitions transitions, Consumer<MinigameResult> done, MinigameResult result) {
        if (!isCurrent(runGeneration)) return;
        transitions.exit().run();
        unloadArena();
        done.accept(result);
    }

    private void unloadArena() {
        // Force-cancel can run before players are evacuated; party cleanup unloads those
        // worlds via the instance mapping, so skip instead of logging a refusal.
        if (arenaWorld != null && arenaOwner != null && arenaWorld.getPlayers().isEmpty()) {
            manager.slime().unloadWorldForInstance(arenaOwner, arenaWorld);
        }
        arenaWorld = null;
        arenaOwner = null;
    }

    private boolean isCurrent(long runGeneration) { return generation == runGeneration; }
}
