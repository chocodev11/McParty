package dev.epicc.minigame;

import org.bukkit.Location;

import java.util.List;

/** Persistent, template-relative definition for one Elytra race map. */
public record ElytraCourse(
        String id,
        String setupWorldName,
        MinigameArenaSpec arenaSpec,
        List<ElytraRing> rings
) {

    public ElytraCourse {
        id = id == null ? "" : id.trim().toLowerCase();
        setupWorldName = setupWorldName == null ? "" : setupWorldName.trim();
        rings = rings == null ? List.of() : List.copyOf(rings);
    }

    public static ElytraCourse empty(String id, String setupWorldName, String template) {
        return new ElytraCourse(
                id,
                setupWorldName,
                new MinigameArenaSpec(
                        template,
                        Double.NaN, Double.NaN, Double.NaN, 0.0f, 0.0f,
                        Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE
                ),
                List.of()
        );
    }

    public boolean isReady() {
        return arenaSpec != null
                && arenaSpec.isValid()
                && inside(arenaSpec.spawnX(), arenaSpec.spawnY(), arenaSpec.spawnZ())
                && arenaSpec.spawnY() > arenaSpec.minY()
                && rings.size() >= 2
                && rings.stream().allMatch(ring -> ring.isValid()
                && inside(ring.x(), ring.y(), ring.z()));
    }

    public ElytraCourse withSpawn(Location spawn) {
        MinigameArenaSpec old = arenaSpec;
        MinigameArenaSpec updated = new MinigameArenaSpec(
                old.template(),
                spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch(),
                old.minX(), old.minY(), old.minZ(), old.maxX(), old.maxY(), old.maxZ()
        );
        return new ElytraCourse(id, setupWorldName, updated, rings);
    }

    public ElytraCourse withBoundary(Location first, Location second) {
        MinigameArenaSpec old = arenaSpec;
        MinigameArenaSpec updated = new MinigameArenaSpec(
                old.template(),
                old.spawnX(), old.spawnY(), old.spawnZ(), old.spawnYaw(), old.spawnPitch(),
                first.getBlockX(), first.getBlockY(), first.getBlockZ(),
                second.getBlockX(), second.getBlockY(), second.getBlockZ()
        );
        return new ElytraCourse(id, setupWorldName, updated, rings);
    }

    public ElytraCourse withRing(Location center, double radius) {
        List<ElytraRing> updated = new java.util.ArrayList<>(rings);
        updated.add(ElytraRing.from(center, radius));
        return new ElytraCourse(id, setupWorldName, arenaSpec, updated);
    }

    public ElytraCourse withoutLastRing() {
        if (rings.isEmpty()) {
            return this;
        }
        return new ElytraCourse(id, setupWorldName, arenaSpec, rings.subList(0, rings.size() - 1));
    }

    private boolean inside(double x, double y, double z) {
        return x >= arenaSpec.minX() && x <= arenaSpec.maxX() + 1.0
                && y >= arenaSpec.minY() && y <= arenaSpec.maxY() + 1.0
                && z >= arenaSpec.minZ() && z <= arenaSpec.maxZ() + 1.0;
    }
}
