package dev.epicc.hologram;

import net.kyori.adventure.text.Component;

@FunctionalInterface
public interface HologramPlaceholder {
    Component resolve(HologramViewerContext context);
}
