package dev.epicc.containment;

import dev.epicc.board.BoardSlot;
import dev.epicc.board.PathHopMover;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.math.Position;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketBarrierService implements Listener {

    private static final String BYPASS_PERMISSION = "mcparty.admin.bypass";

    private final JavaPlugin plugin;
    private final BlockData barrierData = Material.BARRIER.createBlockData();
    private final Map<UUID, ActiveBarrier> activeBarriers = new ConcurrentHashMap<>();

    public PacketBarrierService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void show(Player player, BoardSlot slot) {
        if (player == null || slot == null) {
            return;
        }
        clear(player);
        if (!player.isOnline() || player.hasPermission(BYPASS_PERMISSION) || !slot.isReady()
                || slot.world() == null || !slot.world().equals(player.getWorld())) {
            return;
        }

        List<Location> path = slot.path().points();
        double maxPathY = path.stream()
                .mapToDouble(Location::getY)
                .max()
                .orElse(slot.boundary().maxY());
        List<PacketBarrierGeometry.SlotCenter> centers = path.stream()
                .map(point -> new PacketBarrierGeometry.SlotCenter(
                        point.getBlockX(), point.getBlockZ(), point.getBlockY() - 1
                ))
                .toList();
        List<PacketBarrierGeometry.BlockPosition> positions = PacketBarrierGeometry.build(
                centers,
                maxPathY + PathHopMover.TARGET_HEIGHT_OFFSET,
                slot.world().getMinHeight(),
                slot.world().getMaxHeight()
        );
        if (positions.isEmpty()) {
            return;
        }

        ActiveBarrier active = new ActiveBarrier(slot.world(), positions);
        activeBarriers.put(player.getUniqueId(), active);
        sendBarriers(player, active, null);
    }

    public void clear(Player player) {
        if (player != null) {
            clear(player.getUniqueId());
        }
    }

    public void clear(UUID playerId) {
        ActiveBarrier active = activeBarriers.remove(playerId);
        if (active == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline() || !active.world().equals(player.getWorld())) {
            return;
        }
        restoreActualBlocks(player, active, null);
    }

    public void clearAll() {
        for (UUID playerId : new ArrayList<>(activeBarriers.keySet())) {
            clear(playerId);
        }
        activeBarriers.clear();
    }

    @EventHandler
    public void onChunkLoad(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        ActiveBarrier active = activeBarriers.get(player.getUniqueId());
        if (active == null || !active.world().equals(event.getChunk().getWorld())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ActiveBarrier current = activeBarriers.get(player.getUniqueId());
            if (current == null || current != active || !player.isOnline()
                    || !active.world().equals(player.getWorld())) {
                return;
            }
            sendBarriers(player, active, event.getChunk());
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ActiveBarrier active = activeBarriers.get(player.getUniqueId());
            if (active == null || !player.isOnline() || !active.world().equals(player.getWorld())) {
                return;
            }
            sendBarriers(player, active, null);
        });
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        ActiveBarrier active = activeBarriers.get(playerId);
        if (active != null && !active.world().equals(event.getPlayer().getWorld())) {
            activeBarriers.remove(playerId, active);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        activeBarriers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        if (event.isCancelled()) {
            return;
        }
        for (Map.Entry<UUID, ActiveBarrier> entry : new ArrayList<>(activeBarriers.entrySet())) {
            ActiveBarrier active = entry.getValue();
            if (!active.world().equals(event.getWorld())) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline() && active.world().equals(player.getWorld())) {
                restoreActualBlocks(player, active, null);
            }
            activeBarriers.remove(entry.getKey(), active);
        }
    }

    private void sendBarriers(Player player, ActiveBarrier active, Chunk onlyChunk) {
        if (!player.isOnline() || !active.world().equals(player.getWorld())) {
            return;
        }
        Map<Position, BlockData> changes = new LinkedHashMap<>();
        for (PacketBarrierGeometry.BlockPosition position : active.positions()) {
            if (!belongsTo(position, onlyChunk) || !isLoaded(active.world(), position)) {
                continue;
            }
            Block block = active.world().getBlockAt(position.x(), position.y(), position.z());
            if (block.getType().isAir()) {
                changes.put(Position.block(position.x(), position.y(), position.z()), barrierData);
            }
        }
        if (!changes.isEmpty()) {
            player.sendMultiBlockChange(changes);
        }
    }

    private void restoreActualBlocks(Player player, ActiveBarrier active, Chunk onlyChunk) {
        Map<Position, BlockData> changes = new LinkedHashMap<>();
        for (PacketBarrierGeometry.BlockPosition position : active.positions()) {
            if (!belongsTo(position, onlyChunk) || !isLoaded(active.world(), position)) {
                continue;
            }
            Block block = active.world().getBlockAt(position.x(), position.y(), position.z());
            changes.put(Position.block(position.x(), position.y(), position.z()), block.getBlockData());
        }
        if (!changes.isEmpty()) {
            player.sendMultiBlockChange(changes);
        }
    }

    private static boolean belongsTo(PacketBarrierGeometry.BlockPosition position, Chunk chunk) {
        return chunk == null
                || (position.x() >> 4) == chunk.getX() && (position.z() >> 4) == chunk.getZ();
    }

    private static boolean isLoaded(World world, PacketBarrierGeometry.BlockPosition position) {
        return world.isChunkLoaded(position.x() >> 4, position.z() >> 4);
    }

    private record ActiveBarrier(
            World world,
            List<PacketBarrierGeometry.BlockPosition> positions
    ) {
    }
}
