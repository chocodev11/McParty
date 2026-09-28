package dev.epicc.minigame;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Stacked round floors baked once into the Spleef arena template. */
public record SpleefPlatform(
        int centerX, int centerZ, int radius, int bottomY, int layerGap, int layers, Material material
) {
    public int topY() {
        return bottomY + layerGap * (layers - 1);
    }

    /** Layers must fit inside the arena box with head room above the top one. */
    public boolean fits(MinigameArenaSpec arena) {
        return radius > 0 && layers > 0 && layerGap > 2 && material.isBlock()
                && centerX - radius >= arena.minX() && centerX + radius <= arena.maxX()
                && centerZ - radius >= arena.minZ() && centerZ + radius <= arena.maxZ()
                && bottomY > arena.minY() && topY() + 2 <= arena.maxY();
    }

    /** Main thread. Writes without physics; the template is saved right after. */
    public void paint(World world) {
        BlockData data = material.createBlockData();
        // +radius rounds off the single-block nubs at the four compass points
        int limit = radius * radius + radius;
        for (int layer = 0; layer < layers; layer++) {
            int y = bottomY + layer * layerGap;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= limit) {
                        world.getBlockAt(centerX + dx, y, centerZ + dz).setBlockData(data, false);
                    }
                }
            }
        }
    }
}
