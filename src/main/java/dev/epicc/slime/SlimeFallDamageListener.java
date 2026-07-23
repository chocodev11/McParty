package dev.epicc.slime;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * No fall damage inside McParty slime instance worlds (hops, pads, mis-jumps).
 */
public final class SlimeFallDamageListener implements Listener {

    private final SlimeWorldService slime;

    public SlimeFallDamageListener(SlimeWorldService slime) {
        this.slime = slime;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!slime.isInstanceWorld(player.getWorld())) {
            return;
        }
        event.setCancelled(true);
        player.setFallDistance(0f);
    }
}
