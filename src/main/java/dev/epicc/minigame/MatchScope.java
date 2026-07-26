package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Lifetime of one minigame run: the player set, their captured state, every scheduled task,
 * and a one-shot completion. Closing a scope cancels its tasks, restores every player and
 * detaches from {@link MinigameEventBus}, so a session cannot leak state or complete twice.
 */
public final class MatchScope {

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final MinigameEventBus events;
    private final MatchListener listener;
    private final Consumer<MinigameResult> done;
    /** Party order — placements and survivor ranking stay deterministic. */
    private final List<UUID> playerIds = new ArrayList<>();
    private final Map<UUID, PlayerStateSnapshot> snapshots = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    private boolean damageProtected;
    private boolean closed;

    private MatchScope(
            MinigameContext context,
            MatchListener listener,
            Consumer<MinigameResult> done
    ) {
        this.plugin = context.plugin();
        this.messages = context.messages();
        this.events = context.events();
        this.listener = listener;
        this.done = done;
    }

    /**
     * Snapshot every player, clear them for a fresh phase and start receiving routed events.
     */
    public static MatchScope open(
            MinigameContext context,
            MatchListener listener,
            Consumer<MinigameResult> done
    ) {
        MatchScope scope = new MatchScope(context, listener, done);
        for (Player player : context.onlinePlayers()) {
            scope.playerIds.add(player.getUniqueId());
            scope.snapshots.put(player.getUniqueId(), PlayerStateSnapshot.capture(player));
            PlayerStateSnapshot.preparePhase(player);
        }
        scope.events.register(scope, scope.playerIds);
        return scope;
    }

    MatchListener listener() {
        return listener;
    }

    boolean damageProtected() {
        return damageProtected;
    }

    /** Cancel all damage — including melee between players — for the rest of the match. */
    public void protectFromDamage() {
        damageProtected = true;
    }

    /** Match roster in party order, including players who have since gone offline. */
    public List<UUID> playerIds() {
        return List.copyOf(playerIds);
    }

    public boolean contains(UUID playerId) {
        return snapshots.containsKey(playerId);
    }

    /** Currently online match players, in party order. */
    public List<Player> onlinePlayers() {
        List<Player> online = new ArrayList<>(playerIds.size());
        for (UUID id : playerIds) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline()) {
                online.add(player);
            }
        }
        return online;
    }

    public BukkitTask repeating(long delayTicks, long periodTicks, Runnable action) {
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, action, delayTicks, periodTicks);
        tasks.add(task);
        return task;
    }

    public BukkitTask later(long delayTicks, Runnable action) {
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, action, delayTicks);
        tasks.add(task);
        return task;
    }

    public void broadcast(String messageKey, TagResolver... placeholders) {
        for (Player player : onlinePlayers()) {
            messages.send(player, messageKey, placeholders);
        }
    }

    /** Eliminated players watch the rest of the round instead of standing in the arena. */
    public void spectate(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
    }

    public boolean closed() {
        return closed;
    }

    /** Close the scope and report the result. Later calls are ignored. */
    public void finish(MinigameResult result) {
        if (closed) {
            return;
        }
        close();
        done.accept(result);
    }

    /** Close without reporting a result (session cancelled by the runner). */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        events.unregister(this);
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        for (UUID id : playerIds) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            PlayerStateSnapshot.preparePhase(player);
            PlayerStateSnapshot snapshot = snapshots.get(id);
            if (snapshot != null) {
                snapshot.restore(player);
            }
        }
    }
}
