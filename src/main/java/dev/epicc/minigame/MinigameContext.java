package dev.epicc.minigame;

import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class MinigameContext {

    private final JavaPlugin plugin;
    private final PartyInstance instance;
    private final List<Player> onlinePlayers;

    public MinigameContext(JavaPlugin plugin, PartyInstance instance, List<Player> onlinePlayers) {
        this.plugin = plugin;
        this.instance = instance;
        this.onlinePlayers = List.copyOf(onlinePlayers);
    }

    public JavaPlugin plugin() { return plugin; }
    public PartyInstance instance() { return instance; }
    public List<Player> onlinePlayers() { return onlinePlayers; }

    public List<PartyPlayer> partyPlayers() {
        return onlinePlayers.stream()
                .map(p -> instance.player(p.getUniqueId()).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
