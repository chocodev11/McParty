package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import dev.epicc.party.PartyInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;

public final class MinigameContext {

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final MinigameEventBus events;
    private final PartyInstance instance;
    private final List<Player> onlinePlayers;
    private final MinigameArena arena;

    public MinigameContext(
            JavaPlugin plugin,
            MessageService messages,
            MinigameEventBus events,
            PartyInstance instance,
            List<Player> onlinePlayers,
            MinigameArena arena
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.events = events;
        this.instance = instance;
        this.onlinePlayers = List.copyOf(onlinePlayers);
        this.arena = arena;
    }

    public JavaPlugin plugin() { return plugin; }
    public MessageService messages() { return messages; }
    public MinigameEventBus events() { return events; }
    public PartyInstance instance() { return instance; }
    public List<Player> onlinePlayers() { return onlinePlayers; }
    public Optional<MinigameArena> arena() { return Optional.ofNullable(arena); }
}
