package dev.epicc.minigame;

import org.bukkit.Material;

import java.util.List;

/** Parsed {@code minigame.spleef} config; values are already clamped by {@code PluginConfig}. */
public record SpleefSettings(
        int timeoutSeconds,
        double fallY,
        double spawnRadius,
        MinigameArenaSpec arena,
        List<Material> floorMaterials,
        SpleefPlatform platform,
        double knockback,
        int powerupSpawnSeconds,
        int powerupMaxActive,
        int creeperSpawnSeconds,
        int creeperMaxAlive,
        int creeperExplosionRadius,
        int creeperFuseTicks,
        int creeperIgniteSeconds
) {
    public boolean isValid() {
        return arena.isValid() && Double.isFinite(fallY) && fallY > arena.minY();
    }
}
