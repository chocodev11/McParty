package dev.epicc.minigame;

import com.infernalsuite.asp.api.world.SlimeWorld;
import dev.epicc.config.MessageService;
import dev.epicc.party.PartyInstance;
import dev.epicc.slime.SlimeWorldService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class MinigameManager {

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final MinigameRegistry registry;
    private final SlimeWorldService slime;
    private int revealDurationTicks;
    private int revealIntervalMinTicks;
    private int revealIntervalMaxTicks;
    private int revealExpandIntervalTicks;
    private int revealColorSteps;
    private int revealColorIntervalTicks;

    private Minigame active;
    private MinigameRevealAnimator reveal;

    public MinigameManager(
            JavaPlugin plugin,
            MessageService messages,
            MinigameRegistry registry,
            SlimeWorldService slime,
            int revealDurationTicks,
            int revealIntervalMinTicks,
            int revealIntervalMaxTicks,
            int revealExpandIntervalTicks,
            int revealColorSteps,
            int revealColorIntervalTicks
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.registry = registry;
        this.slime = slime;
        reconfigure(
                revealDurationTicks,
                revealIntervalMinTicks,
                revealIntervalMaxTicks,
                revealExpandIntervalTicks,
                revealColorSteps,
                revealColorIntervalTicks
        );
    }

    public void reconfigure(
            int revealDurationTicks,
            int revealIntervalMinTicks,
            int revealIntervalMaxTicks,
            int revealExpandIntervalTicks,
            int revealColorSteps,
            int revealColorIntervalTicks
    ) {
        this.revealDurationTicks = revealDurationTicks;
        this.revealIntervalMinTicks = revealIntervalMinTicks;
        this.revealIntervalMaxTicks = revealIntervalMaxTicks;
        this.revealExpandIntervalTicks = revealExpandIntervalTicks;
        this.revealColorSteps = revealColorSteps;
        this.revealColorIntervalTicks = revealColorIntervalTicks;
    }

    public MinigameRegistry registry() {
        return registry;
    }

    /** Gather all ASP slime template names declared by registered minigames. */
    public Set<String> getSlimeTemplates() {
        Set<String> templates = new HashSet<>();
        for (Minigame mg : registry.all()) {
            mg.slimeTemplate().ifPresent(t -> {
                if (!t.isBlank()) {
                    templates.add(t.trim());
                }
            });
        }
        return templates;
    }

    /**
     * Pick a random minigame, run title reveal (no teleport), then start it in place.
     */
    public void runRandom(PartyInstance instance, List<Player> players, Consumer<MinigameResult> done) {
        runMinigame(registry.pickRandom(), instance, players, done);
    }

    /**
     * Run a specific minigame (e.g. for admin testing).
     */
    public void runSpecific(Minigame minigame, List<Player> players, Consumer<MinigameResult> done) {
        runMinigame(minigame, null, players, done);
    }

    public void runMinigame(Minigame minigame, PartyInstance instance, List<Player> players, Consumer<MinigameResult> done) {
        cancelActive();
        List<Player> online = List.copyOf(players);

        if (online.isEmpty()) {
            done.accept(new MinigameResult());
            return;
        }

        final AtomicBoolean minigameWorldReady = new AtomicBoolean(true);
        final AtomicBoolean revealFinished = new AtomicBoolean(false);

        String template = minigame.slimeTemplate().orElse(null);
        if (slime != null && slime.isReady() && template != null && !template.isBlank()) {
            minigameWorldReady.set(false);
            final UUID instanceId = instance != null ? instance.id() : UUID.randomUUID();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                Optional<SlimeWorld> clone = slime.prepareClone(instanceId, template);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (clone.isPresent()) {
                        slime.loadClone(instanceId, template, clone.get());
                    }
                    minigameWorldReady.set(true);
                    if (revealFinished.get()) {
                        proceedToStart(minigame, instance, online, done);
                    }
                });
            });
        }

        if (revealDurationTicks <= 0) {
            revealFinished.set(true);
            if (minigameWorldReady.get()) {
                proceedToStart(minigame, instance, online, done);
            }
            return;
        }

        reveal = new MinigameRevealAnimator(
                plugin,
                messages,
                revealDurationTicks,
                revealIntervalMinTicks,
                revealIntervalMaxTicks,
                revealExpandIntervalTicks,
                revealColorSteps,
                revealColorIntervalTicks
        );
        reveal.start(online, minigame, registry.displayNames(), () -> {
            reveal = null;
            revealFinished.set(true);
            if (minigameWorldReady.get()) {
                proceedToStart(minigame, instance, online, done);
            }
        });
    }

    private void proceedToStart(Minigame minigame, PartyInstance instance, List<Player> online, Consumer<MinigameResult> done) {
        if (instance != null && instance.state() == dev.epicc.party.PartyState.CLEANUP) {
            return;
        }
        List<Player> stillOnline = online.stream().filter(Player::isOnline).toList();
        if (stillOnline.isEmpty()) {
            done.accept(new MinigameResult());
            return;
        }
        startNow(minigame, instance, stillOnline, done);
    }


    public void cancelActive() {
        if (reveal != null) {
            reveal.cancel();
            reveal = null;
        }
        if (active != null) {
            active.cancel();
            active = null;
        }
    }

    private void startNow(
            Minigame minigame,
            PartyInstance instance,
            List<Player> players,
            Consumer<MinigameResult> done
    ) {
        active = minigame;
        MinigameContext ctx = new MinigameContext(plugin, instance, players);
        minigame.start(ctx, result -> {
            active = null;
            done.accept(result);
        });
    }
}
