package dev.epicc.board.setup;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class PathSetupListener implements Listener {

    private final JavaPlugin plugin;
    private final PathSetupService setup;

    public PathSetupListener(JavaPlugin plugin, PathSetupService setup) {
        this.plugin = plugin;
        this.setup = setup;
    }

    /**
     * Holding the path stick: treat the broken block as the space center and cancel the break.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!setup.isSettingUp(player.getUniqueId())) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!PathSetupWand.isWand(plugin, hand)) {
            return;
        }
        event.setCancelled(true);
        setup.onPrimarySelected(player, event.getBlock().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        setup.cancel(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        setup.cancel(event.getPlayer());
    }
}
