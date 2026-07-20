package dev.epicc.containment;

import dev.epicc.board.BoardSlot;
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
    private final FakeWallService fakeWallService;

    public BoundaryListener(PartyManager partyManager, FakeWallService fakeWallService) {
        this.partyManager = partyManager;
        this.fakeWallService = fakeWallService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null) {
            return;
        }
        // ignore look-only
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("mcparty.admin.bypass")) {
            return;
        }

        PartyInstance instance = partyManager.instanceOf(player.getUniqueId()).orElse(null);
        if (instance == null || instance.slot() == null) {
            return;
        }
        if (instance.state() != PartyState.STARTING
                && instance.state() != PartyState.PLAYING
                && instance.state() != PartyState.ENDING) {
            return;
        }

        SlotBoundary boundary = instance.slot().boundary();
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
            BoardSlot slot = instance.slot();
            if (slot == null) {
                return;
            }
            Location spawn = slot.spawn();
            if (spawn != null) {
                event.setRespawnLocation(spawn);
            }
            player.getServer().getScheduler().runTaskLater(
                    partyManager.plugin(),
                    () -> fakeWallService.reapply(player, slot.boundary()),
                    1L
            );
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        fakeWallService.clear(event.getPlayer());
        partyManager.leave(event.getPlayer(), true);
    }
}
