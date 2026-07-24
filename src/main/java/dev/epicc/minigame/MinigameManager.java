package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import dev.epicc.party.PartyInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.Consumer;

public final class MinigameManager {

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final MinigameRegistry registry;
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

        if (revealDurationTicks <= 0 || online.isEmpty()) {
            startNow(minigame, instance, online, done);
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
            if (instance != null && instance.state() == dev.epicc.party.PartyState.CLEANUP) {
                return;
            }
            List<Player> stillOnline = online.stream().filter(Player::isOnline).toList();
            if (stillOnline.isEmpty()) {
                done.accept(new MinigameResult());
                return;
            }
            startNow(minigame, instance, stillOnline, done);
        });
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
