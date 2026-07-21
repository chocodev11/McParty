package dev.epicc.board.setup;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;

final class PlacedSpace {

    private final Location pathPoint;
    private final Location centerBlock;
    private final int minX, maxX, minZ, maxZ, y;
    private final List<BlockSnapshot> snapshots;

    PlacedSpace(Location pathPoint, Location centerBlock, int minX, int maxX, int minZ, int maxZ, int y,
                List<BlockSnapshot> snapshots) {
        this.pathPoint = pathPoint.clone();
        this.centerBlock = centerBlock.clone();
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.y = y;
        this.snapshots = snapshots;
    }

    Location pathPoint() {
        return pathPoint.clone();
    }

    Location centerBlock() {
        return centerBlock.clone();
    }

    int minX() {
        return minX;
    }

    int maxX() {
        return maxX;
    }

    int minZ() {
        return minZ;
    }

    int maxZ() {
        return maxZ;
    }

    int y() {
        return y;
    }

    void restore() {
        for (BlockSnapshot snap : snapshots) {
            snap.restore();
        }
    }

    record BlockSnapshot(World world, int x, int y, int z, BlockData data) {
        static BlockSnapshot capture(Block block) {
            return new BlockSnapshot(
                    block.getWorld(),
                    block.getX(),
                    block.getY(),
                    block.getZ(),
                    block.getBlockData().clone()
            );
        }

        void restore() {
            world.getBlockAt(x, y, z).setBlockData(data, false);
        }
    }
}
