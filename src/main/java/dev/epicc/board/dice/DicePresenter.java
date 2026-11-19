package dev.epicc.board.dice;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Rolling dice ride the player as passengers (no freestanding teleport while spinning).
 * Entity yaw/pitch stay 0; "in front" is world-space transformation translation on the eye look-ray
 * (updated each tick; passenger attach is player height, so Y is relative to the top of the head).
 * Several dice sit side by side across the view and share one click and one timeout.
 * Visible only to the roller. On settle: detach onto the current eye ray, face the roller once,
 * hold 1s, callback.
 */
public final class DicePresenter {
    private static final int ENTRY_ANIMATION_TICKS = 8;
    private static final float HEAD_DIRECTION_FOLLOW_ALPHA = 0.22f;
    private static final long SETTLE_HOLD_TICKS = 20L;
    private static final int SETTLE_PARTICLE_COUNT = 72;
    /** Steady per-tick tumble (radians) — 60° yaw and 45° pitch every four ticks. */
    private static final float SPIN_YAW_PER_TICK = (float) (Math.PI / 12.0);
    private static final float SPIN_PITCH_PER_TICK = (float) (Math.PI / 16.0);
    /** Distance between neighbouring dice, as a multiple of the die scale. */
    private static final float DIE_SPACING = 1.2f;
    /** Tumble offset between neighbours so several dice do not spin in lockstep. */
    private static final float DIE_PHASE = 0.9f;

    private final JavaPlugin plugin;
    private final DiceHatService hats;
    private double spawnDistance;
    private int interactTicks;
    private int spinIntervalTicks;
    /** Rolling and settled floating size ({@code board.dice-spin-scale}). */
    private float spinScale;

    private final Map<UUID, Session> byPlayer = new ConcurrentHashMap<>();

    public DicePresenter(
            JavaPlugin plugin,
            DiceHatService hats,
            double spawnDistance,
            int interactSeconds,
            int spinIntervalTicks,
            float spinScale
    ) {
        this.plugin = plugin;
        this.hats = hats;
        reconfigure(spawnDistance, interactSeconds, spinIntervalTicks, spinScale);
    }

    public void reconfigure(
            double spawnDistance,
            int interactSeconds,
            int spinIntervalTicks,
            float spinScale
    ) {
        this.spawnDistance = Math.max(0.5, spawnDistance);
        this.interactTicks = Math.max(1, interactSeconds) * 20;
        this.spinIntervalTicks = Math.max(1, spinIntervalTicks);
        this.spinScale = Math.max(0.1f, spinScale);
    }

    public boolean isRolling(UUID playerId) {
        Session session = byPlayer.get(playerId);
        return session != null && !session.settled;
    }

    /**
     * Start a visual roll of {@code specs}. {@code onSettled} receives the final faces in spec order
     * (main thread) after the hold. Returns false if the player already has a session or a die
     * could not be mounted.
     */
    public boolean start(Player player, List<DieSpec> specs, Consumer<List<Integer>> onSettled) {
        if (specs.isEmpty() || byPlayer.containsKey(player.getUniqueId())) {
            return false;
        }

        Session session = new Session(player.getUniqueId(), onSettled);

        Location spawnAt = player.getLocation().clone();
        spawnAt.setYaw(0f);
        spawnAt.setPitch(0f);
        Vector3f eyeOffset = frontOffset(player, 0.0);

        for (int i = 0; i < specs.size(); i++) {
            DieSpec spec = specs.get(i);
            Die die = new Die(spec, spec.dice().roll(), lateral(i, specs.size()), i * DIE_PHASE);
            die.display = player.getWorld().spawn(spawnAt, ItemDisplay.class, d -> {
                d.setVisibleByDefault(false);
                d.setItemStack(spinFace(spec));
                d.setBillboard(Display.Billboard.FIXED);
                // NONE so only our transformation scale controls size (FIXED adds item-frame shrink)
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setInterpolationDuration(0);
                d.setTeleportDuration(0);
                // Start at the player's eyes; the next tick moves it smoothly along the look ray.
                d.setTransformation(tumble(sideways(eyeOffset, die.lateral), die.phase, die.phase));
                d.setPersistent(false);
                d.setShadowRadius(0f);
                d.setViewRange(48f);
            });
            die.display.setRotation(0f, 0f);
            session.dice.add(die);

            // Only the roller sees their dice (re-show after mount — needed after world change)
            player.showEntity(plugin, die.display);
            if (!player.addPassenger(die.display)) {
                cleanupEntities(session);
                return false;
            }
            player.showEntity(plugin, die.display);
        }
        byPlayer.put(player.getUniqueId(), session);

        session.entryTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            session.entryTask = null;
            if (session.settled) {
                return;
            }
            Player rolling = plugin.getServer().getPlayer(session.playerId);
            if (rolling == null || !rolling.isOnline()) {
                return;
            }
            session.followOffset = frontOffset(rolling);
            for (Die die : session.dice) {
                if (die.display == null || !die.display.isValid()) {
                    continue;
                }
                die.display.setInterpolationDelay(0);
                die.display.setInterpolationDuration(ENTRY_ANIMATION_TICKS);
                die.display.setTransformation(tumble(sideways(session.followOffset, die.lateral), die.phase, die.phase));
            }
        }, 1L);

        // Update every tick after the entrance so the client can interpolate a continuous, fixed-rate tumble.
        session.faceTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (session.settled) {
                return;
            }
            Player rolling = plugin.getServer().getPlayer(session.playerId);
            if (rolling == null || !rolling.isOnline()) {
                return;
            }
            boolean firstSpinTick = session.spinTicks == 0;
            Vector3f targetOffset = frontOffset(rolling);
            if (session.followOffset == null) {
                session.followOffset = targetOffset;
            } else {
                smoothTowards(session.followOffset, targetOffset, HEAD_DIRECTION_FOLLOW_ALPHA);
            }
            session.spinYaw += SPIN_YAW_PER_TICK;
            session.spinPitch += SPIN_PITCH_PER_TICK;
            session.spinTicks++;
            boolean swapFace = session.spinTicks % (spinIntervalTicks * 2) == 0;

            for (Die die : session.dice) {
                ItemDisplay display = die.display;
                if (display == null || !display.isValid()) {
                    continue;
                }
                if (display.getVehicle() == null) {
                    rolling.addPassenger(display);
                }
                // Keep private visibility after chunk/vehicle track updates
                rolling.showEntity(plugin, display);
                display.setRotation(0f, 0f);
                display.setInterpolationDelay(0);
                if (firstSpinTick) {
                    display.setInterpolationDuration(1);
                }
                display.setTransformation(tumble(
                        sideways(session.followOffset, die.lateral),
                        session.spinYaw + die.phase,
                        session.spinPitch + die.phase
                ));
                if (swapFace) {
                    display.setItemStack(spinFace(die.spec));
                }
            }
        }, ENTRY_ANIMATION_TICKS + 1L, 1L);

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
        boolean online = player != null && player.isOnline();
        int landAnimTicks = Math.max(1, spinIntervalTicks);
        List<ItemStack> faces = new ArrayList<>(session.dice.size());

        for (Die die : session.dice) {
            ItemStack face = DiceItems.face(die.spec.skin(), die.result);
            faces.add(face);
            ItemDisplay display = die.display;
            if (display == null || !display.isValid()) {
                continue;
            }
            display.setItemStack(face);

            Entity vehicle = display.getVehicle();
            if (vehicle != null) {
                vehicle.removePassenger(display);
            }

            Location settledAt = online ? floatingInFront(player, die.lateral) : display.getLocation().clone();
            float settledYaw = online ? player.getLocation().getYaw() + 180f : display.getYaw();
            settledAt.setYaw(settledYaw);
            settledAt.setPitch(0f);
            display.setTeleportDuration(landAnimTicks);
            display.teleport(settledAt);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(landAnimTicks);
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(floatingPose());

            // Private display can drop tracking after dismount — keep roller viewer
            if (online) {
                player.showEntity(plugin, display);
            }
        }

        if (online) {
            hats.setHat(player, faces);
            spawnPastelSmoke(floatingInFront(player, 0f).add(0, 0.05, 0));
        }

        // Finish the short detach motion, then hold the floating result for one second.
        session.settleTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (session.aborted) {
                session.settleTask = null;
                return;
            }
            for (Die die : session.dice) {
                if (die.display != null && die.display.isValid()) {
                    die.display.setTeleportDuration(0);
                    die.display.setInterpolationDuration(0);
                }
            }
            session.settleTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                session.settleTask = null;
                if (session.aborted) {
                    return;
                }
                cleanupEntities(session);
                unregister(session);
                Consumer<List<Integer>> cb = session.onSettled;
                if (cb != null) {
                    cb.accept(session.results());
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
        return frontOffset(player, spawnDistance);
    }

    private Vector3f frontOffset(Player player, double distance) {
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
        float fx = (float) (eye.getX() + dir.getX() * distance - feet.getX());
        float fy = (float) (eye.getY() + dir.getY() * distance - (feet.getY() + attachY));
        float fz = (float) (eye.getZ() + dir.getZ() * distance - feet.getZ());
        return new Vector3f(fx, fy, fz);
    }

    /** Sideways position of die {@code index} of {@code count}, centred on the look ray. */
    private float lateral(int index, int count) {
        return (index - (count - 1) / 2f) * spinScale * DIE_SPACING;
    }

    /** Shift {@code front} to the right of its horizontal direction (negative = left). */
    private static Vector3f sideways(Vector3f front, float lateral) {
        Vector3f shifted = new Vector3f(front);
        if (lateral == 0f) {
            return shifted;
        }
        float length = (float) Math.sqrt(front.x * front.x + front.z * front.z);
        if (length < 1.0e-4f) {
            return shifted.add(lateral, 0f, 0f);
        }
        return shifted.add(-front.z / length * lateral, 0f, front.x / length * lateral);
    }

    private Transformation tumble(Vector3f front, float yaw, float pitch) {
        return new Transformation(
                new Vector3f(front),
                new AxisAngle4f(yaw, 0f, 1f, 0f),
                scaleVec(spinScale),
                new AxisAngle4f(pitch, 1f, 0f, 0f)
        );
    }

    private static void smoothTowards(Vector3f current, Vector3f target, float alpha) {
        current.x += (target.x - current.x) * alpha;
        current.y += (target.y - current.y) * alpha;
        current.z += (target.z - current.z) * alpha;
    }

    private Location floatingInFront(Player player, float lateral) {
        Location at = player.getEyeLocation();
        Vector direction = at.getDirection();
        if (direction.lengthSquared() < 1.0e-6) {
            direction.setZ(1);
        } else {
            direction.normalize();
        }
        at.add(direction.clone().multiply(spawnDistance));
        if (lateral != 0f) {
            double length = Math.sqrt(direction.getX() * direction.getX() + direction.getZ() * direction.getZ());
            if (length < 1.0e-4) {
                at.add(lateral, 0, 0);
            } else {
                at.add(-direction.getZ() / length * lateral, 0, direction.getX() / length * lateral);
            }
        }
        return at;
    }

    private Transformation floatingPose() {
        return new Transformation(
                new Vector3f(),
                new Quaternionf(),
                scaleVec(spinScale),
                new Quaternionf()
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

        for (int i = 0; i < SETTLE_PARTICLE_COUNT; i++) {
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
        for (Die die : session.dice) {
            ItemDisplay display = die.display;
            if (display != null && display.isValid()) {
                Entity vehicle = display.getVehicle();
                if (vehicle != null) {
                    vehicle.removePassenger(display);
                }
                display.remove();
            }
            die.display = null;
        }
    }

    private void cancelSpinAndTimeout(Session session) {
        if (session.entryTask != null) {
            session.entryTask.cancel();
            session.entryTask = null;
        }
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

    private static ItemStack spinFace(DieSpec spec) {
        return DiceItems.face(spec.skin(), spec.dice().roll());
    }

    private static final class Session {
        final UUID playerId;
        final Consumer<List<Integer>> onSettled;
        final List<Die> dice = new ArrayList<>();
        BukkitTask entryTask;
        BukkitTask faceTask;
        BukkitTask timeoutTask;
        BukkitTask settleTask;
        Vector3f followOffset;
        float spinYaw;
        float spinPitch;
        int spinTicks;
        boolean settled;
        boolean aborted;

        Session(UUID playerId, Consumer<List<Integer>> onSettled) {
            this.playerId = playerId;
            this.onSettled = onSettled;
        }

        List<Integer> results() {
            List<Integer> results = new ArrayList<>(dice.size());
            for (Die die : dice) {
                results.add(die.result);
            }
            return results;
        }
    }

    private static final class Die {
        final DieSpec spec;
        /** Decided at start; clicking only reveals it. */
        final int result;
        final float lateral;
        final float phase;
        ItemDisplay display;

        Die(DieSpec spec, int result, float lateral, float phase) {
            this.spec = spec;
            this.result = result;
            this.lateral = lateral;
            this.phase = phase;
        }
    }
}
