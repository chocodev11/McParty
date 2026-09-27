package dev.epicc.minigame;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.function.Consumer;

/**
 * Shared minigame pickup: a tilted translucent box spinning around a camera-facing question
 * mark. The hitbox is a server-side AABB, so no Interaction entity is needed. Touching it hides
 * the box, fires the callback once and respawns it after {@code respawnTicks} ({@code <= 0}
 * means one-shot). Tasks and displays die with the owning {@link MatchScope}.
 */
public final class PowerUpBox {

    private static final NamespacedKey BOX_MODEL = new NamespacedKey("mcparty", "power_up_box");
    private static final NamespacedKey QUESTION_MODEL = new NamespacedKey("mcparty", "power_up_question");
    /** Each spin packet interpolates this long; the step must stay below 180° for slerp. */
    private static final int SPIN_PERIOD_TICKS = 5;
    private static final float SPIN_STEP = (float) Math.toRadians(30.0);
    private static final float FULL_TURN = (float) (Math.PI * 2.0);
    private static final Quaternionf TILT = new Quaternionf()
            .rotateX((float) Math.toRadians(25.0))
            .rotateZ((float) Math.toRadians(25.0));
    /** Keeps the billboarded mark inside the tilted shell from every angle. */
    private static final float QUESTION_SCALE = 0.8f;

    private final MatchScope scope;
    private final Location center;
    private final BoundingBox hitbox;
    private final float scale;
    private final long respawnTicks;
    private final Consumer<Player> onTouch;

    private ItemDisplay box;
    private ItemDisplay question;
    private float angle;
    private int ticks;
    private boolean removed;

    private PowerUpBox(MatchScope scope, Location center, float scale, long respawnTicks, Consumer<Player> onTouch) {
        this.scope = scope;
        this.center = center.clone();
        double half = scale * 0.5;
        this.hitbox = BoundingBox.of(center, half, half, half);
        this.scale = scale;
        this.respawnTicks = respawnTicks;
        this.onTouch = onTouch;
    }

    /** {@code scale} 1.0 is a one-block box and hitbox, centered on {@code center}. */
    public static PowerUpBox spawn(
            MatchScope scope,
            Location center,
            float scale,
            long respawnTicks,
            Consumer<Player> onTouch
    ) {
        PowerUpBox powerUp = new PowerUpBox(scope, center, scale, respawnTicks, onTouch);
        powerUp.show();
        scope.repeating(1L, 1L, powerUp::tick);
        scope.onClose(powerUp::despawn);
        return powerUp;
    }

    public Location center() {
        return center.clone();
    }

    public boolean active() {
        return box != null;
    }

    /** Remove for the rest of the match; a pending respawn is skipped. */
    public void remove() {
        removed = true;
        despawn();
    }

    private void tick() {
        if (box == null) {
            return;
        }
        if (++ticks % SPIN_PERIOD_TICKS == 0) {
            spin();
        }
        World world = center.getWorld();
        for (Player player : scope.onlinePlayers()) {
            if (player.getWorld() != world || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (!player.getBoundingBox().overlaps(hitbox)) {
                continue;
            }
            despawn();
            if (respawnTicks > 0) {
                scope.later(respawnTicks, this::show);
            }
            onTouch.accept(player);
            return;
        }
    }

    private void show() {
        if (removed || box != null) {
            return;
        }
        box = spawnDisplay(BOX_MODEL, Display.Billboard.FIXED, boxPose());
        question = spawnDisplay(QUESTION_MODEL, Display.Billboard.CENTER, new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(scale * QUESTION_SCALE),
                new Quaternionf()
        ));
    }

    private void despawn() {
        if (box != null) {
            box.remove();
            box = null;
        }
        if (question != null) {
            question.remove();
            question = null;
        }
    }

    private void spin() {
        angle += SPIN_STEP;
        if (angle >= FULL_TURN) {
            angle -= FULL_TURN;
        }
        box.setInterpolationDelay(0);
        box.setInterpolationDuration(SPIN_PERIOD_TICKS);
        box.setTransformation(boxPose());
    }

    private Transformation boxPose() {
        return new Transformation(
                new Vector3f(),
                new Quaternionf().rotationY(angle),
                new Vector3f(scale),
                new Quaternionf(TILT)
        );
    }

    private ItemDisplay spawnDisplay(NamespacedKey model, Display.Billboard billboard, Transformation pose) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> meta.setItemModel(model));
        return center.getWorld().spawn(center, ItemDisplay.class, entity -> {
            entity.setItemStack(item);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            entity.setBillboard(billboard);
            entity.setTransformation(pose);
            entity.setInterpolationDuration(0);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setGravity(false);
            entity.setNoPhysics(true);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setShadowRadius(0f);
            entity.setShadowStrength(0f);
        });
    }
}
