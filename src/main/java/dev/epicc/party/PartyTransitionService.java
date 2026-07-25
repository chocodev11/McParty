package dev.epicc.party;

import dev.epicc.seamless.SeamlessWorldChangeService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Issues short-lived permits for plugin-owned cross-world teleports. */
public final class PartyTransitionService {
    private static final long PERMIT_MILLIS = 5_000L;

    private final SeamlessWorldChangeService seamless;
    private final Map<UUID, Permit> permits = new ConcurrentHashMap<>();

    public PartyTransitionService(JavaPlugin plugin, SeamlessWorldChangeService seamless) {
        this.seamless = seamless;
    }

    public void transition(Collection<Player> players, PartyPlayArea destination) {
        for (Player player : players) {
            permit(player, destination.spawn());
            seamless.teleport(player, destination.spawn());
        }
    }

    public void permit(Player player, Location destination) {
        permits.put(player.getUniqueId(), new Permit(destination.clone(), System.currentTimeMillis() + PERMIT_MILLIS));
    }

    public boolean consumeIfAllowed(Player player, Location destination) {
        Permit permit = permits.get(player.getUniqueId());
        if (permit == null || permit.expiresAt < System.currentTimeMillis()) {
            permits.remove(player.getUniqueId());
            return false;
        }
        if (destination.getWorld() != permit.destination.getWorld()
                || destination.distanceSquared(permit.destination) > 1.0D) return false;
        permits.remove(player.getUniqueId());
        return true;
    }

    public void clear(UUID playerId) { permits.remove(playerId); }

    private record Permit(Location destination, long expiresAt) {}
}
