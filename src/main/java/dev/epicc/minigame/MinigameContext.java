package dev.epicc.minigame;

import dev.epicc.party.PartyInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;

public final class MinigameContext {

    private final JavaPlugin plugin;
    private final PartyInstance instance;
    private final List<Player> onlinePlayers;
    private final MinigameArena arena;

    public MinigameContext(JavaPlugin plugin, PartyInstance instance, List<Player> onlinePlayers, MinigameArena arena) {
        this.plugin = plugin;
        this.instance = instance;
        this.onlinePlayers = List.copyOf(onlinePlayers);
        this.arena = arena;
    }

    public JavaPlugin plugin() { return plugin; }
    public PartyInstance instance() { return instance; }
    public List<Player> onlinePlayers() { return onlinePlayers; }
    public Optional<MinigameArena> arena() { return Optional.ofNullable(arena); }
}
