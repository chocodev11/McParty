package dev.epicc.containment;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FakeWallService {

    private final Material wallMaterial;
    private final int wallHeight;
    private final Map<UUID, List<Location>> activeShells = new HashMap<>();

    public FakeWallService(Material wallMaterial, int wallHeight) {
        this.wallMaterial = wallMaterial;
        this.wallHeight = Math.max(1, wallHeight);
    }

    public void apply(Player player, SlotBoundary boundary) {
        clear(player);
        List<Location> shell = buildShell(boundary);
        BlockData data = wallMaterial.createBlockData();
        for (Location loc : shell) {
            player.sendBlockChange(loc, data);
        }
        activeShells.put(player.getUniqueId(), shell);
    }

    public void clear(Player player) {
        List<Location> shell = activeShells.remove(player.getUniqueId());
        if (shell == null) {
            return;
        }
        World world = player.getWorld();
        for (Location loc : shell) {
            if (loc.getWorld() == null) {
                loc.setWorld(world);
            }
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
        }
    }

    public void clearAll(Iterable<? extends Player> players) {
        for (Player player : players) {
            clear(player);
        }
    }

    public void reapply(Player player, SlotBoundary boundary) {
        apply(player, boundary);
    }

    private List<Location> buildShell(SlotBoundary b) {
        List<Location> shell = new ArrayList<>();
        World world = b.world();
        int minX = b.minX();
        int maxX = b.maxX();
        int minZ = b.minZ();
        int maxZ = b.maxZ();
        int minY = b.minY();
        int maxY = Math.min(b.maxY(), minY + wallHeight - 1);

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                shell.add(new Location(world, x, y, minZ));
                if (maxZ != minZ) {
                    shell.add(new Location(world, x, y, maxZ));
                }
            }
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                shell.add(new Location(world, minX, y, z));
                if (maxX != minX) {
                    shell.add(new Location(world, maxX, y, z));
                }
            }
        }
        return shell;
    }
}
