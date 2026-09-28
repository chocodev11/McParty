package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import dev.epicc.containment.SlotBoundary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Snow floors stacked in layers. Charged creepers wander in and blow holes (no damage, only
 * knockback) while mystery boxes hand out TNT, wind charges, snowballs and other chaos.
 */
public final class SpleefMinigame implements Minigame, MinigameSession, MatchListener {

    private static final float POWERUP_SCALE = 0.8f;
    private static final int FLOOR_SEARCH_ATTEMPTS = 32;
    private static final int TNT_AMOUNT = 3;
    private static final int TNT_FUSE_TICKS = 40;
    private static final float TNT_POWER = 3f;
    private static final double TNT_THROW_SPEED = 0.9;
    private static final int TNT_COOLDOWN_TICKS = 10;
    private static final int WIND_CHARGE_AMOUNT = 3;
    private static final int SNOWBALL_AMOUNT = 16;
    private static final double SNOWBALL_PUSH = 0.6;
    private static final int SUGAR_RUSH_TICKS = 8 * 20;
    private static final NamespacedKey SUGAR_RUSH_MODEL = new NamespacedKey("mcparty", "sugar_rush");

    private enum PowerUp {
        TNT(3), WIND_CHARGE(3), SNOWBALLS(3), SUGAR_RUSH(2), CREEPER_SURPRISE(2);

        private static final int TOTAL_WEIGHT = 13;
        private final int weight;

        PowerUp(int weight) {
            this.weight = weight;
        }

        static PowerUp random() {
            int roll = ThreadLocalRandom.current().nextInt(TOTAL_WEIGHT);
            for (PowerUp powerUp : values()) {
                roll -= powerUp.weight;
                if (roll < 0) {
                    return powerUp;
                }
            }
            return TNT;
        }
    }

    private final SpleefSettings settings;
    private final List<Integer> coinRewards;

    private final List<Creeper> creepers = new ArrayList<>();
    private final List<PowerUpBox> powerUps = new ArrayList<>();
    private MessageService messages;
    private MatchScope scope;
    private EliminationTracker elimination;
    private SlotBoundary boundary;
    private World world;
    private int timeoutTicks;
    private int powerupSpawnTicks;
    private int creeperSpawnTicks;

    public SpleefMinigame(SpleefSettings settings, List<Integer> coinRewards) {
        this.settings = settings;
        this.coinRewards = coinRewards == null || coinRewards.isEmpty()
                ? List.of(10, 7, 5, 3)
                : List.copyOf(coinRewards);
    }

    @Override
    public String id() {
        return "spleef";
    }

    @Override
    public String displayName() {
        return "Snow Spleef";
    }

    @Override
    public Optional<MinigameArenaSpec> arenaSpec() {
        return Optional.of(settings.arena());
    }

    @Override
    public MinigameSession createSession() {
        return new SpleefMinigame(settings, coinRewards);
    }

    @Override
    public void start(MinigameContext context, Consumer<MinigameResult> done) {
        messages = context.messages();
        scope = MatchScope.open(context, this, done);
        elimination = new EliminationTracker(scope.playerIds(), coinRewards);

        MinigameArena arena = context.arena().orElse(null);
        if (arena == null || elimination.aliveCount() == 0) {
            scope.finish(elimination.result());
            return;
        }
        boundary = arena.playArea().boundary();
        world = boundary.world();

        scope.protectFromDamage();
        spreadOnRing(arena.playArea().spawn(), settings.spawnRadius());
        ItemStack shovel = shovel();
        for (Player player : scope.onlinePlayers()) {
            player.getInventory().setItem(0, shovel.clone());
            player.getInventory().setHeldItemSlot(0);
        }
        scope.broadcast("minigame.spleef-started");

        timeoutTicks = settings.timeoutSeconds() * 20;
        powerupSpawnTicks = settings.powerupSpawnSeconds() * 20;
        creeperSpawnTicks = settings.creeperSpawnSeconds() * 20;
        scope.repeating(1L, 1L, this::tick);
    }

    private void spreadOnRing(Location center, double radius) {
        List<Player> players = scope.onlinePlayers();
        double ringRadius = players.size() == 1 ? 0.0 : radius;
        for (int i = 0; i < players.size(); i++) {
            double angle = 2.0 * Math.PI * i / players.size();
            Location spawn = center.clone().add(Math.cos(angle) * ringRadius, 0.0, Math.sin(angle) * ringRadius);
            spawn.setDirection(center.toVector().subtract(spawn.toVector()));
            players.get(i).teleport(spawn);
        }
    }

    private ItemStack shovel() {
        ItemStack shovel = new ItemStack(Material.DIAMOND_SHOVEL);
        shovel.editMeta(meta -> {
            meta.displayName(itemName("minigame.spleef-item-shovel"));
            // Efficiency V on diamond breaks snow instantly
            meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
            meta.setUnbreakable(true);
        });
        return shovel;
    }

    private void tick() {
        for (UUID playerId : elimination.alive()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                elimination.eliminate(playerId);
            } else if (player.getWorld() != world || player.getY() <= settings.fallY()) {
                eliminate(player);
            }
        }
        if (elimination.aliveCount() <= 1) {
            scope.finish(elimination.result());
            return;
        }

        tickCreepers();
        tickPowerUps();

        timeoutTicks--;
        if (timeoutTicks % 20 == 0) {
            String secondsLeft = Integer.toString(Math.max(0, timeoutTicks / 20));
            for (Player player : scope.onlinePlayers()) {
                if (elimination.isAlive(player.getUniqueId())) {
                    player.sendActionBar(messages.get("minigame.spleef-time-left", "seconds", secondsLeft));
                }
            }
        }
        if (timeoutTicks <= 0) {
            scope.finish(elimination.result());
        }
    }

    private void tickCreepers() {
        int igniteTicks = settings.creeperIgniteSeconds() * 20;
        creepers.removeIf(creeper -> {
            if (!creeper.isValid()) {
                return true;
            }
            if (creeper.getY() <= settings.fallY()) {
                creeper.remove();
                return true;
            }
            // Idle creepers still blow up eventually so the floor keeps shrinking
            if (!creeper.isIgnited() && creeper.getTicksLived() >= igniteTicks) {
                creeper.ignite();
            }
            return false;
        });

        if (--creeperSpawnTicks > 0) {
            return;
        }
        creeperSpawnTicks = settings.creeperSpawnSeconds() * 20;
        if (creepers.size() >= settings.creeperMaxAlive()) {
            return;
        }
        Block floor = randomFloor();
        if (floor != null) {
            spawnCreeper(floor.getLocation().add(0.5, 1.0, 0.5), false);
        }
    }

    private void spawnCreeper(Location location, boolean lit) {
        Creeper creeper = world.spawn(location, Creeper.class, spawned -> {
            spawned.setPowered(true);
            spawned.setExplosionRadius(settings.creeperExplosionRadius());
            spawned.setMaxFuseTicks(settings.creeperFuseTicks());
            // Explosions skip invulnerable mobs, so creepers never chain-kill or drop heads
            spawned.setInvulnerable(true);
            spawned.setPersistent(false);
            spawned.setRemoveWhenFarAway(false);
        });
        if (!creeper.isValid()) {
            return;
        }
        scope.track(creeper);
        creepers.add(creeper);
        world.spawnParticle(Particle.POOF, location, 20, 0.4, 0.6, 0.4, 0.02);
        world.playSound(location, Sound.ENTITY_CREEPER_PRIMED, 0.8f, 1.4f);
        if (lit) {
            creeper.ignite();
        }
    }

    private void tickPowerUps() {
        powerUps.removeIf(box -> !box.active());
        if (--powerupSpawnTicks > 0) {
            return;
        }
        powerupSpawnTicks = settings.powerupSpawnSeconds() * 20;
        if (powerUps.size() >= settings.powerupMaxActive()) {
            return;
        }
        Block floor = randomFloor();
        if (floor == null) {
            return;
        }
        Location center = floor.getLocation().add(0.5, 1.5, 0.5);
        powerUps.add(PowerUpBox.spawn(scope, center, POWERUP_SCALE, 0L, this::collect));
        world.playSound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.6f);
    }

    /** Random exposed floor block on any layer, with two air blocks above it. */
    private Block randomFloor() {
        SpleefPlatform platform = settings.platform();
        int minX = Math.max(boundary.minX(), platform.centerX() - platform.radius());
        int maxX = Math.min(boundary.maxX(), platform.centerX() + platform.radius());
        int minZ = Math.max(boundary.minZ(), platform.centerZ() - platform.radius());
        int maxZ = Math.min(boundary.maxZ(), platform.centerZ() + platform.radius());
        if (minX > maxX || minZ > maxZ) {
            return null;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Block> column = new ArrayList<>();
        for (int attempt = 0; attempt < FLOOR_SEARCH_ATTEMPTS; attempt++) {
            int x = random.nextInt(minX, maxX + 1);
            int z = random.nextInt(minZ, maxZ + 1);
            column.clear();
            for (int y = boundary.maxY() - 2; y >= boundary.minY(); y--) {
                Block block = world.getBlockAt(x, y, z);
                if (settings.floorMaterials().contains(block.getType())
                        && block.getRelative(BlockFace.UP).isEmpty()
                        && block.getRelative(BlockFace.UP, 2).isEmpty()) {
                    column.add(block);
                }
            }
            if (!column.isEmpty()) {
                return column.get(random.nextInt(column.size()));
            }
        }
        return null;
    }

    private void collect(Player player) {
        if (!elimination.isAlive(player.getUniqueId())) {
            return;
        }
        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.8f);
        switch (PowerUp.random()) {
            case TNT -> {
                ItemStack tnt = new ItemStack(Material.TNT, TNT_AMOUNT);
                tnt.editMeta(meta -> meta.displayName(itemName("minigame.spleef-item-tnt")));
                player.getInventory().addItem(tnt);
                broadcastPowerUp("minigame.spleef-powerup-tnt", player);
            }
            case WIND_CHARGE -> {
                player.getInventory().addItem(new ItemStack(Material.WIND_CHARGE, WIND_CHARGE_AMOUNT));
                broadcastPowerUp("minigame.spleef-powerup-wind", player);
            }
            case SNOWBALLS -> {
                player.getInventory().addItem(new ItemStack(Material.SNOWBALL, SNOWBALL_AMOUNT));
                broadcastPowerUp("minigame.spleef-powerup-snowball", player);
            }
            case SUGAR_RUSH -> {
                ItemStack sugar = new ItemStack(Material.SUGAR);
                sugar.editMeta(meta -> {
                    meta.displayName(itemName("minigame.spleef-item-sugar"));
                    meta.setItemModel(SUGAR_RUSH_MODEL);
                });
                player.getInventory().addItem(sugar);
                broadcastPowerUp("minigame.spleef-powerup-sugar", player);
            }
            case CREEPER_SURPRISE -> creeperSurprise(player);
        }
    }

    /** Drops a lit creeper right behind a random opponent (or the collector when playing alone). */
    private void creeperSurprise(Player collector) {
        List<Player> targets = new ArrayList<>();
        for (Player player : scope.onlinePlayers()) {
            if (player != collector && elimination.isAlive(player.getUniqueId())) {
                targets.add(player);
            }
        }
        Player target = targets.isEmpty()
                ? collector
                : targets.get(ThreadLocalRandom.current().nextInt(targets.size()));

        Location behind = target.getLocation();
        Vector back = behind.getDirection().setY(0);
        if (back.lengthSquared() > 1.0E-4) {
            behind.subtract(back.normalize().multiply(1.5));
        }
        spawnCreeper(behind, true);
        scope.broadcast(
                "minigame.spleef-powerup-creeper",
                MessageService.ph("player", collector.getName()),
                MessageService.ph("target", target.getName())
        );
    }

    private void broadcastPowerUp(String key, Player player) {
        scope.broadcast(key, MessageService.ph("player", player.getName()));
    }

    @Override
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        ItemStack hand = event.getItem();
        if (hand == null) {
            return;
        }
        switch (hand.getType()) {
            case TNT -> {
                // Thrown, never placed
                event.setCancelled(true);
                throwTnt(event.getPlayer());
            }
            case SUGAR -> {
                event.setCancelled(true);
                eatSugarRush(event.getPlayer());
            }
            default -> {
            }
        }
    }

    private void throwTnt(Player player) {
        if (!elimination.isAlive(player.getUniqueId()) || player.hasCooldown(Material.TNT)) {
            return;
        }
        player.getInventory().getItemInMainHand().subtract();
        player.setCooldown(Material.TNT, TNT_COOLDOWN_TICKS);

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        TNTPrimed tnt = world.spawn(eye.add(direction.clone().multiply(0.6)), TNTPrimed.class, primed -> {
            primed.setFuseTicks(TNT_FUSE_TICKS);
            primed.setYield(TNT_POWER);
            primed.setSource(player);
            primed.setVelocity(direction.multiply(TNT_THROW_SPEED).add(new Vector(0.0, 0.2, 0.0)));
        });
        scope.track(tnt);
        world.playSound(eye, Sound.ENTITY_TNT_PRIMED, 1.0f, 1.2f);
    }

    private void eatSugarRush(Player player) {
        if (!elimination.isAlive(player.getUniqueId())) {
            return;
        }
        player.getInventory().getItemInMainHand().subtract();
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, SUGAR_RUSH_TICKS, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, SUGAR_RUSH_TICKS, 1));
        Location location = player.getLocation();
        world.playSound(location, Sound.ENTITY_GENERIC_EAT, 1.0f, 1.4f);
        world.spawnParticle(Particle.HAPPY_VILLAGER, location.add(0.0, 1.0, 0.0), 12, 0.4, 0.5, 0.4, 0.0);
    }

    @Override
    public void onEntityExplode(EntityExplodeEvent event) {
        // Blasts only eat the snow floor, drop nothing, and replace the cancelled
        // explosion damage (which also skips vanilla knockback) with our own launch
        event.setYield(0f);
        event.blockList().removeIf(block -> !isFloor(block));

        float power = switch (event.getEntity()) {
            case Creeper creeper -> creeper.getExplosionRadius() * (creeper.isPowered() ? 2f : 1f);
            case TNTPrimed tnt -> tnt.getYield();
            default -> 0f;
        };
        launchPlayers(event.getLocation(), power * 2.0);
    }

    private void launchPlayers(Location center, double reach) {
        if (reach <= 0.0 || settings.knockback() <= 0.0) {
            return;
        }
        Vector origin = center.toVector();
        for (Player player : scope.onlinePlayers()) {
            if (!elimination.isAlive(player.getUniqueId()) || player.getWorld() != center.getWorld()) {
                continue;
            }
            Vector push = player.getLocation().toVector().add(new Vector(0.0, 0.9, 0.0)).subtract(origin);
            double distance = push.length();
            if (distance >= reach) {
                continue;
            }
            double strength = (1.0 - distance / reach) * settings.knockback();
            push = distance < 1.0E-3 ? new Vector(0.0, 1.0, 0.0) : push.multiply(1.0 / distance);
            push.multiply(strength).setY(push.getY() + 0.4 * strength);
            player.setVelocity(player.getVelocity().add(push));
        }
    }

    @Override
    public void onBlockBreak(BlockBreakEvent event) {
        if (!elimination.isAlive(event.getPlayer().getUniqueId()) || !isFloor(event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
    }

    @Override
    public void onProjectileHit(Player shooter, ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball) || !elimination.isAlive(shooter.getUniqueId())) {
            return;
        }

        Entity hitEntity = event.getHitEntity();
        if (hitEntity instanceof Player target && elimination.isAlive(target.getUniqueId())) {
            // Damage protection cancels the hit, and with it vanilla knockback
            Vector push = snowball.getVelocity().setY(0);
            if (push.lengthSquared() > 1.0E-4) {
                push.normalize().multiply(SNOWBALL_PUSH);
            }
            target.setVelocity(target.getVelocity().add(push.setY(0.35)));
            return;
        }

        Block block = event.getHitBlock();
        if (block == null || !isFloor(block)) {
            return;
        }
        BlockData brokenData = block.getBlockData();
        block.setType(Material.AIR, false);
        Location effectLocation = block.getLocation().add(0.5, 0.5, 0.5);
        world.spawnParticle(Particle.BLOCK, effectLocation, 15, 0.3, 0.3, 0.3, 0.0, brokenData);
        world.playSound(effectLocation, brokenData.getSoundGroup().getBreakSound(), 1.0f, 1.0f);
    }

    private boolean isFloor(Block block) {
        return settings.floorMaterials().contains(block.getType()) && boundary.isInside(block.getLocation());
    }

    @Override
    public void onDropItem(PlayerDropItemEvent event) {
        event.setCancelled(true);
    }

    @Override
    public void onQuit(Player player) {
        elimination.eliminate(player.getUniqueId());
        if (elimination.aliveCount() <= 1) {
            scope.finish(elimination.result());
        }
    }

    private void eliminate(Player player) {
        if (!elimination.eliminate(player.getUniqueId())) {
            return;
        }
        scope.spectate(player.getUniqueId());
        scope.broadcast("minigame.spleef-eliminated", MessageService.ph("player", player.getName()));
    }

    private Component itemName(String key) {
        return messages.get(key).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    public void cancel() {
        if (scope != null) {
            scope.close();
        }
    }
}
