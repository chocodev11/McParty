package dev.epicc.board;

import org.bukkit.Location;
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
 * Path move: upward velocity hop → at apex (vy ≤ 0) teleport to target XZ at that Y → fall.
 * No potion effects; no per-tick Y teleports during rise.
 */
public final class PathHopMover implements Listener {

    private final JavaPlugin plugin;
    private double upVelocity;
    private int riseMaxTicks;
    private int fallMaxTicks;

    private final Map<UUID, Hop> hops = new ConcurrentHashMap<>();
    private final Set<UUID> fallImmune = ConcurrentHashMap.newKeySet();

    public PathHopMover(
            JavaPlugin plugin,
            double upVelocity,
            double riseMaxSeconds,
            double fallMaxSeconds
    ) {
        this.plugin = plugin;
        reconfigure(upVelocity, riseMaxSeconds, fallMaxSeconds);
    }

    public void reconfigure(double upVelocity, double riseMaxSeconds, double fallMaxSeconds) {
        this.upVelocity = Math.max(0.1, upVelocity);
        this.riseMaxTicks = Math.max(10, (int) Math.round(Math.max(0.5, riseMaxSeconds) * 20.0));
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

        Location land = dest.clone();
        Hop hop = new Hop(player.getUniqueId(), land, onDone, player.getWalkSpeed());
        hops.put(player.getUniqueId(), hop);

        player.setFallDistance(0f);
        player.setWalkSpeed(0f);
        player.setFlying(false);
        player.setAllowFlight(false);
        // Single upward impulse — gravity brings vy down to ≤ 0 at the apex
        player.setVelocity(new Vector(0, upVelocity, 0));

        hop.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(hop), 1L, 1L);
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
            clearHopState(p, hop.savedWalkSpeed);
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
            Vector v = player.getVelocity();
            // Pin XZ only — keep natural Y from velocity + gravity
            if (v.getX() != 0.0 || v.getZ() != 0.0) {
                player.setVelocity(new Vector(0, v.getY(), 0));
                v = player.getVelocity();
            }
            player.setFallDistance(0f);

            boolean apex = hop.tick > 1 && v.getY() <= 0.0;
            boolean timedOut = hop.tick >= riseMaxTicks;
            if (!apex && !timedOut) {
                return;
            }

            // Keep this exact Y; move XZ to above the path target
            double peakY = player.getLocation().getY();
            hop.phase = Phase.FALL;
            hop.tick = 0;

            Location peak = hop.land.clone();
            peak.setY(peakY);
            peak.setYaw(hop.land.getYaw());
            peak.setPitch(hop.land.getPitch());
            player.teleport(peak);
            player.setFallDistance(0f);
            player.setVelocity(new Vector(0, 0, 0));
            return;
        }

        // FALL — gravity only
        if (player.isOnGround() || hop.tick >= fallMaxTicks) {
            Location land = hop.land.clone();
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
            clearHopState(p, hop.savedWalkSpeed);
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

    private static void clearHopState(Player p, float walkSpeed) {
        p.setFallDistance(0f);
        p.setVelocity(new Vector(0, 0, 0));
        p.setWalkSpeed(Math.max(0f, Math.min(1f, walkSpeed)));
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
        final Location land;
        final Runnable onDone;
        final float savedWalkSpeed;
        Phase phase = Phase.RISE;
        int tick;
        BukkitTask task;

        Hop(UUID playerId, Location land, Runnable onDone, float savedWalkSpeed) {
            this.playerId = playerId;
            this.land = land;
            this.onDone = onDone;
            this.savedWalkSpeed = savedWalkSpeed;
        }
    }
}
