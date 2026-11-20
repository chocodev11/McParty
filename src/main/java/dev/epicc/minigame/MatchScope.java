package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
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
 * <p>
 * Phases: {@link IntroSequence} freezes the scope until GO, the session runs LIVE, and
 * {@link #conclude} shows the END screen before reporting.
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
    /** Names captured at open, so rankings can still name players who went offline. */
    private final Map<UUID, String> names = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<Runnable> closeActions = new ArrayList<>();
    private final List<Consumer<Player>> detachActions = new ArrayList<>();
    private final int introSeconds;
    private final int endSeconds;

    private boolean damageProtected;
    private boolean frozen;
    private boolean concluding;
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
        this.introSeconds = context.introSeconds();
        this.endSeconds = context.endSeconds();
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
            scope.names.put(player.getUniqueId(), player.getName());
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

    boolean frozen() {
        return frozen;
    }

    /**
     * Hold players in place (they can still look around) and swallow gameplay input until
     * {@link #unfreeze()}. Used by INTRO and END; teleports are not affected.
     */
    public void freeze() {
        frozen = true;
    }

    public void unfreeze() {
        frozen = false;
    }

    MessageService messages() {
        return messages;
    }

    int introSeconds() {
        return introSeconds;
    }

    /** Name at match start; also resolves players who have since gone offline. */
    public String name(UUID playerId) {
        return names.getOrDefault(playerId, "?");
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

    /** Runs when the scope closes, before players are restored — for match-owned entities. */
    public void onClose(Runnable action) {
        closeActions.add(action);
    }

    /** Runs for a player who leaves mid-match, after their state is restored — for per-player UI. */
    public void onDetach(Consumer<Player> action) {
        detachActions.add(action);
    }

    /** Route the entity's events to this match; it is removed when the scope closes. */
    public <T extends Entity> T track(T entity) {
        events.track(entity, this);
        onClose(() -> {
            if (entity.isValid()) {
                entity.remove();
            }
        });
        return entity;
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

    /** Revoke ownership before notifying gameplay, which may finish the match. */
    public void detach(Player player) {
        UUID id = player.getUniqueId();
        PlayerStateSnapshot snapshot = snapshots.remove(id);
        if (snapshot == null || closed) return;
        playerIds.remove(id);
        events.unregister(player, this);
        PlayerStateSnapshot.preparePhase(player);
        snapshot.restore(player);
        for (Consumer<Player> action : detachActions) {
            action.accept(player);
        }
        listener.onQuit(player);
    }

    /**
     * END phase: stop gameplay, show everyone their placement, then report the result. Later calls
     * are ignored, so a quit during the end screen cannot change a ranking already shown.
     */
    public void conclude(MinigameResult result) {
        if (closed || concluding) {
            return;
        }
        concluding = true;
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        frozen = true;
        if (endSeconds > 0) {
            showPlacements(result);
        }
        // Never report from inside the caller's event: a quitter is still in the arena world
        // during PlayerQuitEvent, and the runner unloads that world on completion
        later(Math.max(1L, endSeconds * 20L), () -> finish(result));
    }

    private void showPlacements(MinigameResult result) {
        List<Map.Entry<UUID, Integer>> podium = result.placements().entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(3)
                .toList();
        Title.Times times = Title.Times.times(
                Duration.ofMillis(200), Duration.ofSeconds(endSeconds), Duration.ofMillis(400)
        );
        for (Player player : onlinePlayers()) {
            Integer place = result.placements().get(player.getUniqueId());
            if (place == null) {
                continue;
            }
            String placeText = Integer.toString(place);
            String coins = Integer.toString(result.coinRewards().getOrDefault(player.getUniqueId(), 0));
            player.showTitle(Title.title(
                    messages.get("minigame.end-title", "place", placeText),
                    messages.get("minigame.end-subtitle", "coins", coins),
                    times
            ));
            messages.send(player, "minigame.end-header");
            for (Map.Entry<UUID, Integer> entry : podium) {
                messages.send(player, "minigame.end-line",
                        "place", Integer.toString(entry.getValue()),
                        "player", name(entry.getKey()));
            }
            messages.send(player, "minigame.end-self", "place", placeText, "coins", coins);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
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
        for (Runnable action : closeActions) {
            action.run();
        }
        closeActions.clear();
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
