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
        if (spaces.isEmpty()) {
            lastPrimaryKey = null;
            return;
        }
        PlacedSpace last = spaces.get(spaces.size() - 1);
        lastPrimaryKey = primaryKey(last.centerBlock());
    }

    private static String primaryKey(Location block) {
        return block.getWorld().getName() + ":" + block.getBlockX() + ":" + block.getBlockY() + ":" + block.getBlockZ();
    }
}
