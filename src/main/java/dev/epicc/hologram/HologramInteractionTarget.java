package dev.epicc.hologram;

import org.bukkit.Location;

import java.util.UUID;

/** The nearest packet hologram intersected by a player's view ray. */
public record HologramInteractionTarget(String id, UUID scopeId, Location location, double distance) {

    public HologramInteractionTarget {
        location = location == null ? null : location.clone();
    }
}
