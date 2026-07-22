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

    private Minigame active;
    private MinigameRevealAnimator reveal;

    public MinigameManager(
            JavaPlugin plugin,
            MessageService messages,
            MinigameRegistry registry,
            int revealDurationTicks,
            int revealIntervalMinTicks,
            int revealIntervalMaxTicks,
            int revealExpandIntervalTicks
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.registry = registry;
        reconfigure(
                revealDurationTicks,
                revealIntervalMinTicks,
                revealIntervalMaxTicks,
                revealExpandIntervalTicks
        );
    }

    public void reconfigure(
            int revealDurationTicks,
            int revealIntervalMinTicks,
            int revealIntervalMaxTicks,
            int revealExpandIntervalTicks
    ) {
        this.revealDurationTicks = revealDurationTicks;
        this.revealIntervalMinTicks = revealIntervalMinTicks;
        this.revealIntervalMaxTicks = revealIntervalMaxTicks;
        this.revealExpandIntervalTicks = revealExpandIntervalTicks;
    }

    public MinigameRegistry registry() {
        return registry;
    }

    /**
     * Pick a random minigame, run title reveal (no teleport), then start it in place.
     */
    public void runRandom(PartyInstance instance, List<Player> players, Consumer<MinigameResult> done) {
        cancelActive();
        Minigame picked = registry.pickRandom();
        List<Player> online = List.copyOf(players);

        if (revealDurationTicks <= 0 || online.isEmpty()) {
            startNow(picked, instance, online, done);
            return;
        }

        reveal = new MinigameRevealAnimator(
                plugin,
                messages,
                revealDurationTicks,
                revealIntervalMinTicks,
                revealIntervalMaxTicks,
                revealExpandIntervalTicks
        );
        reveal.start(online, picked, registry.displayNames(), () -> {
            reveal = null;
            if (instance == null) {
                return;
            }
            List<Player> stillOnline = online.stream().filter(Player::isOnline).toList();
            if (stillOnline.isEmpty()) {
                done.accept(new MinigameResult());
                return;
            }
            startNow(picked, instance, stillOnline, done);
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
