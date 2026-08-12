package dev.epicc.party;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.InventoryHolder;

/** Prevents gameplay interactions in the configured and per-party lobby worlds. */
public final class LobbyProtectionListener implements Listener {

    private final LobbyMatchmaker lobbyMatchmaker;

    public LobbyProtectionListener(LobbyMatchmaker lobbyMatchmaker) {
        this.lobbyMatchmaker = lobbyMatchmaker;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && lobbyMatchmaker.isLobbyWorld(player.getWorld())) {
            event.setCancelled(true);
            player.setFallDistance(0.0f);
            return;
        }

        if (event instanceof EntityDamageByEntityEvent damage
                && isPlayerAttack(damage.getDamager())
                && lobbyMatchmaker.isLobbyWorld(damage.getDamager().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player
                && lobbyMatchmaker.isLobbyWorld(player.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (lobbyMatchmaker.isLobbyWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (lobbyMatchmaker.isLobbyWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (lobbyMatchmaker.isLobbyWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (lobbyMatchmaker.isLobbyWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || !lobbyMatchmaker.isLobbyWorld(event.getPlayer().getWorld())) {
            return;
        }
        if (event.getClickedBlock().getState() instanceof InventoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (lobbyMatchmaker.isLobbyWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    private static boolean isPlayerAttack(org.bukkit.entity.Entity entity) {
        return entity instanceof Player
                || entity instanceof Projectile projectile && projectile.getShooter() instanceof Player;
    }
}
