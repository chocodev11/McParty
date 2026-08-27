package dev.epicc.party;

import dev.epicc.seamless.SeamlessWorldChangeService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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

    public void transition(Collection<Player> players, PartyPlayArea destination, Consumer<Player> afterTeleport) {
        transition(players, destination, false, afterTeleport);
    }

    public void transitionSeamlessly(Collection<Player> players, PartyPlayArea destination) {
        transition(players, destination, true);
    }

    public void transitionSeamlessly(
            Collection<Player> players,
            PartyPlayArea destination,
            Consumer<Player> afterTeleport
    ) {
        transition(players, destination, true, afterTeleport);
    }

    public void teleport(Player player, Location destination) {
        teleport(player, destination, false);
    }

    public void teleport(Player player, Location destination, Consumer<Player> afterTeleport) {
        teleport(player, destination, false, afterTeleport);
    }

    public void teleportSeamlessly(Player player, Location destination) {
        teleport(player, destination, true);
    }

    public void teleportSeamlessly(Player player, Location destination, Consumer<Player> afterTeleport) {
        teleport(player, destination, true, afterTeleport);
    }

    public void flushPendingTeleports() {
        seamless.flushPendingTeleports();
    }

    private void transition(Collection<Player> players, PartyPlayArea destination, boolean seamlessTransition) {
        transition(players, destination, seamlessTransition, null);
    }

    private void transition(
            Collection<Player> players,
            PartyPlayArea destination,
            boolean seamlessTransition,
            Consumer<Player> afterTeleport
    ) {
        for (Player player : players) {
            teleport(player, destination.spawn(), seamlessTransition, afterTeleport);
        }
    }

    private void teleport(Player player, Location destination, boolean seamlessTransition) {
        teleport(player, destination, seamlessTransition, null);
    }

    private void teleport(
            Player player,
            Location destination,
            boolean seamlessTransition,
            Consumer<Player> afterTeleport
    ) {
        permit(player, destination);
        Runnable onArrive = () -> {
            clear(player.getUniqueId());
            if (afterTeleport != null && player.isOnline()) {
                afterTeleport.accept(player);
            }
        };
        if (seamlessTransition) {
            seamless.teleport(player, destination, onArrive);
        } else {
            player.teleport(destination);
            onArrive.run();
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
