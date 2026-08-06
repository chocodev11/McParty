package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import dev.epicc.containment.SlotBoundary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** TNT-floor match with crossbows and a timed Multishot power-up. */
public final class SpleefMinigame implements Minigame, MinigameSession, MatchListener {

    private static final double POWERUP_TOUCH_RADIUS_SQUARED = 2.25;
    private static final int CROSSBOW_QUICK_CHARGE_LEVEL = 3;

    private final int timeoutSeconds;
    private final double fallY;
    private final double spawnRadius;
    private final MinigameArenaSpec arenaSpec;
    private final List<Material> floorMaterials;
    private final List<Integer> coinRewards;
    private final int powerupSpawnSeconds;
    private final int multishotSeconds;
    private final String powerupItemModel;

    private final Map<UUID, Integer> multishotTicks = new HashMap<>();
    private MessageService messages;
    private MatchScope scope;
    private EliminationTracker elimination;
    private SlotBoundary boundary;
    private ItemDisplay activePowerup;
    private int timeoutTicks;
    private int powerupSpawnTicks;

    public SpleefMinigame(
            int timeoutSeconds,
            double fallY,
            double spawnRadius,
            MinigameArenaSpec arenaSpec,
            List<Material> floorMaterials,
            List<Integer> coinRewards,
            int powerupSpawnSeconds,
            int multishotSeconds,
            String powerupItemModel
    ) {
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.fallY = fallY;
        this.spawnRadius = Math.max(0.0, spawnRadius);
        this.arenaSpec = arenaSpec;
        this.floorMaterials = floorMaterials == null || floorMaterials.isEmpty()
                ? List.of(Material.TNT)
                : List.copyOf(floorMaterials);
        this.coinRewards = coinRewards == null || coinRewards.isEmpty()
                ? List.of(10, 7, 5, 3)
                : List.copyOf(coinRewards);
        this.powerupSpawnSeconds = Math.max(1, powerupSpawnSeconds);
        this.multishotSeconds = Math.max(1, multishotSeconds);
        this.powerupItemModel = powerupItemModel == null || powerupItemModel.isBlank()
                ? "tnt_multishot"
                : powerupItemModel;
    }

    @Override
    public String id() {
        return "spleef";
    }

    @Override
    public String displayName() {
        return "TNT Spleef";
    }

    @Override
    public Optional<MinigameArenaSpec> arenaSpec() {
        return Optional.ofNullable(arenaSpec);
    }

    @Override
    public MinigameSession createSession() {
        return new SpleefMinigame(
                timeoutSeconds,
                fallY,
                spawnRadius,
                arenaSpec,
                floorMaterials,
                coinRewards,
                powerupSpawnSeconds,
                multishotSeconds,
                powerupItemModel
        );
    }

    @Override
    public void start(MinigameContext context, Consumer<MinigameResult> done) {
        messages = context.messages();
        scope = MatchScope.open(context, this, done);
        elimination = new EliminationTracker(scope.playerIds(), coinRewards);
        multishotTicks.clear();
        activePowerup = null;

        MinigameArena arena = context.arena().orElse(null);
        if (arena == null) {
            scope.finish(elimination.result());
            return;
        }
        boundary = arena.playArea().boundary();

        if (elimination.aliveCount() == 0) {
            scope.finish(elimination.result());
            return;
        }

        scope.protectFromDamage();
        spreadOnRing(arena.playArea().spawn(), spawnRadius);
        for (Player player : scope.onlinePlayers()) {
            giveCrossbow(player, false);
        }
        scope.broadcast("minigame.spleef-started");

        timeoutTicks = timeoutSeconds * 20;
        powerupSpawnTicks = powerupSpawnSeconds * 20;
        scope.repeating(1L, 1L, this::tick);
    }

    private void spreadOnRing(Location center, double radius) {
        List<Player> players = scope.onlinePlayers();
        if (players.isEmpty()) {
            return;
        }

        double ringRadius = players.size() == 1 ? 0.0 : radius;
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            double angle = 2.0 * Math.PI * i / players.size();
            Location spawn = center.clone().add(
                    Math.cos(angle) * ringRadius,
                    0.0,
                    Math.sin(angle) * ringRadius
            );
            spawn.setDirection(center.toVector().subtract(spawn.toVector()));
            player.teleport(spawn);
        }
    }

    private void giveCrossbow(Player player, boolean multishot) {
        ItemStack crossbow = new ItemStack(Material.CROSSBOW);
        ItemMeta meta = crossbow.getItemMeta();
        meta.displayName(Component.text("Fire Crossbow", NamedTextColor.RED));
        meta.addEnchant(Enchantment.QUICK_CHARGE, CROSSBOW_QUICK_CHARGE_LEVEL, true);
        if (multishot) {
            meta.addEnchant(Enchantment.MULTISHOT, 1, true);
        }
        meta.setUnbreakable(true);
        crossbow.setItemMeta(meta);

        player.getInventory().setItem(0, crossbow);
        player.getInventory().setItem(1, new ItemStack(Material.ARROW, 64));
        player.getInventory().setHeldItemSlot(0);
    }

    private void setCrossbowMultishot(Player player, boolean enabled) {
        ItemStack crossbow = player.getInventory().getItem(0);
        if (crossbow == null || crossbow.getType() != Material.CROSSBOW) {
            giveCrossbow(player, enabled);
            return;
        }

        ItemMeta meta = crossbow.getItemMeta();
        meta.addEnchant(Enchantment.QUICK_CHARGE, CROSSBOW_QUICK_CHARGE_LEVEL, true);
        if (enabled) {
            meta.addEnchant(Enchantment.MULTISHOT, 1, true);
        } else {
            meta.removeEnchant(Enchantment.MULTISHOT);
        }
        crossbow.setItemMeta(meta);
    }

    private void tick() {
        if (elimination.aliveCount() <= 1) {
            finish();
            return;
        }

        for (UUID playerId : elimination.alive()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                elimination.eliminate(playerId);
                multishotTicks.remove(playerId);
                continue;
            }
            if (player.getWorld() != boundary.world() || player.getY() <= fallY) {
                eliminate(player, true);
            }
        }

        if (elimination.aliveCount() <= 1) {
            finish();
            return;
        }

        tickMultishot();
        tickPowerup();

        timeoutTicks--;
        if (timeoutTicks % 20 == 0) {
            int secondsLeft = Math.max(0, timeoutTicks / 20);
            for (Player player : scope.onlinePlayers()) {
                if (elimination.isAlive(player.getUniqueId())) {
                    player.sendActionBar(messages.get(
                            "minigame.spleef-time-left", "seconds", Integer.toString(secondsLeft)
                    ));
                }
            }
        }
        if (timeoutTicks <= 0) {
            finish();
        }
    }

    private void tickMultishot() {
        Iterator<Map.Entry<UUID, Integer>> iterator = multishotTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || !elimination.isAlive(entry.getKey())) {
                iterator.remove();
                continue;
            }

            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                setCrossbowMultishot(player, false);
                iterator.remove();
                continue;
            }

            entry.setValue(remaining);
            if (remaining % 20 == 0) {
                player.sendActionBar(messages.get(
                        "minigame.spleef-multishot", "seconds", Integer.toString(remaining / 20)
                ));
            }
        }
    }

    private void tickPowerup() {
        if (activePowerup != null && !activePowerup.isValid()) {
            activePowerup = null;
        }

        if (activePowerup != null) {
            Location powerupLocation = activePowerup.getLocation();
            for (Player player : scope.onlinePlayers()) {
                if (!elimination.isAlive(player.getUniqueId())
                        || player.getWorld() != powerupLocation.getWorld()
                        || player.getLocation().distanceSquared(powerupLocation) > POWERUP_TOUCH_RADIUS_SQUARED) {
                    continue;
                }
                collectPowerup(player);
                return;
            }
            return;
        }

        powerupSpawnTicks--;
        if (powerupSpawnTicks <= 0) {
            spawnPowerup();
            powerupSpawnTicks = powerupSpawnSeconds * 20;
        }
    }

    private void spawnPowerup() {
        Block floor = findAvailableFloorBlock();
        if (floor == null) {
            return;
        }

        Location location = floor.getLocation().add(0.5, 1.25, 0.5);
        ItemStack item = new ItemStack(Material.TNT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Multishot", NamedTextColor.GOLD));
        meta.setItemModel(new NamespacedKey("mcparty", powerupItemModel));
        item.setItemMeta(meta);

        World world = floor.getWorld();
        activePowerup = world.spawn(location, ItemDisplay.class, display -> {
            display.setItemStack(item);
            display.setBillboard(Display.Billboard.CENTER);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setInterpolationDuration(1);
            display.setTeleportDuration(0);
            display.setGlowing(true);
            display.setPersistent(false);
            display.setShadowRadius(0f);
            display.setViewRange(32f);
        });
        activePowerup.setRotation(0f, 0f);
        scope.broadcast("minigame.spleef-powerup-spawned");
    }

    private Block findAvailableFloorBlock() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        World world = boundary.world();
        for (int attempt = 0; attempt < 96; attempt++) {
            int x = random.nextInt(boundary.minX(), boundary.maxX() + 1);
            int z = random.nextInt(boundary.minZ(), boundary.maxZ() + 1);
            for (int y = boundary.maxY(); y >= boundary.minY(); y--) {
                Block block = world.getBlockAt(x, y, z);
                if (floorMaterials.contains(block.getType())
                        && block.getRelative(BlockFace.UP).getType().isAir()) {
                    return block;
                }
            }
        }
        return null;
    }

    private void collectPowerup(Player player) {
        setCrossbowMultishot(player, true);
        multishotTicks.put(player.getUniqueId(), multishotSeconds * 20);
        removePowerup();
        scope.broadcast(
                "minigame.spleef-powerup-collected",
                MessageService.ph("player", player.getName()),
                MessageService.ph("seconds", multishotSeconds)
        );
    }

    @Override
    public void onBlockBreak(BlockBreakEvent event) {
        event.setCancelled(true);
    }

    @Override
    public void onProjectileHit(Player shooter, ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow)) {
            return;
        }
        Block block = event.getHitBlock();
        if (block == null || !isBreakable(shooter, block)) {
            return;
        }

        BlockData brokenData = block.getBlockData();
        block.setType(Material.AIR, false);
        Location effectLocation = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();
        world.spawnParticle(Particle.BLOCK, effectLocation, 15, 0.3, 0.3, 0.3, 0.0, brokenData);
        world.playSound(effectLocation, Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f);
    }

    private boolean isBreakable(Player player, Block block) {
        return elimination.isAlive(player.getUniqueId())
                && boundary.isInside(block.getLocation())
                && floorMaterials.contains(block.getType());
    }

    @Override
    public void onDropItem(PlayerDropItemEvent event) {
        event.setCancelled(true);
    }

    @Override
    public void onQuit(Player player) {
        multishotTicks.remove(player.getUniqueId());
        elimination.eliminate(player.getUniqueId());
        if (elimination.aliveCount() <= 1) {
            finish();
        }
    }

    private void eliminate(Player player, boolean announce) {
        if (!elimination.eliminate(player.getUniqueId())) {
            return;
        }
        multishotTicks.remove(player.getUniqueId());
        scope.spectate(player.getUniqueId());
        if (announce) {
            scope.broadcast(
                    "minigame.spleef-eliminated",
                    MessageService.ph("player", player.getName())
            );
        }
    }

    private void removePowerup() {
        if (activePowerup == null) {
            return;
        }
        if (activePowerup.isValid()) {
            activePowerup.remove();
        }
        activePowerup = null;
    }

    private void finish() {
        removePowerup();
        multishotTicks.clear();
        scope.finish(elimination.result());
    }

    @Override
    public void cancel() {
        removePowerup();
        multishotTicks.clear();
        if (scope != null) {
            scope.close();
        }
    }
}
