package dev.epicc.seamless;

import dev.epicc.resourcepack.FontImageService;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Suppresses the client "Loading terrain…" screen on same-environment world
 * changes
 * by cancelling the outbound RESPAWN packet (PacketEvents).
 * <p>
 * Opt-in only: call {@link #teleport(Player, Location)} for a world change
 * McParty owns.
 * Compatible transitions show the resource-pack overlay before the one-shot
 * mark is sent.
 */
public final class SeamlessWorldChangeService {

    /** Ticks needed to reach full opacity before the world change. */
    public static final long TELEPORT_DELAY_TICKS = 21L;
    /** Total title transition time: fade-in followed immediately by fade-out. */
    public static final long TRANSITION_DURATION_TICKS = 40L;

    private static final int MARK_TIMEOUT_TICKS = 5;
    private static final Duration FADE_IN = Duration.ofSeconds(1);
    private static final Duration HOLD = Duration.ofMillis(250);
    private static final Duration FADE_OUT = Duration.ofSeconds(1);

    private final JavaPlugin plugin;
    private final FontImageService fontImages;
    private final Map<UUID, Long> marked = new ConcurrentHashMap<>();
    private final Map<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();
    private final boolean active;

    public SeamlessWorldChangeService(JavaPlugin plugin, boolean enabled, FontImageService fontImages) {
        this.plugin = plugin;
        this.fontImages = fontImages;
        this.active = enabled && tryRegisterPacketListener();
        if (enabled && !active) {
            plugin.getLogger().warning(
                    "seamless-world-change enabled but PacketEvents is missing or failed to hook — dirt screen stays");
        } else if (active) {
            plugin.getLogger().info("Seamless world-change active (PacketEvents RESPAWN cancel)");
        }
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Show the transition overlay, then teleport with a seamless same-env world
     * change when possible.
     */
    public void teleport(Player player, Location to) {
        teleport(player, to, () -> {
        });
    }

    public void teleport(Player player, Location to, Runnable afterTeleport) {
        if (player == null || to == null) {
            return;
        }

        Location destination = to.clone();
        if (!active || !canSeamless(player.getWorld(), destination.getWorld())) {
            cancelPendingTeleport(player);
            player.teleport(destination);
            afterTeleport.run();
            return;
        }

        UUID playerId = player.getUniqueId();
        long token = System.nanoTime();
        PendingTeleport pending = new PendingTeleport(token, destination, afterTeleport);
        pendingTeleports.put(playerId, pending);
        player.showTitle(transitionTitle());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            PendingTeleport current = pendingTeleports.get(playerId);
            if (current == null || current.token() != token) {
                return;
            }
            pendingTeleports.remove(playerId, current);
            if (!player.isOnline()) {
                current.afterTeleport().run();
                return;
            }

            markIfCompatible(player, player.getWorld(), current.destination().getWorld());
            player.teleport(current.destination());
            current.afterTeleport().run();
        }, TELEPORT_DELAY_TICKS);
    }

    /** Finish delayed overlays before the plugin unloads any worlds. */
    public void flushPendingTeleports() {
        for (Map.Entry<UUID, PendingTeleport> entry : pendingTeleports.entrySet()) {
            UUID playerId = entry.getKey();
            PendingTeleport pending = entry.getValue();
            if (!pendingTeleports.remove(playerId, pending)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.clearTitle();
                player.teleport(pending.destination());
            }
            pending.afterTeleport().run();
        }
        marked.clear();
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

    private Title transitionTitle() {
        return Title.title(
                fontImages.image("background"),
                fontImages.image("logo"),
                Title.Times.times(FADE_IN, HOLD, FADE_OUT));
    }

    private void cancelPendingTeleport(Player player) {
        if (pendingTeleports.remove(player.getUniqueId()) != null) {
            player.clearTitle();
        }
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
        // Different vertical ranges desync client placement without a real dimension
        // reset
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

    private record PendingTeleport(long token, Location destination, Runnable afterTeleport) {
    }
}
