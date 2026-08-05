package dev.epicc.hologram;

import org.bukkit.entity.Player;

public record HologramViewerContext(Player player, HologramDefinition hologram, long tick) {
}
