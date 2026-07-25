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
 * Rolling die rides the player as a passenger (no freestanding teleport while spinning).
 * Entity yaw/pitch stay 0; "in front" is world-space transformation translation on the eye look-ray
 * (updated each tick; passenger attach is player height, so Y is relative to the top of the head).
 * Visible only to the roller. On settle: detach, land upright on the look-ray floor, hold 1s, callback.
 */
public final class DicePresenter {

    /**
     * Matches resourcepack {@code dice_*.json}: element {@code from [1,0,1] to [15,14,15]}.
     * ItemDisplay + {@code NONE} pivots on the 16px item-space center (8/16), not the cube
     * midpoint (7/16). Bottom is at model y=0 → 8px below origin. Using 7px left the die
     * buried by exactly 1 texture pixel.
     */
    private static final float MODEL_UNIT = 1f / 16f;
    /** Entity → mesh bottom (item center at 8, cube bottom at 0). */
    private static final float MODEL_ORIGIN_TO_BOTTOM = 8f * MODEL_UNIT;
    private static final long SETTLE_HOLD_TICKS = 20L;
    /** How far along the eye ray to search for a land surface on settle. */
    private static final double SETTLE_RAY_RANGE = 8.0;
    /** Steady per-tick tumble (radians) — 60° yaw and 45° pitch every four ticks. */
    private static final float SPIN_YAW_PER_TICK = (float) (Math.PI / 12.0);
    private static final float SPIN_PITCH_PER_TICK = (float) (Math.PI / 16.0);

    private final JavaPlugin plugin;
    private final DiceHatService hats;
    private double spawnDistance;
    private int interactTicks;
    private int spinIntervalTicks;
    /** Settle / on-ground size ({@code board.dice-display-scale}). */
    private float displayScale;
    /** Spin in front of eyes ({@code board.dice-spin-scale}); usually smaller than settle. */
    private float spinScale;

    private final Map<UUID, Session> byPlayer = new ConcurrentHashMap<>();

    public DicePresenter(
            JavaPlugin plugin,
            DiceHatService hats,
            double spawnDistance,
            int interactSeconds,
            int spinIntervalTicks,
            float displayScale,
            float spinScale
    ) {
        this.plugin = plugin;
        this.hats = hats;
        reconfigure(spawnDistance, interactSeconds, spinIntervalTicks, displayScale, spinScale);
    }

    public void reconfigure(
            double spawnDistance,
            int interactSeconds,
            int spinIntervalTicks,
            float displayScale,
            float spinScale
    ) {
        this.spawnDistance = Math.max(0.5, spawnDistance);
        this.interactTicks = Math.max(1, interactSeconds) * 20;
        this.spinIntervalTicks = Math.max(1, spinIntervalTicks);
        this.displayScale = Math.max(0.1f, displayScale);
        this.spinScale = Math.max(0.1f, spinScale);
    }

    public boolean isRolling(UUID playerId) {
        Session session = byPlayer.get(playerId);
        return session != null && !session.settled;
    }

    /**
     * Start visual roll. {@code onSettled} receives the final face (main thread) after the hold.
     * Returns false if player already has a session.
     */
    public boolean start(Player player, Dice dice, IntConsumer onSettled) {
        if (byPlayer.containsKey(player.getUniqueId())) {
            return false;
        }

        int result = dice.roll();
        Session session = new Session(player.getUniqueId(), result, onSettled);

        Location spawnAt = player.getLocation().clone();
        spawnAt.setYaw(0f);
        spawnAt.setPitch(0f);

        ItemDisplay display = player.getWorld().spawn(spawnAt, ItemDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setItemStack(DiceItems.face(spinFace(dice)));
            d.setBillboard(Display.Billboard.FIXED);
            // NONE so only our transformation scale controls size (FIXED adds item-frame shrink)
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setInterpolationDuration(1);
            d.setTeleportDuration(0);
            // Entity yaw/pitch stay 0 — "in front" is pure translation from player look (no setRotation lag)
            d.setTransformation(tumble(frontOffset(player), 0f, 0f));
            d.setPersistent(false);
            d.setShadowRadius(0f);
            d.setViewRange(48f);
        });
        display.setRotation(0f, 0f);

        // Only the roller sees their die (re-show after mount — needed after world change)
        player.showEntity(plugin, display);

        if (!player.addPassenger(display)) {
            display.remove();
            return false;
        }
        player.showEntity(plugin, display);

        session.display = display;
        byPlayer.put(player.getUniqueId(), session);

        // Update every tick so the client can interpolate a continuous, fixed-rate tumble.
        session.faceTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (session.settled || session.display == null || !session.display.isValid()) {
                return;
            }
            Player rolling = plugin.getServer().getPlayer(session.playerId);
            if (rolling == null || !rolling.isOnline()) {
                return;
            }
            if (session.display.getVehicle() == null) {
                rolling.addPassenger(session.display);
            }
            // Keep private visibility after chunk/vehicle track updates
            rolling.showEntity(plugin, session.display);
            session.display.setRotation(0f, 0f);
            session.display.setInterpolationDelay(0);
            session.spinYaw += SPIN_YAW_PER_TICK;
            session.spinPitch += SPIN_PITCH_PER_TICK;
            session.spinTicks++;
            session.display.setTransformation(
                    tumble(frontOffset(rolling), session.spinYaw, session.spinPitch)
            );
            if (session.spinTicks % (spinIntervalTicks * 2) == 0) {
                session.display.setItemStack(DiceItems.face(spinFace(dice)));
            }
        }, 1L, 1L);

        session.timeoutTask = plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> settle(session), interactTicks
        );
        return true;
    }

    /** Click or /party roll — settle if this player owns an active (not yet settled) session. */
    public boolean trySettle(Player player) {
        Session session = byPlayer.get(player.getUniqueId());
        if (session == null || session.settled) {
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

            Entity vehicle = session.display.getVehicle();
            if (vehicle != null) {
                vehicle.removePassenger(session.display);
            }

            Location landAt;
            if (player != null && player.isOnline()) {
                landAt = groundInFront(player);
            } else {
                landAt = session.display.getLocation();
                landAt.setPitch(0f);
            }

            // Short land motion, then freeze for the hold (see settleTask below)
            int landAnimTicks = Math.max(1, spinIntervalTicks);
            session.display.setTeleportDuration(landAnimTicks);
            session.display.teleport(landAt);
            session.display.setRotation(landAt.getYaw(), 0f);
            session.display.setInterpolationDelay(0);
            session.display.setInterpolationDuration(landAnimTicks);
            // Same scale as spin ({@link #tumble}); upright, no extra local offset
            session.display.setTransformation(diceScale(displayScale));
            particleAt = landAt.clone().add(0, 0.05, 0);

            // Private display can drop tracking after dismount — keep roller viewer
            if (player != null && player.isOnline()) {
                player.showEntity(plugin, session.display);
            }
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

        // After land anim: freeze in place, stay SETTLE_HOLD_TICKS, then remove + board callback
        int landAnimTicks = Math.max(1, spinIntervalTicks);
        session.settleTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (session.aborted) {
                session.settleTask = null;
                return;
            }
            // Snap static for the hold second (no further interpolation)
            if (session.display != null && session.display.isValid()) {
                session.display.setTeleportDuration(0);
                session.display.setInterpolationDuration(0);
            }
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
            }, SETTLE_HOLD_TICKS);
        }, landAnimTicks);
    }

    /**
     * World-space translation so the die sits on the eye look-ray at {@link #spawnDistance}.
     * Display entity yaw/pitch stay 0 (world-aligned axes). Passenger attach on a player is
     * {@code AT_HEIGHT} (feet + {@link Player#getHeight()}), not the feet — a positive local Y
     * stacks on top of the head and floats the die far above the eyes.
     */
    private Vector3f frontOffset(Player player) {
        Location feet = player.getLocation();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        if (dir.lengthSquared() < 1.0e-6) {
            dir = new Vector(0, 0, 1);
        } else {
            dir.normalize();
        }
        // target = eye + look * spawnDistance; attach ≈ feet + (0, height, 0)
        double attachY = player.getHeight();
        float fx = (float) (eye.getX() + dir.getX() * spawnDistance - feet.getX());
        float fy = (float) (eye.getY() + dir.getY() * spawnDistance - (feet.getY() + attachY));
        float fz = (float) (eye.getZ() + dir.getZ() * spawnDistance - feet.getZ());
        return new Vector3f(fx, fy, fz);
    }

    private Transformation tumble(Vector3f front, float yaw, float pitch) {
        return new Transformation(
                new Vector3f(front),
                new AxisAngle4f(yaw, 0f, 1f, 0f),
                scaleVec(spinScale),
                new AxisAngle4f(pitch, 1f, 0f, 0f)
        );
    }

    /** Settle pose: ground size only, no rotation/translation (entity holds world pose). */
    private static Transformation diceScale(float scale) {
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f(0f, 0f, 1f, 0f),
                scaleVec(scale),
                new AxisAngle4f(0f, 0f, 1f, 0f)
        );
    }

    private static Vector3f scaleVec(float scale) {
        return new Vector3f(scale, scale, scale);
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

        for (int i = 0; i < 36; i++) {
            Color c = pastels[rng.nextInt(pastels.length)];
            Particle.DustOptions dust = new Particle.DustOptions(c, 2.6f);
            double ox = rng.nextDouble(-1.1, 1.1);
            double oy = rng.nextDouble(-0.25, 1.0);
            double oz = rng.nextDouble(-1.1, 1.1);
            world.spawnParticle(Particle.DUST, at.clone().add(ox, oy, oz), 1, 0.08, 0.08, 0.08, 0, dust);
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
        if (session.faceTask != null) {
            session.faceTask.cancel();
            session.faceTask = null;
        }
        if (session.timeoutTask != null) {
            session.timeoutTask.cancel();
            session.timeoutTask = null;
        }
    }

    private void unregister(Session session) {
        byPlayer.remove(session.playerId, session);
    }

    /**
     * Land on the surface under the player's look ray (same aim as the spinning die).
     * Raycasts from the eyes; if a block is hit, drops from that XZ to the floor so the
     * die rests on pads/ground rather than sticking to a wall face.
     * <p>
     * Y: snap surface to 1/16, then lift by half model height × {@link #displayScale}
     * so the cube sits on the pad (visual center is the entity origin).
     */
    private Location groundInFront(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        if (dir.lengthSquared() < 1.0e-6) {
            dir = new Vector(0, 0, 1);
        } else {
            dir.normalize();
        }

        World world = player.getWorld();
        double range = Math.max(spawnDistance, SETTLE_RAY_RANGE);
        RayTraceResult lookHit = world.rayTraceBlocks(
                eye, dir, range, FluidCollisionMode.NEVER, true
        );

        Vector aim;
        if (lookHit != null && lookHit.getHitPosition() != null) {
            aim = lookHit.getHitPosition();
        } else {
            aim = eye.toVector().add(dir.clone().multiply(spawnDistance));
        }

        // From slightly above the aim point, find the floor so the cube sits on a pad
        Location probe = new Location(world, aim.getX(), aim.getY() + 0.25, aim.getZ());
        RayTraceResult floor = world.rayTraceBlocks(
                probe, new Vector(0, -1, 0), 8.0, FluidCollisionMode.NEVER, true
        );

        Location at;
        if (floor != null && floor.getHitPosition() != null) {
            at = floor.getHitPosition().toLocation(world);
        } else {
            at = aim.toLocation(world);
            // Fallback: keep player feet Y if no floor under the aim point
            at.setY(player.getLocation().getY());
        }

        double surfaceY = snapModelGrid(at.getY());
        // Origin is item-space center: lift by 8px so model y=0 sits on the pad
        at.setY(surfaceY + displayScale * MODEL_ORIGIN_TO_BOTTOM);
        at.setYaw(player.getLocation().getYaw());
        at.setPitch(0f);
        return at;
    }

    /** Snap world Y to the item-model pixel grid (1/16 block). */
    private static double snapModelGrid(double y) {
        return Math.round(y * 16.0) / 16.0;
    }

    private static int spinFace(Dice dice) {
        return dice.roll();
    }

    private static final class Session {
        final UUID playerId;
        final int result;
        final IntConsumer onSettled;
        ItemDisplay display;
        BukkitTask faceTask;
        BukkitTask timeoutTask;
        BukkitTask settleTask;
        float spinYaw;
        float spinPitch;
        int spinTicks;
        boolean settled;
        boolean aborted;

        Session(UUID playerId, int result, IntConsumer onSettled) {
            this.playerId = playerId;
            this.result = result;
            this.onSettled = onSettled;
        }
    }
}
