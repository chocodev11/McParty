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
    private final Runnable hibernateBoardCallback;

    public MinigameContext(JavaPlugin plugin, PartyInstance instance, List<Player> onlinePlayers, Runnable hibernateBoardCallback) {
        this.plugin = plugin;
        this.instance = instance;
        this.onlinePlayers = List.copyOf(onlinePlayers);
        this.hibernateBoardCallback = hibernateBoardCallback;
    }

    public JavaPlugin plugin() { return plugin; }
    public PartyInstance instance() { return instance; }
    public List<Player> onlinePlayers() { return onlinePlayers; }
    
    public void hibernateBoard() {
        if (hibernateBoardCallback != null) {
            hibernateBoardCallback.run();
        }
    }

    public List<PartyPlayer> partyPlayers() {
        return onlinePlayers.stream()
                .map(p -> instance.player(p.getUniqueId()).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
