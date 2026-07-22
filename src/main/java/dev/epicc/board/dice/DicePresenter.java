package dev.epicc.board.dice;

import dev.epicc.board.Dice;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;

/**
 * Rolling die rides the player as a passenger (always in front, no per-tick teleport).
 * On settle: detaches once and drops to the ground in front.
 */
public final class DicePresenter {

    private static final float CUBE_HALF = 14f / 16f / 2f;
    /** Local Y of ride attachment ≈ chest/eye for standing player. */
    private static final float RIDE_EYE_Y = 1.4f;

    private final JavaPlugin plugin;
    private final DiceHatService hats;
    private double spawnDistance;
    private int interactTicks;
    private int spinIntervalTicks;
    private float displayScale;

    private final Map<UUID, Session> byPlayer = new ConcurrentHashMap<>();

    public DicePresenter(
            JavaPlugin plugin,
            DiceHatService hats,
            double spawnDistance,
            int interactSeconds,
            int spinIntervalTicks,
            float displayScale
    ) {
        this.plugin = plugin;
        this.hats = hats;
        reconfigure(spawnDistance, interactSeconds, spinIntervalTicks, displayScale);
    }

    public void reconfigure(double spawnDistance, int interactSeconds, int spinIntervalTicks, float displayScale) {
        this.spawnDistance = Math.max(0.5, spawnDistance);
        this.interactTicks = Math.max(1, interactSeconds) * 20;
        this.spinIntervalTicks = Math.max(1, spinIntervalTicks);
        this.displayScale = Math.max(0.1f, displayScale);
    }

    public boolean isRolling(UUID playerId) {
        return byPlayer.containsKey(playerId);
    }

    /**
     * Start visual roll. {@code onSettled} receives the final face (main thread).
     * Returns false if player already has a session.
     */
    public boolean start(Player player, Dice dice, IntConsumer onSettled) {
        if (byPlayer.containsKey(player.getUniqueId())) {
            return false;
        }

        int result = dice.roll();
        Session session = new Session(player.getUniqueId(), result, onSettled);
        Vector3f front = frontOffset();

        ItemDisplay display = player.getWorld().spawn(player.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(DiceItems.face(spinFace(dice)));
            d.setBillboard(Display.Billboard.FIXED);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setInterpolationDuration(spinIntervalTicks);
            d.setTeleportDuration(0);
            d.setTransformation(tumble(front, 0f, 0f));
            d.setPersistent(false);
            d.setShadowRadius(0f);
        });

        if (!player.addPassenger(display)) {
            display.remove();
            return false;
        }

        session.display = display;
        byPlayer.put(player.getUniqueId(), session);

        session.spinTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (session.settled || session.display == null || !session.display.isValid()) {
                return;
            }
            // Keep riding if something dismounted us
            Player rolling = plugin.getServer().getPlayer(session.playerId);
            if (rolling != null && rolling.isOnline() && session.display.getVehicle() == null) {
                rolling.addPassenger(session.display);
            }

            session.display.setItemStack(DiceItems.face(spinFace(dice)));
            float yaw = ThreadLocalRandom.current().nextFloat() * ((float) Math.PI * 2f);
            float pitch = ThreadLocalRandom.current().nextFloat() * ((float) Math.PI * 2f);
            session.display.setInterpolationDelay(0);
            session.display.setInterpolationDuration(spinIntervalTicks);
            // Same front offset every frame — only rotation changes
            session.display.setTransformation(tumble(front, yaw, pitch));
        }, 0L, spinIntervalTicks);

        session.timeoutTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> settle(session), interactTicks);
        return true;
    }

    /** Click or /party roll — settle if this player owns an active session. */
    public boolean trySettle(Player player) {
        Session session = byPlayer.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        settle(session);
        return true;
    }

    public void cancel(UUID playerId) {
        Session session = byPlayer.get(playerId);
        if (session != null) {
            session.aborted = true;
            session.settled = true;
            cleanupEntities(session);
            unregister(session);
        }
    }

    public void cancelAll() {
        for (Session session : byPlayer.values()) {
            session.aborted = true;
            session.settled = true;
            cleanupEntities(session);
        }
        byPlayer.clear();
    }

    private void settle(Session session) {
        if (session.settled) {
            return;
        }
        session.settled = true;
        cancelSpinAndTimeout(session);

        Player player = plugin.getServer().getPlayer(session.playerId);
        Location particleAt = null;

        if (session.display != null && session.display.isValid()) {
            session.display.setItemStack(DiceItems.face(session.result));

            // Detach so we can place it in the world once
            Entity vehicle = session.display.getVehicle();
            if (vehicle != null) {
                vehicle.removePassenger(session.display);
            }

            Location landAt;
            if (player != null && player.isOnline()) {
                landAt = groundInFront(player);
            } else {
                landAt = session.display.getLocation();
            }

            session.display.setTeleportDuration(Math.max(1, spinIntervalTicks));
            session.display.teleport(landAt);
            session.display.setInterpolationDelay(0);
            session.display.setInterpolationDuration(spinIntervalTicks);
            session.display.setTransformation(landUpright(displayScale));
            particleAt = landAt.clone().add(0, 0.15, 0);
        }

        if (player != null && player.isOnline()) {
            hats.setHat(player, session.result);
            if (particleAt == null) {
                particleAt = player.getEyeLocation();
            }
        }
        if (particleAt != null) {
            spawnPastelSmoke(particleAt);
        }

        long holdTicks = Math.max(12L, spinIntervalTicks + 6L);
        session.settleTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            session.settleTask = null;
            if (session.aborted) {
                return;
            }
            cleanupEntities(session);
            unregister(session);
            IntConsumer cb = session.onSettled;
            if (cb != null) {
                cb.accept(session.result);
            }
        }, holdTicks);
    }

    private Vector3f frontOffset() {
        // Local space of passenger on player: +Y up, −Z roughly look-forward for FIXED
        return new Vector3f(0f, RIDE_EYE_Y, (float) -spawnDistance);
    }

    private Transformation tumble(Vector3f front, float yaw, float pitch) {
        return new Transformation(
                new Vector3f(front),
                new AxisAngle4f(yaw, 0f, 1f, 0f),
                new Vector3f(displayScale, displayScale, displayScale),
                new AxisAngle4f(pitch, 1f, 0f, 0f)
        );
    }

    private static void spawnPastelSmoke(Location at) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Color[] pastels = {
                Color.fromRGB(255, 182, 193),
                Color.fromRGB(255, 218, 185),
                Color.fromRGB(255, 250, 205),
                Color.fromRGB(189, 236, 182),
                Color.fromRGB(174, 214, 241),
                Color.fromRGB(230, 204, 232),
                Color.fromRGB(255, 209, 220),
        };

        for (int i = 0; i < 22; i++) {
            Color c = pastels[rng.nextInt(pastels.length)];
            Particle.DustOptions dust = new Particle.DustOptions(c, 2.2f);
            double ox = rng.nextDouble(-0.45, 0.45);
            double oy = rng.nextDouble(-0.15, 0.5);
            double oz = rng.nextDouble(-0.45, 0.45);
            world.spawnParticle(Particle.DUST, at.clone().add(ox, oy, oz), 1, 0.02, 0.02, 0.02, 0, dust);
        }
    }

    private void cleanupEntities(Session session) {
        cancelSpinAndTimeout(session);
        if (session.settleTask != null) {
            session.settleTask.cancel();
            session.settleTask = null;
        }
        if (session.display != null && session.display.isValid()) {
            Entity vehicle = session.display.getVehicle();
            if (vehicle != null) {
                vehicle.removePassenger(session.display);
            }
            session.display.remove();
        }
        session.display = null;
    }

    private void cancelSpinAndTimeout(Session session) {
        if (session.spinTask != null) {
            session.spinTask.cancel();
            session.spinTask = null;
        }
        if (session.timeoutTask != null) {
            session.timeoutTask.cancel();
            session.timeoutTask = null;
        }
    }

    private void unregister(Session session) {
        byPlayer.remove(session.playerId, session);
    }

    private Location groundInFront(Player player) {
        Location feet = player.getLocation();
        Vector flat = feet.getDirection().setY(0);
        if (flat.lengthSquared() < 1.0e-6) {
            flat = new Vector(0, 0, 1);
        } else {
            flat.normalize();
        }
        Vector offset = flat.multiply(spawnDistance);

        Location probe = feet.clone().add(offset).add(0, 1.5, 0);
        World world = player.getWorld();
        RayTraceResult floor = world.rayTraceBlocks(
                probe, new Vector(0, -1, 0), 8.0, FluidCollisionMode.NEVER, true
        );

        Location at;
        if (floor != null && floor.getHitPosition() != null) {
            at = floor.getHitPosition().toLocation(world);
        } else {
            at = feet.clone().add(offset);
            at.setY(feet.getY());
        }

        at.add(0, displayScale * CUBE_HALF, 0);
        at.setYaw(player.getLocation().getYaw());
        at.setPitch(0f);
        return at;
    }

    private static int spinFace(Dice dice) {
        return dice.roll();
    }

    private static Transformation landUpright(float scale) {
        float yaw = ThreadLocalRandom.current().nextFloat() * ((float) Math.PI * 2f);
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f(yaw, 0f, 1f, 0f),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0f, 0f, 1f, 0f)
        );
    }

    private static final class Session {
        final UUID playerId;
        final int result;
        final IntConsumer onSettled;
        ItemDisplay display;
        BukkitTask spinTask;
        BukkitTask timeoutTask;
        BukkitTask settleTask;
        boolean settled;
        boolean aborted;

        Session(UUID playerId, int result, IntConsumer onSettled) {
            this.playerId = playerId;
            this.result = result;
            this.onSettled = onSettled;
        }
    }
}
