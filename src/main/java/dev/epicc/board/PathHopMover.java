package dev.epicc.board;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Path move: rise in place → teleport high above target → natural fall (no fall damage).
 */
public final class PathHopMover implements Listener {

    private final JavaPlugin plugin;
    private final int riseTicks;
    private final double height;
    private final int fallMaxTicks;

    private final Map<UUID, Hop> hops = new ConcurrentHashMap<>();
    /** Brief post-land immunity so late FALL events do not hurt. */
    private final Set<UUID> fallImmune = ConcurrentHashMap.newKeySet();

    public PathHopMover(
            JavaPlugin plugin,
            double height,
            double riseSeconds,
            double fallMaxSeconds
    ) {
        this.plugin = plugin;
        this.height = Math.max(0.5, height);
        this.riseTicks = Math.max(1, (int) Math.round(Math.max(0.05, riseSeconds) * 20.0));
        this.fallMaxTicks = Math.max(10, (int) Math.round(Math.max(0.5, fallMaxSeconds) * 20.0));
    }

    public boolean isHopping(UUID playerId) {
        return hops.containsKey(playerId);
    }

    /**
     * Animate hop to {@code dest}. {@code onDone} runs on the main thread when finished
     * (or immediately if player offline / dest null).
     */
    public void hop(Player player, Location dest, Runnable onDone) {
        if (player == null || !player.isOnline() || dest == null) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }

        cancel(player.getUniqueId());

        Location start = player.getLocation().clone();
        Location land = dest.clone();
        Hop hop = new Hop(player.getUniqueId(), start, land, onDone);
        hops.put(player.getUniqueId(), hop);

        player.setFallDistance(0f);
        player.setVelocity(new Vector(0, 0, 0));

        hop.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(hop), 0L, 1L);
    }

    /** Abort hop without running {@code onDone} (party end / replace). */
    public void cancel(UUID playerId) {
        Hop hop = hops.remove(playerId);
        if (hop == null) {
            return;
        }
        if (hop.task != null) {
            hop.task.cancel();
            hop.task = null;
        }
        fallImmune.remove(playerId);
        Player p = plugin.getServer().getPlayer(playerId);
        if (p != null && p.isOnline()) {
            p.setFallDistance(0f);
        }
    }

    public void cancelAll() {
        for (UUID id : new java.util.ArrayList<>(hops.keySet())) {
            cancel(id);
        }
        fallImmune.clear();
    }

    private void tick(Hop hop) {
        Player player = plugin.getServer().getPlayer(hop.playerId);
        if (player == null || !player.isOnline()) {
            finish(hop, false);
            return;
        }

        hop.tick++;

        if (hop.phase == Phase.RISE) {
            double t = Math.min(1.0, (double) hop.tick / riseTicks);
            // ease-out rise
            double eased = 1.0 - (1.0 - t) * (1.0 - t);
            Location at = hop.start.clone();
            at.setY(hop.start.getY() + height * eased);
            at.setYaw(player.getLocation().getYaw());
            at.setPitch(player.getLocation().getPitch());
            player.teleport(at);
            player.setVelocity(new Vector(0, 0, 0));
            player.setFallDistance(0f);

            if (hop.tick >= riseTicks) {
                hop.phase = Phase.FALL;
                hop.tick = 0;
                Location peak = hop.land.clone().add(0, height, 0);
                peak.setYaw(hop.land.getYaw());
                peak.setPitch(hop.land.getPitch());
                player.teleport(peak);
                player.setFallDistance(0f);
                // small downward nudge so client starts falling immediately
                player.setVelocity(new Vector(0, -0.15, 0));
            }
            return;
        }

        // FALL — gravity only; keep fallDistance for animation, block damage via listener
        if (player.isOnGround() || hop.tick >= fallMaxTicks) {
            Location land = hop.land.clone();
            // snap to path point yaw/pitch; keep XZ if already close
            player.teleport(land);
            player.setFallDistance(0f);
            player.setVelocity(new Vector(0, 0, 0));
            finish(hop, true);
        }
    }

    private void finish(Hop hop, boolean runCallback) {
        if (hop.task != null) {
            hop.task.cancel();
            hop.task = null;
        }
        hops.remove(hop.playerId, hop);

        Player p = plugin.getServer().getPlayer(hop.playerId);
        if (p != null && p.isOnline()) {
            p.setFallDistance(0f);
        }

        UUID id = hop.playerId;
        fallImmune.add(id);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            fallImmune.remove(id);
            Player again = plugin.getServer().getPlayer(id);
            if (again != null) {
                again.setFallDistance(0f);
            }
        }, 15L);

        if (runCallback && hop.onDone != null) {
            hop.onDone.run();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (hops.containsKey(id) || fallImmune.contains(id)) {
            event.setCancelled(true);
            player.setFallDistance(0f);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancel(id);
        fallImmune.remove(id);
    }

    private enum Phase {
        RISE,
        FALL
    }

    private static final class Hop {
        final UUID playerId;
        final Location start;
        final Location land;
        final Runnable onDone;
        Phase phase = Phase.RISE;
        int tick;
        BukkitTask task;

        Hop(UUID playerId, Location start, Location land, Runnable onDone) {
            this.playerId = playerId;
            this.start = start;
            this.land = land;
            this.onDone = onDone;
        }
    }
}
