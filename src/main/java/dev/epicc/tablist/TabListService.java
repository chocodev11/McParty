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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Optional;

/** Renders the configurable component-based tab list for each viewer. */
public final class TabListService implements Listener {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final MessageService messages;
    private final PartyManager parties;
    private BukkitTask refreshTask;

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
        refreshAll();
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
        Component header = component(player, "tablist.header");
        Component footer = component(player, "tablist.footer");
        player.sendPlayerListHeaderAndFooter(header, footer);

        if (messages.raw("tablist.player-name", "").isBlank()) {
            player.playerListName(null);
        } else {
            player.playerListName(component(player, "tablist.player-name"));
        }
    }

    public void shutdown() {
        stopTask();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!config.tabListEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> refresh(event.getPlayer()));
    }

    private Component component(Player viewer, String path) {
        String raw = messages.raw(path, "");
        if (raw.isBlank()) {
            return Component.empty();
        }
        Optional<PartyInstance> party = parties.instanceOf(viewer.getUniqueId());
        int coins = party.flatMap(instance -> instance.player(viewer.getUniqueId()))
                .map(player -> player.coins())
                .orElse(0);
        return messages.get(
                path,
                MessageService.ph("player", viewer.getName()),
                MessageService.ph("uuid", viewer.getUniqueId().toString()),
                MessageService.ph("world", viewer.getWorld().getName()),
                MessageService.ph("online", Bukkit.getOnlinePlayers().size()),
                MessageService.ph("party", party.map(PartyInstance::shortId).orElse("")),
                MessageService.ph("coins", coins)
        );
    }

    private void stopTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private void clearAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            player.playerListName(null);
        }
    }
}
