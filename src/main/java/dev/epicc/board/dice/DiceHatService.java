package dev.epicc.board.dice;

import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Passenger ItemDisplay above the player's head showing last roll face. */
public final class DiceHatService {

    private final Map<UUID, ItemDisplay> hats = new ConcurrentHashMap<>();
    private final float scale;

    public DiceHatService(float scale) {
        this.scale = Math.max(0.1f, scale);
    }

    public void setHat(Player player, int face) {
        clear(player.getUniqueId());
        ItemDisplay hat = player.getWorld().spawn(player.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(DiceItems.face(face));
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0.55f, 0f),
                    new AxisAngle4f(0f, 0f, 1f, 0f),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0f, 0f, 1f, 0f)
            ));
            d.setPersistent(false);
        });
        player.addPassenger(hat);
        hats.put(player.getUniqueId(), hat);
    }

    public void clear(UUID playerId) {
        ItemDisplay hat = hats.remove(playerId);
        if (hat != null && hat.isValid()) {
            hat.getPassengers().forEach(hat::removePassenger);
            if (hat.getVehicle() != null) {
                hat.getVehicle().removePassenger(hat);
            }
            hat.remove();
        }
    }

    public void clearAll() {
        for (UUID id : hats.keySet()) {
            clear(id);
        }
        hats.clear();
    }
}
