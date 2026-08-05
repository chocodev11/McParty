package dev.epicc.lobby.parkour;

import dev.epicc.config.MessageService;
import dev.epicc.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class LobbyParkourService {

    private static final String GOAL = "goal";
    private static final double TRIGGER_RANGE = 0.9;
    private static final double LAUNCH_VELOCITY = 5.0;
    private static final long LAUNCH_TIMEOUT_TICKS = 160L;
    private static final long HOTBAR_ITEM_COOLDOWN_NANOS = 500_000_000L;

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final MessageService messages;
    private final ParkourLeaderboardStore leaderboard;
    private final NamespacedKey triggerKey;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    public LobbyParkourService(
            JavaPlugin plugin,
            PluginConfig config,
            MessageService messages,
            ParkourLeaderboardStore leaderboard
    ) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.leaderboard = leaderboard;
        this.triggerKey = new NamespacedKey(plugin, "lobby_parkour_trigger");
    }

    public boolean isRunning(UUID playerId) {
        return runs.containsKey(playerId);
    }

    public LobbyParkourDefinition definition() {
        return config.lobbyParkour();
    }

    public String action(ItemStack stack) {
        return LobbyParkourItems.action(plugin, stack);
    }

    public CompletableFuture<List<ParkourLeaderboardEntry>> top(int limit) {
        if (leaderboard == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return leaderboard.top(config.lobbyParkourCourseId(), limit);
    }

    public boolean tryUseHotbarItem(Player player) {
        Run run = runs.get(player.getUniqueId());
        if (run == null) {
            return false;
        }
        return run.tryUseHotbarItem();
    }

    public void refreshConfiguredWorld() {
        String worldName = definition().fallbackWorld();
        if (!worldName.isBlank()) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                refresh(world);
            }
        }
    }

    /** Spawn the visual model and its touch hitbox in a permanent lobby or a freshly loaded clone. */
    public void refresh(World world) {
        removeTriggers(world);
        LobbyParkourDefinition definition = definition();
        if (definition.goal() != null) {
            spawnTrigger(world, definition.goal(), GOAL);
        }
    }

    public void removeTriggers(World world) {
        for (Entity entity : world.getEntities()) {
            if (entity.getPersistentDataContainer().has(triggerKey, PersistentDataType.STRING)) {
                entity.remove();
            }
        }
    }

    public boolean configureStart(Player player) {
        if (!isStandingOnPressurePlate(player)) {
            return false;
        }
        config.setLobbyParkourStart(player.getWorld().getName(), pressurePlateAtPlayer(player));
        refresh(player.getWorld());
        return true;
    }

    public boolean addCheckpoint(Player player) {
        if (!isStandingOnPressurePlate(player)) {
            return false;
        }
        config.addLobbyParkourCheckpoint(pressurePlateAtPlayer(player));
        return true;
    }

    public void configureGoal(Player player) {
        config.setLobbyParkourGoal(player.getWorld().getName(), LobbyParkourPoint.beneath(player.getLocation()));
        refresh(player.getWorld());
    }

    public void configureLeaderboard(Player player) {
        config.setLobbyParkourLeaderboard(player.getWorld().getName(), LobbyParkourPoint.beneath(player.getLocation()));
        refresh(player.getWorld());
    }

    public void clear(Player player) {
        String oldWorldName = definition().fallbackWorld();
        World oldWorld = Bukkit.getWorld(oldWorldName);
        if (oldWorld != null) {
            removeTriggers(oldWorld);
        }
        config.clearLobbyParkour();
        removeTriggers(player.getWorld());
    }

    public void start(Player player) {
        LobbyParkourDefinition definition = definition();
        if (!definition.isReady() || runs.containsKey(player.getUniqueId())) {
            return;
        }
        ItemStack[] hotbar = new ItemStack[9];
        for (int slot = 0; slot < hotbar.length; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            hotbar[slot] = item == null ? null : item.clone();
            player.getInventory().setItem(slot, null);
        }
        player.getInventory().setItem(0, LobbyParkourItems.restart(plugin, messages));
        player.getInventory().setItem(1, LobbyParkourItems.checkpoint(plugin, messages));
        player.getInventory().setItem(8, LobbyParkourItems.leave(plugin, messages));
        Run run = new Run(hotbar, definition.start(), System.nanoTime());
        runs.put(player.getUniqueId(), run);
        startTimerDisplay(player, run);
        messages.send(player, "parkour.started");
    }

    /** Handles contact with the invisible hitbox paired with the goal visual. */
    public void handleTrigger(Player player, Location location) {
        String trigger = nearbyTrigger(location);
        if (trigger == null) {
            return;
        }
        if (GOAL.equals(trigger)) {
            reachGoal(player);
        }
    }

    public void updateCheckpoint(Player player, LobbyParkourPoint checkpoint) {
        Run run = runs.get(player.getUniqueId());
        if (run == null || checkpoint.equals(run.checkpoint())) {
            return;
        }
        run.setCheckpoint(checkpoint);
        messages.send(player, "parkour.checkpoint-reached");
    }

    public void restart(Player player) {
        Run run = runs.get(player.getUniqueId());
        if (run == null) {
            return;
        }
        cancelLaunch(run);
        cancelTimerDisplay(run);
        run.setGoalReached(false);
        run.restartTimer();
        startTimerDisplay(player, run);
        player.teleport(run.start().teleportLocation(player.getLocation()));
        messages.send(player, "parkour.restarted");
    }

    public void teleportCheckpoint(Player player) {
        Run run = runs.get(player.getUniqueId());
        if (run == null) {
            return;
        }
        player.teleport(run.checkpoint().teleportLocation(player.getLocation()));
    }

    public void handleSlimeLanding(Player player, Location location) {
        Run run = runs.get(player.getUniqueId());
        if (run == null || !run.goalReached() || run.launchTask() != null) {
            return;
        }
        if (location.clone().subtract(0.0, 0.1, 0.0).getBlock().getType() != Material.SLIME_BLOCK) {
            return;
        }

        player.setVelocity(new Vector(0.0, LAUNCH_VELOCITY, 0.0));
        run.setLaunchTask(plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            private long ticks;

            @Override
            public void run() {
                if (!player.isOnline() || !runs.containsKey(player.getUniqueId())) {
                    cancelLaunch(player.getUniqueId());
                    return;
                }
                if (++ticks > LAUNCH_TIMEOUT_TICKS || player.getVelocity().getY() <= 0.0) {
                    cancelLaunch(player.getUniqueId());
                    LobbyParkourPoint leaderboard = definition().leaderboard();
                    if (leaderboard != null) {
                        player.teleport(leaderboard.teleportLocation(player.getLocation()));
                    }
                    finish(player);
                }
            }
        }, 1L, 1L));
    }

    public void finish(Player player) {
        if (stop(player)) {
            messages.send(player, "parkour.finished");
        }
    }

    public void leave(Player player) {
        if (stop(player)) {
            messages.send(player, "parkour.left");
        }
    }

    public void stopSilently(Player player) {
        stop(player);
    }

    public void shutdown() {
        for (Run run : runs.values()) {
            cancelLaunch(run);
            cancelTimerDisplay(run);
        }
        runs.clear();
        for (World world : Bukkit.getWorlds()) {
            removeTriggers(world);
        }
    }

    private void reachGoal(Player player) {
        Run run = runs.get(player.getUniqueId());
        if (run == null || run.goalReached()) {
            return;
        }
        long completionTimeMs = run.stopTimer();
        cancelTimerDisplay(run);
        run.setGoalReached(true);
        recordCompletion(player, completionTimeMs);
        messages.send(player, "parkour.goal-reached");
    }

    private void recordCompletion(Player player, long completionTimeMs) {
        if (leaderboard == null) {
            return;
        }
        leaderboard.submit(
                config.lobbyParkourCourseId(),
                player.getUniqueId(),
                player.getName(),
                completionTimeMs
        ).whenComplete((submission, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not save parkour result for " + player.getUniqueId(), error);
                return;
            }
            if (!submission.personalBest()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    messages.send(player, "parkour.personal-best", "time", formatTime(completionTimeMs));
                }
            });
        });
    }

    private boolean stop(Player player) {
        Run run = runs.remove(player.getUniqueId());
        if (run == null) {
            return false;
        }
        cancelLaunch(run);
        cancelTimerDisplay(run);
        player.sendActionBar(Component.empty());
        for (int slot = 0; slot < run.hotbar().length; slot++) {
            player.getInventory().setItem(slot, run.hotbar()[slot]);
        }
        return true;
    }

    private void cancelLaunch(UUID playerId) {
        Run run = runs.get(playerId);
        if (run == null) {
            return;
        }
        cancelLaunch(run);
    }

    private static void cancelLaunch(Run run) {
        BukkitTask launchTask = run.launchTask();
        if (launchTask != null) {
            launchTask.cancel();
            run.setLaunchTask(null);
        }
    }

    private void startTimerDisplay(Player player, Run run) {
        run.setTimerTask(plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || runs.get(player.getUniqueId()) != run || run.timerStopped()) {
                cancelTimerDisplay(run);
                return;
            }
            player.sendActionBar(messages.get("parkour.timer", "time", formatTime(run.elapsedMs())));
        }, 1L, 1L));
    }

    private static void cancelTimerDisplay(Run run) {
        BukkitTask timerTask = run.timerTask();
        if (timerTask != null) {
            timerTask.cancel();
            run.setTimerTask(null);
        }
    }

    private void spawnTrigger(World world, LobbyParkourPoint point, String trigger) {
        Location at = point.teleportLocation(new Location(world, 0, 0, 0));
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> meta.setItemModel(new NamespacedKey("mcparty", "parkour_" + trigger)));
        Location visualAt = at.clone().subtract(0.0, 1.0, 0.0);
        world.spawn(visualAt, ArmorStand.class, entity -> {
            entity.setInvisible(true);
            entity.setGravity(false);
            entity.setMarker(true);
            entity.setBasePlate(false);
            entity.getEquipment().setHelmet(item, true);
            entity.getPersistentDataContainer().set(triggerKey, PersistentDataType.STRING, trigger);
        });
        world.spawn(at, Interaction.class, interaction -> {
            interaction.setInteractionWidth(1.0f);
            interaction.setInteractionHeight(2.0f);
            interaction.setResponsive(false);
            interaction.getPersistentDataContainer().set(triggerKey, PersistentDataType.STRING, trigger);
        });
    }

    private String nearbyTrigger(Location location) {
        Collection<Entity> nearby = location.getWorld().getNearbyEntities(
                location, TRIGGER_RANGE, 1.2, TRIGGER_RANGE
        );
        for (Entity entity : nearby) {
            if (!(entity instanceof Interaction)) {
                continue;
            }
            String trigger = entity.getPersistentDataContainer().get(triggerKey, PersistentDataType.STRING);
            if (trigger != null) {
                return trigger;
            }
        }
        return null;
    }

    private static boolean isStandingOnPressurePlate(Player player) {
        return Tag.PRESSURE_PLATES.isTagged(player.getLocation().getBlock().getType());
    }

    private static LobbyParkourPoint pressurePlateAtPlayer(Player player) {
        Location location = player.getLocation();
        return new LobbyParkourPoint(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static String formatTime(long milliseconds) {
        return String.format(Locale.ROOT, "%.3fs", milliseconds / 1000.0);
    }

    private static final class Run {
        private final ItemStack[] hotbar;
        private final LobbyParkourPoint start;
        private long startedAtNanos;
        private long stoppedAtNanos;
        private boolean timerStopped;
        private LobbyParkourPoint checkpoint;
        private boolean goalReached;
        private BukkitTask launchTask;
        private BukkitTask timerTask;
        private long nextHotbarItemUseNanos;

        private Run(ItemStack[] hotbar, LobbyParkourPoint start, long startedAtNanos) {
            this.hotbar = hotbar;
            this.start = start;
            this.startedAtNanos = startedAtNanos;
            this.checkpoint = start;
        }

        private ItemStack[] hotbar() { return hotbar; }
        private LobbyParkourPoint checkpoint() { return checkpoint; }
        private LobbyParkourPoint start() { return start; }
        private boolean goalReached() { return goalReached; }
        private BukkitTask launchTask() { return launchTask; }
        private BukkitTask timerTask() { return timerTask; }
        private boolean timerStopped() { return timerStopped; }
        private boolean tryUseHotbarItem() {
            long now = System.nanoTime();
            if (now < nextHotbarItemUseNanos) {
                return false;
            }
            nextHotbarItemUseNanos = now + HOTBAR_ITEM_COOLDOWN_NANOS;
            return true;
        }
        private long stopTimer() {
            if (!timerStopped) {
                stoppedAtNanos = System.nanoTime();
                timerStopped = true;
            }
            return elapsedMs();
        }
        private long elapsedMs() {
            long endNanos = timerStopped ? stoppedAtNanos : System.nanoTime();
            return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(endNanos - startedAtNanos));
        }
        private void restartTimer() {
            startedAtNanos = System.nanoTime();
            stoppedAtNanos = 0L;
            timerStopped = false;
        }
        private void setCheckpoint(LobbyParkourPoint checkpoint) { this.checkpoint = checkpoint; }
        private void setGoalReached(boolean goalReached) { this.goalReached = goalReached; }
        private void setLaunchTask(BukkitTask launchTask) { this.launchTask = launchTask; }
        private void setTimerTask(BukkitTask timerTask) { this.timerTask = timerTask; }
    }
}
