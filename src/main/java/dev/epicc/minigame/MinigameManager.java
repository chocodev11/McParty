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
    private final MinigameEventBus events;
    private MinigameRevealSettings reveal;

    public MinigameManager(
            JavaPlugin plugin,
            MessageService messages,
            MinigameRegistry registry,
            SlimeWorldService slime,
            MinigameEventBus events,
            MinigameRevealSettings reveal
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.registry = registry;
        this.slime = slime;
        this.events = events;
        this.reveal = reveal;
    }

    public void reconfigure(MinigameRevealSettings reveal) {
        this.reveal = reveal;
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

    MinigameEventBus events() {
        return events;
    }

    SlimeWorldService slime() {
        return slime;
    }

    MinigameRevealSettings reveal() {
        return reveal;
    }
}
