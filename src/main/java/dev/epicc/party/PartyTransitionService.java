package dev.epicc.party;

import dev.epicc.seamless.SeamlessWorldChangeService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Issues short-lived permits for plugin-owned cross-world teleports. */
public final class PartyTransitionService {
    private static final long PERMIT_MILLIS = 5_000L;

    private final SeamlessWorldChangeService seamless;
    private final Map<UUID, Permit> permits = new ConcurrentHashMap<>();

    public PartyTransitionService(SeamlessWorldChangeService seamless) {
        this.seamless = seamless;
    }

    public void transition(Collection<Player> players, PartyPlayArea destination) {
        transition(players, destination, false);
    }

    public void transitionSeamlessly(Collection<Player> players, PartyPlayArea destination) {
        transition(players, destination, true);
    }

    public void teleport(Player player, Location destination) {
        teleport(player, destination, false);
    }

    public void teleportSeamlessly(Player player, Location destination) {
        teleport(player, destination, true);
    }

    public void flushPendingTeleports() {
        seamless.flushPendingTeleports();
    }

    private void transition(Collection<Player> players, PartyPlayArea destination, boolean seamlessTransition) {
        for (Player player : players) {
            teleport(player, destination.spawn(), seamlessTransition);
        }
    }

    private void teleport(Player player, Location destination, boolean seamlessTransition) {
        permit(player, destination);
        Runnable clearPermit = () -> clear(player.getUniqueId());
        if (seamlessTransition) {
            seamless.teleport(player, destination, clearPermit);
        } else {
            player.teleport(destination);
            clearPermit.run();
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
