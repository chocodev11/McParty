package dev.epicc.board;

import dev.epicc.containment.SlotBoundary;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

public final class BoardSlot {

    private final String id;
    private final World world;
    /** ASP FileLoader world name (basename of {@code <name>.slime}), not the Bukkit setup world. */
    private String slimeTemplate;
    private final SlotBoundary boundary;
    private final BoardPath path;
    private Location spawn;
    private final BoardSlotLease lease = new BoardSlotLease();

    public BoardSlot(
            String id,
            World world,
            String slimeTemplate,
            SlotBoundary boundary,
            BoardPath path,
            Location spawn
    ) {
        this.id = id;
        this.world = world;
        this.slimeTemplate = slimeTemplate != null ? slimeTemplate : "";
        this.boundary = boundary;
        this.path = path;
        this.spawn = spawn != null ? spawn.clone() : null;
    }

    public String id() { return id; }
    public World world() { return world; }
    public String slimeTemplate() { return slimeTemplate; }
    public SlotBoundary boundary() { return boundary; }
    public BoardPath path() { return path; }
    public Location spawn() { return spawn != null ? spawn.clone() : null; }
    public UUID claimedBy() { return lease.holder(); }

    public void setSpawn(Location spawn) {
        this.spawn = spawn != null ? spawn.clone() : null;
    }

    public void setSlimeTemplate(String slimeTemplate) {
        this.slimeTemplate = slimeTemplate != null ? slimeTemplate.trim() : "";
    }

    public boolean isFree() {
        return lease.isFree();
    }

    public boolean claim(UUID instanceId) {
        return lease.acquire(instanceId, true);
    }

    public void release() {
        lease.clear();
    }

    public boolean isReady() {
        return spawn != null && !path.isEmpty() && boundary != null;
    }

    /**
     * Runtime copy bound to a loaded slime clone world, keeping template coords.
     * Claim state is not copied — caller claims the template slot separately.
     */
    public BoardSlot forWorld(World target) {
        Location newSpawn = null;
        if (spawn != null) {
            newSpawn = new Location(
                    target,
                    spawn.getX(),
                    spawn.getY(),
                    spawn.getZ(),
                    spawn.getYaw(),
                    spawn.getPitch()
            );
        }
        return new BoardSlot(
                id,
                target,
                slimeTemplate,
                boundary != null ? boundary.forWorld(target) : null,
                path.forWorld(target),
                newSpawn
        );
    }
}
