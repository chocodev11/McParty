package dev.epicc.hologram;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HologramTemplateSet {

    private final List<CompiledHologramTemplate> lines;

    public HologramTemplateSet(List<String> rawLines) {
        lines = rawLines == null
                ? List.of()
                : rawLines.stream().map(CompiledHologramTemplate::compile).toList();
    }

    public List<Component> render(HologramViewerContext context, Map<String, HologramPlaceholder> resolvers) {
        List<Component> rendered = new ArrayList<>(lines.size());
        for (CompiledHologramTemplate line : lines) {
            rendered.add(line.render(context, resolvers));
        }
        return List.copyOf(rendered);
    }
}
