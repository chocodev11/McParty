package dev.epicc.lobby.parkour;

import org.bukkit.Location;

/** A block beneath a player's feet, stored relative to every lobby clone. */
public record LobbyParkourPoint(int x, int y, int z) {

    public static LobbyParkourPoint beneath(Location location) {
        return new LobbyParkourPoint(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ());
    }

    public boolean matchesBlockBelow(Location location) {
        return location.getBlockX() == x && location.getBlockY() - 1 == y && location.getBlockZ() == z;
    }

    public Location teleportLocation(Location source) {
        return new Location(source.getWorld(), x + 0.5, y + 1.0, z + 0.5, source.getYaw(), source.getPitch());
    }
}
