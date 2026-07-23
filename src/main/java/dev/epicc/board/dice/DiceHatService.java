package dev.epicc.board.dice;

import org.bukkit.Location;
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
 * Sticky dice hat: ItemDisplay passenger of the player (public — everyone can see it).
 * FIXED billboard, pitch-zero spawn, no extra local rotation.
 */
public final class DiceHatService {

    /** Above head; scale multiplies the 14/16 model cube. */
    private static final float HEAD_Y = 0.75f;

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

        // Fixed world orientation (yaw=pitch=0); only rides player via passenger attach
        Location at = player.getLocation().clone();
        at.setYaw(0f);
        at.setPitch(0f);

        ItemDisplay hat = player.getWorld().spawn(at, ItemDisplay.class, d -> {
            // Public by default — all players can see the hat
            d.setVisibleByDefault(true);
            d.setItemStack(DiceItems.face(face));
            d.setBillboard(Display.Billboard.FIXED);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
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
        hat.setRotation(0f, 0f);

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
