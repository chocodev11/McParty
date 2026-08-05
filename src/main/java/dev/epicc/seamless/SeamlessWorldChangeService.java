package dev.epicc.seamless;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Suppresses the client "Loading terrain…" screen on same-environment world changes
 * by cancelling the outbound RESPAWN packet (PacketEvents).
 * <p>
 * Opt-in only: call {@link #teleport(Player, Location)} or {@link #markIfCompatible}
 * right before a world change McParty owns. One-shot mark; fail-open if PE is missing.
 */
public final class SeamlessWorldChangeService {

    private static final int MARK_TIMEOUT_TICKS = 5;

    private final JavaPlugin plugin;
    private final Map<UUID, Long> marked = new ConcurrentHashMap<>();
    private final boolean active;

    public SeamlessWorldChangeService(JavaPlugin plugin, boolean enabled) {
        this.plugin = plugin;
        this.active = enabled && tryRegisterPacketListener();
        if (enabled && !active) {
            plugin.getLogger().warning(
                    "seamless-world-change enabled but PacketEvents is missing or failed to hook — dirt screen stays"
            );
        } else if (active) {
            plugin.getLogger().info("Seamless world-change active (PacketEvents RESPAWN cancel)");
        }
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Teleport with seamless same-env world change when possible.
     */
    public void teleport(Player player, Location to) {
        if (player == null || to == null) {
            return;
        }
        if (active) {
            markIfCompatible(player, player.getWorld(), to.getWorld());
        }
        player.teleport(to);
    }

    /**
     * Mark the next RESPAWN for this player if from/to are compatible.
     * Safe to call even when inactive (no-op).
     */
    public void markIfCompatible(Player player, World from, World to) {
        if (!active || player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        if (!canSeamless(from, to)) {
            return;
        }
        mark(player.getUniqueId());
    }

    public void mark(UUID playerId) {
        if (!active || !plugin.isEnabled() || playerId == null) {
            return;
        }
        long token = System.nanoTime();
        marked.put(playerId, token);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Long current = marked.get(playerId);
            if (current != null && current == token) {
                marked.remove(playerId, token);
            }
        }, MARK_TIMEOUT_TICKS);
    }

    /**
     * One-shot: true if this player was marked (and mark is consumed).
     */
    public boolean consumeMark(UUID playerId) {
        return playerId != null && marked.remove(playerId) != null;
    }

    public static boolean canSeamless(World from, World to) {
        if (from == null || to == null) {
            return false;
        }
        if (from.equals(to)) {
            return false;
        }
        if (from.getEnvironment() != to.getEnvironment()) {
            return false;
        }
        // Different vertical ranges desync client placement without a real dimension reset
        return from.getMinHeight() == to.getMinHeight()
                && from.getMaxHeight() == to.getMaxHeight();
    }

    private boolean tryRegisterPacketListener() {
        Plugin pe = Bukkit.getPluginManager().getPlugin("packetevents");
        if (pe == null || !pe.isEnabled()) {
            return false;
        }
        try {
            SeamlessRespawnListener.register(this);
            return true;
        } catch (NoClassDefFoundError | Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not register PacketEvents seamless listener", e);
            return false;
        }
    }
}
