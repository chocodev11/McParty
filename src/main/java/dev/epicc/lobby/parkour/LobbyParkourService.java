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

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LobbyParkourService {

    private static final String GOAL = "goal";
    private static final double TRIGGER_RANGE = 0.9;
    private static final double LAUNCH_VELOCITY = 5.0;
    private static final long LAUNCH_TIMEOUT_TICKS = 160L;

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final MessageService messages;
    private final NamespacedKey triggerKey;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    public LobbyParkourService(JavaPlugin plugin, PluginConfig config, MessageService messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
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
        runs.put(player.getUniqueId(), new Run(hotbar, definition.start()));
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
        for (UUID playerId : runs.keySet()) {
            cancelLaunch(playerId);
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
        run.setGoalReached(true);
        messages.send(player, "parkour.goal-reached");
    }

    private boolean stop(Player player) {
        Run run = runs.remove(player.getUniqueId());
        if (run == null) {
            return false;
        }
        BukkitTask launchTask = run.launchTask();
        if (launchTask != null) {
            launchTask.cancel();
        }
        for (int slot = 0; slot < run.hotbar().length; slot++) {
            player.getInventory().setItem(slot, run.hotbar()[slot]);
        }
        return true;
    }

    private void cancelLaunch(UUID playerId) {
        Run run = runs.get(playerId);
        if (run == null || run.launchTask() == null) {
            return;
        }
        run.launchTask().cancel();
        run.setLaunchTask(null);
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

    private static final class Run {
        private final ItemStack[] hotbar;
        private final LobbyParkourPoint start;
        private LobbyParkourPoint checkpoint;
        private boolean goalReached;
        private BukkitTask launchTask;

        private Run(ItemStack[] hotbar, LobbyParkourPoint start) {
            this.hotbar = hotbar;
            this.start = start;
            this.checkpoint = start;
        }

        private ItemStack[] hotbar() { return hotbar; }
        private LobbyParkourPoint checkpoint() { return checkpoint; }
        private LobbyParkourPoint start() { return start; }
        private boolean goalReached() { return goalReached; }
        private BukkitTask launchTask() { return launchTask; }
        private void setCheckpoint(LobbyParkourPoint checkpoint) { this.checkpoint = checkpoint; }
        private void setGoalReached(boolean goalReached) { this.goalReached = goalReached; }
        private void setLaunchTask(BukkitTask launchTask) { this.launchTask = launchTask; }
    }
}
