package dev.epicc.minigame;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/** Template-relative ring center, plane normal, and opening radius. */
public record ElytraRing(
        double x,
        double y,
        double z,
        double radius,
        double normalX,
        double normalY,
        double normalZ
) {

    private static final double MAX_PLANE_DISTANCE = 1.25;
    private static final double CENTER_RADIUS_RATIO = 0.35;

    public static ElytraRing from(Location center, double radius) {
        Vector normal = center.getDirection().normalize();
        if (normal.lengthSquared() < 0.0001) {
            normal = new Vector(0.0, 0.0, 1.0);
        }
        return new ElytraRing(
                center.getX(), center.getY(), center.getZ(), radius,
                normal.getX(), normal.getY(), normal.getZ()
        );
    }

    public boolean isValid() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Double.isFinite(radius) && radius > 0.0
                && Double.isFinite(normalX) && Double.isFinite(normalY) && Double.isFinite(normalZ)
                && normalX * normalX + normalY * normalY + normalZ * normalZ > 0.0001;
    }

    /**
     * Checks the ring opening rather than just a sphere around its center. The small plane
     * tolerance keeps normal player hitboxes from missing a ring at server tick boundaries.
     */
    public boolean contains(Location location) {
        if (location == null || !isValid()) {
            return false;
        }
        Vector offset = new Vector(location.getX() - x, location.getY() - y, location.getZ() - z);
        Vector normal = new Vector(normalX, normalY, normalZ).normalize();
        double planeDistance = offset.dot(normal);
        if (Math.abs(planeDistance) > MAX_PLANE_DISTANCE) {
            return false;
        }
        double radialSquared = Math.max(0.0, offset.lengthSquared() - planeDistance * planeDistance);
        return radialSquared <= radius * radius;
    }

    public boolean centerHit(Location location) {
        if (!contains(location)) {
            return false;
        }
        double dx = location.getX() - x;
        double dy = location.getY() - y;
        double dz = location.getZ() - z;
        double centerRadius = radius * CENTER_RADIUS_RATIO;
        return dx * dx + dy * dy + dz * dz <= centerRadius * centerRadius;
    }

}
