package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import dev.epicc.slime.SlimeWorldService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.Consumer;

/**
 * Global minigame catalogue and configuration. Each party receives its own
 * {@link MinigameRunner}, which owns the mutable state for one active run.
 */
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

    public MinigameRunner createRunner() {
        return new MinigameRunner(this);
    }

    /** Run an isolated minigame session for admin testing. */
    public void runSpecific(Minigame minigame, List<Player> players, Consumer<MinigameResult> done) {
        createRunner().run(minigame, null, players, ArenaTransitions.none(), done);
    }

    JavaPlugin plugin() {
        return plugin;
    }

    MessageService messages() {
        return messages;
    }

    SlimeWorldService slime() {
        return slime;
    }

    int revealDurationTicks() {
        return revealDurationTicks;
    }

    int revealIntervalMinTicks() {
        return revealIntervalMinTicks;
    }

    int revealIntervalMaxTicks() {
        return revealIntervalMaxTicks;
    }

    int revealExpandIntervalTicks() {
        return revealExpandIntervalTicks;
    }

    int revealColorSteps() {
        return revealColorSteps;
    }

    int revealColorIntervalTicks() {
        return revealColorIntervalTicks;
    }
}
