package dev.epicc.containment;

import dev.epicc.board.PathHopMover;
import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyManager;
import dev.epicc.party.PartyState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class BoundaryListener implements Listener {

    private final PartyManager partyManager;
    private final PathHopMover pathHopMover;

    public BoundaryListener(PartyManager partyManager, PathHopMover pathHopMover) {
        this.partyManager = partyManager;
        this.pathHopMover = pathHopMover;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null) {
            return;
        }
        // ignore look-only
        if (from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("mcparty.admin.bypass")) {
            return;
        }
        // path hop rises/falls outside the floor plane intentionally
        if (pathHopMover.isHopping(player.getUniqueId())) {
            return;
        }

        PartyInstance instance = partyManager.instanceOf(player.getUniqueId()).orElse(null);
        if (instance == null || instance.activePlayArea() == null) {
            return;
        }
        // WAITING has a play area only once the lobby clone is loaded; CLEANUP is releasing worlds
        if (instance.state() == PartyState.CLEANUP) {
            return;
        }

        if (event instanceof PlayerTeleportEvent && partyManager.consumeTransitionPermit(player, to)) {
            return;
        }
        SlotBoundary boundary = instance.activePlayArea().boundary();
        if (boundary.isInside(to)) {
            return;
        }
        event.setTo(boundary.clampInside(from));
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        onMove(event);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        partyManager.instanceOf(player.getUniqueId()).ifPresent(instance -> {
            if (instance.activePlayArea() == null) {
                return;
            }
            Location spawn = instance.activePlayArea().spawn();
            if (spawn != null) {
                event.setRespawnLocation(spawn);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        partyManager.leave(event.getPlayer(), true);
    }
}
