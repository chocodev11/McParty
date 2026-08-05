package dev.epicc.hologram;

import java.util.List;

public record HologramFrame(long durationTicks, List<String> lines) {

    public HologramFrame {
        durationTicks = Math.max(1L, durationTicks);
        lines = List.copyOf(lines == null ? List.of() : lines);
    }
}
