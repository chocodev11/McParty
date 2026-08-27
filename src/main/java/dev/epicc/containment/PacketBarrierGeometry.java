package dev.epicc.containment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PacketBarrierGeometry {

    static final int HALF_SIZE = 3;
    static final int MIN_HEIGHT = 7;

    private PacketBarrierGeometry() {
    }

    static List<BlockPosition> build(
            List<SlotCenter> centers,
            double highestHopY,
            int worldMinHeight,
            int worldMaxHeight
    ) {
        if (centers.isEmpty() || worldMinHeight >= worldMaxHeight) {
            return List.of();
        }

        Set<BlockPosition> positions = new LinkedHashSet<>();
        for (SlotCenter center : centers) {
            int bottomY = Math.max(center.baseY(), worldMinHeight);
            int topY = Math.min(topY(center.baseY(), highestHopY), worldMaxHeight - 1);
            if (bottomY > topY) {
                continue;
            }

            for (int y = bottomY; y <= topY; y++) {
                for (int offset = -HALF_SIZE; offset <= HALF_SIZE; offset++) {
                    positions.add(new BlockPosition(center.x() + offset, y, center.z() - HALF_SIZE));
                    positions.add(new BlockPosition(center.x() + offset, y, center.z() + HALF_SIZE));
                }
                for (int offset = -HALF_SIZE + 1; offset <= HALF_SIZE - 1; offset++) {
                    positions.add(new BlockPosition(center.x() - HALF_SIZE, y, center.z() + offset));
                    positions.add(new BlockPosition(center.x() + HALF_SIZE, y, center.z() + offset));
                }
            }
        }
        return List.copyOf(positions);
    }

    static int topY(int baseY, double highestHopY) {
        return Math.max(baseY + MIN_HEIGHT - 1, (int) Math.ceil(highestHopY) + 1);
    }

    record SlotCenter(int x, int z, int baseY) {
    }

    record BlockPosition(int x, int y, int z) {
    }
}
