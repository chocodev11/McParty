package dev.epicc.hologram;

import java.util.List;

public record HologramDefinition(
        String id,
        HologramLocation location,
        List<String> lines,
        List<HologramFrame> frames,
        HologramStyle style,
        int refreshTicks,
        String visibilityMode,
        String permission,
        String scope
) {

    public HologramDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (location == null) {
            throw new IllegalArgumentException("location is required");
        }
        lines = List.copyOf(lines == null ? List.of() : lines);
        frames = List.copyOf(frames == null ? List.of() : frames);
        style = style == null ? HologramStyle.defaults(32.0f) : style;
        refreshTicks = Math.max(1, refreshTicks);
        visibilityMode = visibilityMode == null || visibilityMode.isBlank()
                ? "all" : visibilityMode.toLowerCase(java.util.Locale.ROOT);
        permission = permission == null ? "" : permission;
        scope = scope == null || scope.isBlank() ? "global" : scope.toLowerCase(java.util.Locale.ROOT);
    }

    public List<String> activeLines(long tick) {
        if (frames.isEmpty()) {
            return lines;
        }
        long total = 0L;
        for (HologramFrame frame : frames) {
            total += frame.durationTicks();
        }
        long position = total == 0 ? 0 : Math.floorMod(tick, total);
        for (HologramFrame frame : frames) {
            if (position < frame.durationTicks()) {
                return frame.lines();
            }
            position -= frame.durationTicks();
        }
        return frames.getLast().lines();
    }
}
