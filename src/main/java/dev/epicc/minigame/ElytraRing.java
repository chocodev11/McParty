package dev.epicc.minigame;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Optional;

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

    private static final double CENTER_RADIUS_RATIO = 0.35;
    private static final double EPSILON = 1.0e-6;

    public record Collision(Location point, double segmentProgress) {
    }

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

    public Vector normalVector() {
        return new Vector(normalX, normalY, normalZ).normalize();
    }

    /**
     * Finds where a movement segment crosses this ring's plane and checks the opening at that
     * exact point. The returned progress makes it possible to validate multiple rings in the
     * order they are crossed during one movement event.
     */
    public Optional<Collision> collision(Location from, Location to, double hitboxPadding) {
        if (from == null || to == null || from.getWorld() != to.getWorld() || !isValid()) {
            return Optional.empty();
        }

        Vector normal = normalVector();
        Vector fromOffset = new Vector(from.getX() - x, from.getY() - y, from.getZ() - z);
        Vector toOffset = new Vector(to.getX() - x, to.getY() - y, to.getZ() - z);
        double fromPlaneDistance = fromOffset.dot(normal);
        double toPlaneDistance = toOffset.dot(normal);
        boolean bothPositive = fromPlaneDistance > EPSILON && toPlaneDistance > EPSILON;
        boolean bothNegative = fromPlaneDistance < -EPSILON && toPlaneDistance < -EPSILON;
        if (bothPositive || bothNegative
                || (Math.abs(fromPlaneDistance) <= EPSILON && Math.abs(toPlaneDistance) <= EPSILON)) {
            return Optional.empty();
        }

        double denominator = fromPlaneDistance - toPlaneDistance;
        if (Math.abs(denominator) <= EPSILON) {
            return Optional.empty();
        }
        double segmentProgress = fromPlaneDistance / denominator;
        if (segmentProgress < -EPSILON || segmentProgress > 1.0 + EPSILON) {
            return Optional.empty();
        }
        segmentProgress = Math.max(0.0, Math.min(1.0, segmentProgress));

        Vector pointOffset = fromOffset.clone().add(toOffset.clone().subtract(fromOffset).multiply(segmentProgress));
        Vector onPlane = pointOffset.clone().subtract(normal.clone().multiply(pointOffset.dot(normal)));
        double allowedRadius = radius + Math.max(0.0, hitboxPadding);
        if (onPlane.lengthSquared() > allowedRadius * allowedRadius) {
            return Optional.empty();
        }

        Location point = from.clone().add(to.toVector().subtract(from.toVector()).multiply(segmentProgress));
        return Optional.of(new Collision(point, segmentProgress));
    }

    public boolean centerHit(Location point) {
        if (point == null || !isValid()) {
            return false;
        }
        Vector normal = normalVector();
        Vector offset = new Vector(point.getX() - x, point.getY() - y, point.getZ() - z);
        Vector onPlane = offset.subtract(normal.multiply(offset.dot(normal)));
        double centerRadius = radius * CENTER_RADIUS_RATIO;
        return onPlane.lengthSquared() <= centerRadius * centerRadius;
    }

}
