package dev.epicc.hologram;

public record HologramStyle(
        String billboard,
        String alignment,
        String color,
        float scale,
        int backgroundArgb,
        boolean defaultBackground,
        boolean shadowed,
        boolean seeThrough,
        byte textOpacity,
        int lineWidth,
        float viewRange,
        int brightnessBlock,
        int brightnessSky
) {

    public HologramStyle {
        billboard = normalize(billboard, "center");
        alignment = normalize(alignment, "center");
        color = color == null || color.isBlank() ? "#FFFFFF" : color;
        scale = !Float.isFinite(scale) || scale <= 0 ? 1.0f : scale;
        lineWidth = Math.max(1, lineWidth);
        viewRange = !Float.isFinite(viewRange) || viewRange <= 0 ? 32.0f : viewRange;
        brightnessBlock = clampLight(brightnessBlock);
        brightnessSky = clampLight(brightnessSky);
    }

    public static HologramStyle defaults(float viewRange) {
        return new HologramStyle(
                "center", "center", "#FFFFFF", 1.0f, 0x00000000, true,
                true, false, (byte) -1, 200, viewRange, -1, -1
        );
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static int clampLight(int value) {
        return value < -1 ? -1 : Math.min(value, 15);
    }
}
