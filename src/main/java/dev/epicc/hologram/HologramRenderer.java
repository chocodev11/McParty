package dev.epicc.hologram;

import org.bukkit.entity.Player;

import java.util.UUID;

interface HologramRenderer {

    Handle show(Player player, HologramView view);

    void update(Player player, Handle handle, HologramView view);

    void hide(Player player, Handle handle);

    record Handle(int entityId, UUID uuid) {
    }
}
