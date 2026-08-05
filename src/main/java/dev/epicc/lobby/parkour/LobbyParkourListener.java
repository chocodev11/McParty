package dev.epicc.lobby.parkour;

import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyManager;
import dev.epicc.party.PartyPlayArea;
import dev.epicc.party.PartyState;
import org.bukkit.Tag;
import org.bukkit.event.block.Action;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class LobbyParkourListener implements Listener {

    private final LobbyParkourService parkour;
    private final PartyManager parties;

    public LobbyParkourListener(LobbyParkourService parkour, PartyManager parties) {
        this.parkour = parkour;
        this.parties = parties;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isWaitingInLobby(player)) {
            if (parkour.isRunning(player.getUniqueId())) {
                parkour.leave(player);
            }
            return;
        }

        LobbyParkourDefinition definition = parkour.definition();
        if (!definition.isReady()) {
            return;
        }
        parkour.handleTrigger(player, event.getTo());
        if (!parkour.isRunning(player.getUniqueId())) {
            return;
        }
        parkour.handleSlimeLanding(player, event.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPressurePlate(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null
                || !Tag.PRESSURE_PLATES.isTagged(event.getClickedBlock().getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (!isWaitingInLobby(player)) {
            return;
        }
        LobbyParkourDefinition definition = parkour.definition();
        if (!definition.isReady()) {
            return;
        }
        if (!parkour.isRunning(player.getUniqueId()) && definition.start().matchesBlock(event.getClickedBlock().getLocation())) {
            parkour.start(player);
            return;
        }
        if (!parkour.isRunning(player.getUniqueId())) {
            return;
        }
        for (LobbyParkourPoint checkpoint : definition.checkpoints()) {
            if (checkpoint.matchesBlock(event.getClickedBlock().getLocation())) {
                parkour.updateCheckpoint(player, checkpoint);
                return;
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        if (!parkour.isRunning(player.getUniqueId())) {
            return;
        }
        String action = parkour.action(event.getItem());
        if (action == null) {
            return;
        }
        event.setCancelled(true);
        switch (action) {
            case LobbyParkourItems.RESTART -> parkour.restart(player);
            case LobbyParkourItems.CHECKPOINT -> parkour.teleportCheckpoint(player);
            case LobbyParkourItems.LEAVE -> parties.leaveLobbyParkour(player);
            default -> { }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !parkour.isRunning(player.getUniqueId())) {
            return;
        }
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && parkour.isRunning(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        parkour.leave(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (parkour.isRunning(event.getPlayer().getUniqueId()) && !isWaitingInLobby(event.getPlayer())) {
            parkour.leave(event.getPlayer());
        }
    }

    private boolean isWaitingInLobby(Player player) {
        PartyInstance instance = parties.instanceOf(player.getUniqueId()).orElse(null);
        if (instance == null) {
            return parkour.definition().fallbackWorld().equals(player.getWorld().getName());
        }
        if (instance.state() != PartyState.WAITING) {
            return false;
        }
        PartyPlayArea area = instance.activePlayArea();
        if (area != null) {
            return area.world().equals(player.getWorld());
        }
        return parkour.definition().fallbackWorld().equals(player.getWorld().getName());
    }
}
