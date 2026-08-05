package dev.epicc.hologram;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record HologramLocation(
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {

    public HologramLocation {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world is required");
        }
    }

    public Location resolve() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }

    public HologramLocation inWorld(String worldName) {
        return new HologramLocation(worldName, x, y, z, yaw, pitch);
    }

    public static HologramLocation from(Location location) {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("location world is required");
        }
        return new HologramLocation(
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch()
        );
    }
}
