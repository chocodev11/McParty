package dev.epicc.board.setup;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PathSetupSession {

    private final UUID playerId;
    private final String name;
    private final World world;
    /** First stick hit: player spawn center (no pad). Null until set. */
    private Location spawn;
    private final List<PlacedSpace> spaces = new ArrayList<>();
    private String lastPrimaryKey;

    PathSetupSession(UUID playerId, String name, World world) {
        this.playerId = playerId;
        this.name = name;
        this.world = world;
    }

    UUID playerId() {
        return playerId;
    }

    String name() {
        return name;
    }

    World world() {
        return world;
    }

    Location spawn() {
        return spawn != null ? spawn.clone() : null;
    }

    void setSpawn(Location spawn) {
        this.spawn = spawn != null ? spawn.clone() : null;
    }

    boolean hasSpawn() {
        return spawn != null;
    }

    List<PlacedSpace> spaces() {
        return spaces;
    }

    boolean isDuplicatePrimary(Location block) {
        String key = primaryKey(block);
        return key.equals(lastPrimaryKey);
    }

    void markPrimary(Location block) {
        lastPrimaryKey = primaryKey(block);
    }

    void clearLastPrimary() {
        if (!spaces.isEmpty()) {
            PlacedSpace last = spaces.get(spaces.size() - 1);
            lastPrimaryKey = primaryKey(last.centerBlock());
            return;
        }
        if (spawn != null) {
            // Approximate spawn primary from feet position (block below feet center)
            lastPrimaryKey = primaryKey(new Location(
                    spawn.getWorld(),
                    Math.floor(spawn.getX()),
                    Math.floor(spawn.getY()) - 1,
                    Math.floor(spawn.getZ())
            ));
            return;
        }
        lastPrimaryKey = null;
    }

    private static String primaryKey(Location block) {
        return block.getWorld().getName() + ":" + block.getBlockX() + ":" + block.getBlockY() + ":" + block.getBlockZ();
    }
}
