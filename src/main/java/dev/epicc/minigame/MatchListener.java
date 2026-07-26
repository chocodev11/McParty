package dev.epicc.minigame;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Gameplay hooks a session receives from {@link MinigameEventBus} through its {@link MatchScope}.
 * Sessions never register their own Bukkit listener; the bus routes by player so one handler
 * per event type serves every running match.
 * <p>
 * Add a hook here (and a matching handler on the bus) when a new minigame needs another event.
 */
public interface MatchListener {

    /** For sessions that need no gameplay events. */
    MatchListener NONE = new MatchListener() {
    };

    default void onQuit(Player player) {
    }

    default void onInteract(PlayerInteractEvent event) {
    }

    default void onDropItem(PlayerDropItemEvent event) {
    }

    default void onPickupItem(Player player, EntityPickupItemEvent event) {
    }

    /** {@code shooter} is the match player who fired the projectile. */
    default void onProjectileHit(Player shooter, ProjectileHitEvent event) {
    }
}
