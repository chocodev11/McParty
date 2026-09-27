package dev.epicc.tablist;

import dev.epicc.config.MessageService;
import dev.epicc.config.PluginConfig;
import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Renders the configurable component-based tab list for each viewer. */
public final class TabListService implements Listener {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final MessageService messages;
    private final PartyManager parties;
    private BukkitTask refreshTask;
    private final Map<UUID, UUID> knownParties = new HashMap<>();

    public TabListService(
            JavaPlugin plugin,
            PluginConfig config,
            MessageService messages,
            PartyManager parties
    ) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.parties = parties;
    }

    public void start() {
        stopTask();
        if (!config.tabListEnabled()) {
            return;
        }
        refreshTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::refreshAll,
                1L,
                config.tabListRefreshTicks()
        );
        refreshMembership();
    }

    public void reload() {
        clearAll();
        start();
    }

    public void refreshAll() {
        if (!config.tabListEnabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    public void refresh(Player player) {
        if (!config.tabListEnabled() || player == null || !player.isOnline()) {
            return;
        }
        updatePlayerListName(player);
        Component header = component(player, config.tabListHeader());
        Component footer = component(player, config.tabListFooter());
        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    public void shutdown() {
        stopTask();
        clearAll();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!config.tabListEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, this::refreshMembership);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        knownParties.remove(event.getPlayer().getUniqueId());
    }

    /** Membership changes affect only edges to the players whose party changed. */
    public void refreshMembership() {
        if (!config.tabListEnabled()) return;
        var online = new ArrayList<>(Bukkit.getOnlinePlayers());
        Set<UUID> present = new HashSet<>();
        for (Player player : online) {
            UUID id = player.getUniqueId();
            UUID party = parties.instanceOf(id).map(PartyInstance::id).orElse(null);
            present.add(id);
            if (!knownParties.containsKey(id) || !Objects.equals(knownParties.get(id), party)) {
                for (Player other : online) {
                    updateVisibility(player, other);
                    if (!player.equals(other)) updateVisibility(other, player);
                }
            }
            knownParties.put(id, party);
        }
        knownParties.keySet().retainAll(present);
        refreshAll();
    }

    private Component component(Player viewer, String raw) {
        if (raw.isBlank()) {
            return Component.empty();
        }
        Optional<PartyInstance> party = parties.instanceOf(viewer.getUniqueId());
        int coins = party.flatMap(instance -> instance.player(viewer.getUniqueId()))
                .map(player -> player.coins())
                .orElse(0);
        return messages.render(
                raw,
                MessageService.ph("player", viewer.getName()),
                MessageService.ph("uuid", viewer.getUniqueId().toString()),
                MessageService.ph("world", viewer.getWorld().getName()),
                MessageService.ph("online", Bukkit.getOnlinePlayers().size()),
                MessageService.ph("party", party.map(PartyInstance::shortId).orElse("")),
                MessageService.ph("coins", coins)
        );
    }

    private void updatePlayerListName(Player player) {
        String raw = parties.instanceOf(player.getUniqueId())
                .filter(instance -> instance.isHost(player.getUniqueId()))
                .map(instance -> config.tabListHostPlayerName())
                .filter(template -> !template.isBlank())
                .orElse(config.tabListPlayerName());
        if (raw.isBlank()) {
            player.playerListName(null);
        } else {
            player.playerListName(component(player, raw));
        }
    }

    private void updateVisibility(Player viewer, Player target) {
        boolean shouldList = target.equals(viewer)
                || !config.tabListPartyOnly()
                || parties.instanceOf(viewer.getUniqueId())
                        .map(instance -> instance.player(target.getUniqueId()).isPresent()).orElse(false);
        if (shouldList) viewer.listPlayer(target);
        else viewer.unlistPlayer(target);
    }

    private void stopTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private void clearAll() {
        knownParties.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            player.playerListName(null);
            for (Player target : Bukkit.getOnlinePlayers()) {
                player.listPlayer(target);
            }
        }
    }
}
