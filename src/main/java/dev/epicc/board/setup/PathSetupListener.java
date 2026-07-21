package dev.epicc.board.setup;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class PathSetupListener implements Listener {

    private final JavaPlugin plugin;
    private final PathSetupService setup;

    public PathSetupListener(JavaPlugin plugin, PathSetupService setup) {
        this.plugin = plugin;
        this.setup = setup;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!setup.isSettingUp(player.getUniqueId())) {
            return;
        }
        // WE updates selection during the interact chain; read after MONITOR
        setup.tryPlaceFromWorldEdit(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!setup.isSettingUp(player.getUniqueId())) {
            return;
        }
        if (!isPos1Command(event.getMessage())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> setup.tryPlaceFromWorldEdit(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        setup.cancel(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        setup.cancel(event.getPlayer());
    }

    private static boolean isPos1Command(String raw) {
        String msg = raw.trim().toLowerCase(Locale.ROOT);
        while (msg.startsWith("/")) {
            msg = msg.substring(1);
        }
        // pos1 | worldedit pos1 | we pos1 | worldedit:pos1
        if (msg.equals("pos1") || msg.startsWith("pos1 ")) {
            return true;
        }
        if (msg.equals("worldedit pos1") || msg.startsWith("worldedit pos1 ")) {
            return true;
        }
        if (msg.equals("we pos1") || msg.startsWith("we pos1 ")) {
            return true;
        }
        return msg.endsWith(":pos1") || msg.contains(":pos1 ");
    }
}
