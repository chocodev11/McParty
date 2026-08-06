package dev.epicc.minigame;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single Bukkit listener shared by every running minigame. Events are routed to the
 * {@link MatchScope} that owns the player, so a server with many concurrent parties keeps
 * one handler per event type instead of one per session.
 */
public final class MinigameEventBus implements Listener {

    private final Map<UUID, MatchScope> byPlayer = new ConcurrentHashMap<>();

    void register(MatchScope scope, Collection<UUID> playerIds) {
        for (UUID id : playerIds) {
            byPlayer.put(id, scope);
        }
    }

    void unregister(MatchScope scope) {
        byPlayer.values().removeIf(known -> known == scope);
    }

    private MatchScope scopeOf(Player player) {
        return player == null ? null : byPlayer.get(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        MatchScope scope = scopeOf(event.getPlayer());
        if (scope != null) {
            scope.listener().onInteract(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        MatchScope scope = scopeOf(event.getPlayer());
        if (scope != null) {
            scope.listener().onDropItem(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        MatchScope scope = scopeOf(event.getPlayer());
        if (scope != null) {
            scope.listener().onBlockBreak(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        MatchScope scope = scopeOf(player);
        if (scope != null) {
            scope.listener().onPickupItem(player, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player shooter)) {
            return;
        }
        MatchScope scope = scopeOf(shooter);
        if (scope != null) {
            scope.listener().onProjectileHit(shooter, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        MatchScope scope = scopeOf(victim);
        if (scope != null && scope.damageProtected()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        MatchScope scope = scopeOf(attacker);
        if (scope != null && scope.damageProtected()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        MatchScope scope = scopeOf(event.getPlayer());
        if (scope != null) {
            scope.listener().onQuit(event.getPlayer());
        }
    }
}
