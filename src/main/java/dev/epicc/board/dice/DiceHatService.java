package dev.epicc.board.dice;

import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sticky dice hat: ItemDisplay passenger of the player (rides head like gear).
 * FIXED billboard so it turns with the body instead of facing the camera.
 */
public final class DiceHatService {

    private static final float HEAD_Y = 0.55f;

    private final Map<UUID, ItemDisplay> hats = new ConcurrentHashMap<>();
    private float scale;

    public DiceHatService(float scale) {
        reconfigure(scale);
    }

    public void reconfigure(float scale) {
        this.scale = Math.max(0.1f, scale);
    }

    public void setHat(Player player, int face) {
        clear(player.getUniqueId());

        ItemDisplay hat = player.getWorld().spawn(player.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(DiceItems.face(face));
            d.setBillboard(Display.Billboard.FIXED);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setInterpolationDuration(0);
            d.setTeleportDuration(0);
            d.setTransformation(new Transformation(
                    new Vector3f(0f, HEAD_Y, 0f),
                    new AxisAngle4f(0f, 0f, 1f, 0f),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0f, 0f, 1f, 0f)
            ));
            d.setPersistent(false);
            d.setViewRange(64f);
            d.setShadowRadius(0f);
            d.setShadowStrength(0f);
        });
        // Passenger = client-side stick to player (no server teleports)
        if (!player.addPassenger(hat)) {
            hat.remove();
            return;
        }
        hats.put(player.getUniqueId(), hat);
    }

    public void clear(UUID playerId) {
        ItemDisplay hat = hats.remove(playerId);
        if (hat == null) {
            return;
        }
        if (hat.isValid()) {
            Entity vehicle = hat.getVehicle();
            if (vehicle != null) {
                vehicle.removePassenger(hat);
            }
            hat.remove();
        }
    }

    public void clearAll() {
        for (UUID id : new java.util.ArrayList<>(hats.keySet())) {
            clear(id);
        }
    }
}
