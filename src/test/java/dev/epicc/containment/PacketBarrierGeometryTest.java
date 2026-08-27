package dev.epicc.containment;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketBarrierGeometryTest {

    @Test
    void buildsSevenBySevenOuterRingCenteredOnThePathPoint() {
        List<PacketBarrierGeometry.BlockPosition> positions = PacketBarrierGeometry.build(
                List.of(new PacketBarrierGeometry.SlotCenter(10, -4, 64)),
                0.0,
                -64,
                320
        );

        assertEquals(24 * 7, positions.size());
        assertEquals(positions.size(), new HashSet<>(positions).size());
        assertTrue(positions.stream().allMatch(position -> position.y() >= 64 && position.y() <= 70));
        assertTrue(positions.stream().allMatch(position ->
                position.x() == 7 || position.x() == 13 || position.z() == -7 || position.z() == -1));
        assertFalse(positions.contains(new PacketBarrierGeometry.BlockPosition(10, 64, -4)));
        assertFalse(positions.contains(new PacketBarrierGeometry.BlockPosition(10, 64, -5)));
    }

    @Test
    void deduplicatesCornersAndOverlappingSlotWalls() {
        List<PacketBarrierGeometry.BlockPosition> oneWall = PacketBarrierGeometry.build(
                List.of(new PacketBarrierGeometry.SlotCenter(0, 0, 64)),
                0.0,
                -64,
                320
        );
        List<PacketBarrierGeometry.BlockPosition> overlappingWalls = PacketBarrierGeometry.build(
                List.of(
                        new PacketBarrierGeometry.SlotCenter(0, 0, 64),
                        new PacketBarrierGeometry.SlotCenter(2, 0, 64)
                ),
                0.0,
                -64,
                320
        );

        assertEquals(24 * 7, oneWall.size());
        assertEquals(oneWall.size(), new HashSet<>(oneWall).size());
        assertEquals(overlappingWalls.size(), new HashSet<>(overlappingWalls).size());
        assertTrue(overlappingWalls.size() < oneWall.size() * 2);
    }

    @Test
    void clearsTheMinimumHeightAndReachesAboveTheHopApex() {
        assertEquals(70, PacketBarrierGeometry.topY(64, 0.0));
        assertEquals(80, PacketBarrierGeometry.topY(64, 79.0));

        List<PacketBarrierGeometry.BlockPosition> positions = PacketBarrierGeometry.build(
                List.of(new PacketBarrierGeometry.SlotCenter(0, 0, 64)),
                79.0,
                -64,
                320
        );
        assertEquals(17, positions.stream().map(PacketBarrierGeometry.BlockPosition::y).distinct().count());
        assertTrue(positions.stream().anyMatch(position -> position.y() == 80));
    }

}
