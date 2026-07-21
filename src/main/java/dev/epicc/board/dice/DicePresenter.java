package dev.epicc.board.dice;

import dev.epicc.board.Dice;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;

/**
 * Spawns a spinny ItemDisplay + Interaction in front of the player.
 * Settles on click or timeout; result is chosen when the session starts.
 */
public final class DicePresenter {

    private final JavaPlugin plugin;
    private final DiceHatService hats;
    private final double spawnDistance;
    private final int interactTicks;
    private final int spinIntervalTicks;
    private final float displayScale;

    private final Map<UUID, Session> byPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Session> byInteraction = new ConcurrentHashMap<>();

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
        Location at = spawnLocation(player);
        Session session = new Session(player.getUniqueId(), result, onSettled);

        ItemDisplay display = player.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(DiceItems.face(spinFace(dice)));
            d.setBillboard(Display.Billboard.CENTER);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setInterpolationDuration(spinIntervalTicks);
            d.setTransformation(identityScale(displayScale));
            d.setPersistent(false);
        });

        Interaction interaction = player.getWorld().spawn(at, Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(0.9f);
            i.setResponsive(true);
            i.setPersistent(false);
        });

        session.display = display;
        session.interaction = interaction;
        byPlayer.put(player.getUniqueId(), session);
        byInteraction.put(interaction.getUniqueId(), session);

        session.spinTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (session.settled || session.display == null || !session.display.isValid()) {
                return;
            }
            session.display.setItemStack(DiceItems.face(spinFace(dice)));
            float angle = ThreadLocalRandom.current().nextFloat() * ((float) Math.PI * 2f);
            session.display.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(angle, 0f, 1f, 0f),
                    new Vector3f(displayScale, displayScale, displayScale),
                    new AxisAngle4f(angle * 0.5f, 1f, 0f, 0f)
            ));
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

    public boolean trySettleFromEntity(Player player, Entity entity) {
        if (entity == null) {
            return false;
        }
        Session session = byInteraction.get(entity.getUniqueId());
        if (session == null || !session.playerId.equals(player.getUniqueId())) {
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
        byInteraction.clear();
    }

    private void settle(Session session) {
        if (session.settled) {
            return;
        }
        session.settled = true;
        cancelSpinAndTimeout(session);

        if (session.display != null && session.display.isValid()) {
            session.display.setItemStack(DiceItems.face(session.result));
            session.display.setTransformation(identityScale(displayScale));
        }

        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player != null && player.isOnline()) {
            hats.setHat(player, session.result);
        }

        // brief hold so the final face is visible, then remove prop and apply move
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
        }, 6L);
    }

    private void cleanupEntities(Session session) {
        cancelSpinAndTimeout(session);
        if (session.settleTask != null) {
            session.settleTask.cancel();
            session.settleTask = null;
        }
        if (session.interaction != null) {
            byInteraction.remove(session.interaction.getUniqueId(), session);
            if (session.interaction.isValid()) {
                session.interaction.remove();
            }
            session.interaction = null;
        }
        if (session.display != null && session.display.isValid()) {
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

    private Location spawnLocation(Player player) {
        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTraceBlocks(
                eye, eye.getDirection(), spawnDistance, FluidCollisionMode.NEVER, true
        );
        Location at;
        if (hit != null && hit.getHitPosition() != null) {
            at = hit.getHitPosition().toLocation(player.getWorld());
            at.subtract(eye.getDirection().multiply(0.35));
        } else {
            at = eye.clone().add(eye.getDirection().multiply(spawnDistance));
        }
        at.add(0, -0.15, 0);
        at.setYaw(player.getLocation().getYaw());
        at.setPitch(0f);
        return at;
    }

    private static int spinFace(Dice dice) {
        return dice.roll();
    }

    private static Transformation identityScale(float scale) {
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f(0f, 0f, 1f, 0f),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0f, 0f, 1f, 0f)
        );
    }

    private static final class Session {
        final UUID playerId;
        final int result;
        final IntConsumer onSettled;
        ItemDisplay display;
        Interaction interaction;
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
