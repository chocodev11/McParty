package dev.epicc.minigame;

/** Template-relative spawn and mandatory containment box for an arena clone. */
public record MinigameArenaSpec(
        String template,
        double spawnX, double spawnY, double spawnZ, float spawnYaw, float spawnPitch,
        int minX, int minY, int minZ, int maxX, int maxY, int maxZ
) {
    public boolean isValid() {
        return template != null && !template.isBlank()
                && Double.isFinite(spawnX) && Double.isFinite(spawnY) && Double.isFinite(spawnZ)
                && minX <= maxX && minY <= maxY && minZ <= maxZ;
    }
}
