package dev.epicc.minigame;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ElytraRingTest {

    @Test
    void crossingTheCenterReturnsIntersectionAndCenterBonusPoint() {
        ElytraRing ring = ring(2.0);

        Optional<ElytraRing.Collision> collision = ring.collision(point(0.0, 0.0, -4.0), point(0.0, 0.0, 4.0), 0.0);

        assertTrue(collision.isPresent());
        assertEquals(0.5, collision.orElseThrow().segmentProgress(), 1.0e-9);
        assertEquals(0.0, collision.orElseThrow().point().getX(), 1.0e-9);
        assertEquals(0.0, collision.orElseThrow().point().getZ(), 1.0e-9);
        assertTrue(ring.centerHit(collision.orElseThrow().point()));
    }

    @Test
    void crossingTheEdgeCountsButDoesNotAwardCenterBonus() {
        ElytraRing ring = ring(2.0);

        Optional<ElytraRing.Collision> collision = ring.collision(point(2.0, 0.0, -2.0), point(2.0, 0.0, 2.0), 0.0);

        assertTrue(collision.isPresent());
        assertFalse(ring.centerHit(collision.orElseThrow().point()));
    }

    @Test
    void movementNearTheRingWithoutCrossingItsPlaneDoesNotCount() {
        ElytraRing ring = ring(2.0);

        assertTrue(ring.collision(point(0.0, 0.0, 1.0), point(0.0, 0.0, 3.0), 0.0).isEmpty());
    }

    @Test
    void crossingOutsideTheOpeningDoesNotCount() {
        ElytraRing ring = ring(2.0);

        assertTrue(ring.collision(point(2.1, 0.0, -1.0), point(2.1, 0.0, 1.0), 0.0).isEmpty());
    }

    @Test
    void fastMovementStillReturnsTheCrossingPoint() {
        ElytraRing ring = ring(2.0);

        Optional<ElytraRing.Collision> collision = ring.collision(point(0.0, 0.0, -100.0), point(0.0, 0.0, 100.0), 0.0);

        assertTrue(collision.isPresent());
        assertEquals(0.5, collision.orElseThrow().segmentProgress(), 1.0e-9);
    }

    @Test
    void crossingProgressAllowsSequentialRingsInOneMovementEvent() {
        ElytraRing first = ringAt(0.0, 2.0);
        ElytraRing second = ringAt(10.0, 2.0);
        Location from = point(0.0, 0.0, -1.0);
        Location to = point(0.0, 0.0, 11.0);

        ElytraRing.Collision firstCollision = first.collision(from, to, 0.0).orElseThrow();
        ElytraRing.Collision secondCollision = second.collision(from, to, 0.0).orElseThrow();

        assertTrue(firstCollision.segmentProgress() < secondCollision.segmentProgress());
        assertEquals(0.0, firstCollision.point().getZ(), 1.0e-9);
        assertEquals(10.0, secondCollision.point().getZ(), 1.0e-9);
    }

    @Test
    void hitboxPaddingExtendsOnlyTheOpeningRadius() {
        ElytraRing ring = ring(2.0);

        assertTrue(ring.collision(point(2.25, 0.0, -1.0), point(2.25, 0.0, 1.0), 0.3).isPresent());
        assertTrue(ring.collision(point(2.25, 0.0, -1.0), point(2.25, 0.0, 1.0), 0.2).isEmpty());
    }

    private static ElytraRing ring(double radius) {
        return ringAt(0.0, radius);
    }

    private static ElytraRing ringAt(double z, double radius) {
        return new ElytraRing(0.0, 0.0, z, radius, 0.0, 0.0, 1.0);
    }

    private static Location point(double x, double y, double z) {
        return new Location(null, x, y, z);
    }
}
