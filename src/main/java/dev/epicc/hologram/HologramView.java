package dev.epicc.hologram;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

public record HologramView(HologramDefinition definition, List<Component> lines) {
    public HologramView {
        lines = List.copyOf(lines);
    }

    public Component text() {
        if (lines.isEmpty()) {
            return Component.empty();
        }
        String rawColor = definition.style().color();
        TextColor color;
        try {
            color = TextColor.fromHexString(rawColor);
        } catch (IllegalArgumentException ignored) {
            color = null;
        }
        if (color == null) {
            color = NamedTextColor.NAMES.value(rawColor);
        }
        Component first = withDefaultColor(lines.getFirst(), color);
        Component result = first;
        for (int i = 1; i < lines.size(); i++) {
            Component line = withDefaultColor(lines.get(i), color);
            result = result.append(Component.newline()).append(line);
        }
        return result;
    }

    private static Component withDefaultColor(Component line, TextColor color) {
        return color == null || line.color() != null ? line : line.color(color);
    }
}
