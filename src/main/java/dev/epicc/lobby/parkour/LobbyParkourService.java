package dev.epicc.lobby.parkour;

import dev.epicc.config.MessageService;
import dev.epicc.config.PluginConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LobbyParkourService {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final MessageService messages;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    public LobbyParkourService(JavaPlugin plugin, PluginConfig config, MessageService messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
    }

    public boolean isRunning(UUID playerId) {
        return runs.containsKey(playerId);
    }

    public LobbyParkourDefinition definition() {
        return config.lobbyParkour();
    }

    public String action(ItemStack stack) {
        return LobbyParkourItems.action(plugin, stack);
    }

    public void start(Player player) {
        LobbyParkourDefinition definition = config.lobbyParkour();
        if (!definition.isReady() || runs.containsKey(player.getUniqueId())) {
            return;
        }
        ItemStack[] hotbar = new ItemStack[9];
        for (int slot = 0; slot < hotbar.length; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            hotbar[slot] = item == null ? null : item.clone();
            player.getInventory().setItem(slot, null);
        }
        player.getInventory().setItem(0, LobbyParkourItems.restart(plugin, messages));
        player.getInventory().setItem(1, LobbyParkourItems.checkpoint(plugin, messages));
        player.getInventory().setItem(8, LobbyParkourItems.leave(plugin, messages));
        runs.put(player.getUniqueId(), new Run(hotbar, definition.start()));
        messages.send(player, "parkour.started");
    }

    public void updateCheckpoint(Player player, LobbyParkourPoint checkpoint) {
        Run run = runs.get(player.getUniqueId());
        if (run == null || checkpoint.equals(run.checkpoint())) {
            return;
        }
        run.setCheckpoint(checkpoint);
        messages.send(player, "parkour.checkpoint-reached");
    }

    public void restart(Player player) {
        Run run = runs.get(player.getUniqueId());
        if (run == null) {
            return;
        }
        player.teleport(run.start().teleportLocation(player.getLocation()));
        messages.send(player, "parkour.restarted");
    }

    public void teleportCheckpoint(Player player) {
        Run run = runs.get(player.getUniqueId());
        if (run == null) {
            return;
        }
        player.teleport(run.checkpoint().teleportLocation(player.getLocation()));
    }

    public void finish(Player player) {
        if (stop(player)) {
            messages.send(player, "parkour.finished");
        }
    }

    public void leave(Player player) {
        if (stop(player)) {
            messages.send(player, "parkour.left");
        }
    }

    public void stopSilently(Player player) {
        stop(player);
    }

    /**
     * Temporary normal-lobby restoration: reinstate the hotbar captured when the run began.
     * A future lobby hotbar implementation can replace this one method without changing parkour.
     */
    private boolean stop(Player player) {
        Run run = runs.remove(player.getUniqueId());
        if (run == null) {
            return false;
        }
        for (int slot = 0; slot < run.hotbar().length; slot++) {
            player.getInventory().setItem(slot, run.hotbar()[slot]);
        }
        return true;
    }

    private static final class Run {
        private final ItemStack[] hotbar;
        private final LobbyParkourPoint start;
        private LobbyParkourPoint checkpoint;

        private Run(ItemStack[] hotbar, LobbyParkourPoint start) {
            this.hotbar = hotbar;
            this.start = start;
            this.checkpoint = start;
        }

        private ItemStack[] hotbar() {
            return hotbar;
        }

        private LobbyParkourPoint checkpoint() {
            return checkpoint;
        }

        private LobbyParkourPoint start() {
            return start;
        }

        private void setCheckpoint(LobbyParkourPoint checkpoint) {
            this.checkpoint = checkpoint;
        }
    }
}
