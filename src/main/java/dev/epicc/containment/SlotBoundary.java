package dev.epicc.containment;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

public final class SlotBoundary {

    private final World world;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    public SlotBoundary(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public World world() { return world; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }

    public boolean isInside(Location loc) {
        if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(world)) {
            return false;
        }
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        // inclusive block bounds with full block interior
        return x >= minX && x < maxX + 1
                && y >= minY && y < maxY + 1
                && z >= minZ && z < maxZ + 1;
    }

    public Location clampInside(Location from) {
        if (from == null) {
            return null;
        }
        double x = Math.min(Math.max(from.getX(), minX + 0.5), maxX + 0.5);
        double y = Math.min(Math.max(from.getY(), minY), maxY);
        double z = Math.min(Math.max(from.getZ(), minZ + 0.5), maxZ + 0.5);
        Location clamped = new Location(world, x, y, z, from.getYaw(), from.getPitch());
        return clamped;
    }

    public Vector center() {
        return new Vector(
                (minX + maxX + 1) / 2.0,
                (minY + maxY + 1) / 2.0,
                (minZ + maxZ + 1) / 2.0
        );
    }

    /** Same bounds re-bound to another world (for slime clones). */
    public SlotBoundary forWorld(World world) {
        return new SlotBoundary(world, minX, minY, minZ, maxX, maxY, maxZ);
    }
}
