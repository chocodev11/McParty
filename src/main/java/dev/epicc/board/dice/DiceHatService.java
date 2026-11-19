package dev.epicc.board.dice;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sticky dice hat: one ItemDisplay passenger per die (public — everyone can see it).
 * A single die keeps a FIXED billboard; several dice turn with each viewer (VERTICAL) so the
 * sideways offset reads as "side by side" from every angle.
 */
public final class DiceHatService {

    /** Above head; scale multiplies the 14/16 model cube. */
    private static final float HEAD_Y = 0.75f;
    private static final float MULTI_SCALE = 0.75f;
    private static final float MULTI_SPACING = 1.15f;

    private final Map<UUID, List<ItemDisplay>> hats = new ConcurrentHashMap<>();
    private float scale;

    public DiceHatService(float scale) {
        reconfigure(scale);
    }

    public void reconfigure(float scale) {
        this.scale = Math.max(0.1f, scale);
    }

    public void setHat(Player player, List<ItemStack> faces) {
        clear(player.getUniqueId());

        // Fixed world orientation (yaw=pitch=0); only rides player via passenger attach
        Location at = player.getLocation().clone();
        at.setYaw(0f);
        at.setPitch(0f);

        boolean single = faces.size() == 1;
        float size = single ? scale : scale * MULTI_SCALE;
        float spacing = size * MULTI_SPACING;
        List<ItemDisplay> spawned = new ArrayList<>(faces.size());
        for (int i = 0; i < faces.size(); i++) {
            ItemStack face = faces.get(i);
            float x = single ? 0f : (i - (faces.size() - 1) / 2f) * spacing;
            ItemDisplay hat = player.getWorld().spawn(at, ItemDisplay.class, d -> {
                // Public by default — all players can see the hat
                d.setVisibleByDefault(true);
                d.setItemStack(face);
                d.setBillboard(single ? Display.Billboard.FIXED : Display.Billboard.VERTICAL);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setInterpolationDuration(0);
                d.setTeleportDuration(0);
                d.setTransformation(new Transformation(
                        new Vector3f(x, HEAD_Y, 0f),
                        new AxisAngle4f(0f, 0f, 1f, 0f),
                        new Vector3f(size, size, size),
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
                continue;
            }
            spawned.add(hat);
        }
        if (!spawned.isEmpty()) {
            hats.put(player.getUniqueId(), spawned);
        }
    }

    public void clear(UUID playerId) {
        List<ItemDisplay> removed = hats.remove(playerId);
        if (removed == null) {
            return;
        }
        for (ItemDisplay hat : removed) {
            if (!hat.isValid()) {
                continue;
            }
            Entity vehicle = hat.getVehicle();
            if (vehicle != null) {
                vehicle.removePassenger(hat);
            }
            hat.remove();
        }
    }

    public void clearAll() {
        for (UUID id : new ArrayList<>(hats.keySet())) {
            clear(id);
        }
    }
}
