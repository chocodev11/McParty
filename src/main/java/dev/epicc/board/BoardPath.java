package dev.epicc.board;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BoardPath {

    private final List<Location> points = new ArrayList<>();

    public void add(Location location) {
        points.add(location.clone());
    }

    public void clear() {
        points.clear();
    }

    public List<Location> points() {
        return Collections.unmodifiableList(points);
    }

    public int size() {
        return points.size();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public Location get(int index) {
        if (points.isEmpty()) {
            return null;
        }
        int clamped = Math.max(0, Math.min(index, points.size() - 1));
        return points.get(clamped).clone();
    }

    /** Same path points re-bound to another world (for slime clones). */
    public BoardPath forWorld(World world) {
        BoardPath copy = new BoardPath();
        for (Location p : points) {
            copy.add(new Location(world, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch()));
        }
        return copy;
    }
}
