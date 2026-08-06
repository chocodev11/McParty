package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import dev.epicc.containment.SlotBoundary;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Disposable-arena Spleef match: break the floor and stay above the fall line. */
public final class SpleefMinigame implements Minigame, MinigameSession, MatchListener {

    private final int timeoutSeconds;
    private final double fallY;
    private final double spawnRadius;
    private final MinigameArenaSpec arenaSpec;
    private final List<Material> floorMaterials;
    private final List<Integer> coinRewards;

    private MessageService messages;
    private MatchScope scope;
    private EliminationTracker elimination;
    private SlotBoundary boundary;
    private int timeoutTicks;

    public SpleefMinigame(
            int timeoutSeconds,
            double fallY,
            double spawnRadius,
            MinigameArenaSpec arenaSpec,
            List<Material> floorMaterials,
            List<Integer> coinRewards
    ) {
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.fallY = fallY;
        this.spawnRadius = Math.max(0.0, spawnRadius);
        this.arenaSpec = arenaSpec;
        this.floorMaterials = floorMaterials == null || floorMaterials.isEmpty()
                ? List.of(Material.SNOW_BLOCK, Material.POWDER_SNOW)
                : List.copyOf(floorMaterials);
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
        return "Spleef";
    }

    @Override
    public Optional<MinigameArenaSpec> arenaSpec() {
        return Optional.ofNullable(arenaSpec);
    }

    @Override
    public MinigameSession createSession() {
        return new SpleefMinigame(
                timeoutSeconds, fallY, spawnRadius, arenaSpec, floorMaterials, coinRewards
        );
    }

    @Override
    public void start(MinigameContext context, Consumer<MinigameResult> done) {
        messages = context.messages();
        scope = MatchScope.open(context, this, done);
        elimination = new EliminationTracker(scope.playerIds(), coinRewards);

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
            giveShovel(player);
        }
        scope.broadcast("minigame.spleef-started");

        timeoutTicks = timeoutSeconds * 20;
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

    private void giveShovel(Player player) {
        ItemStack shovel = new ItemStack(Material.DIAMOND_SHOVEL);
        ItemMeta meta = shovel.getItemMeta();
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        meta.setUnbreakable(true);
        shovel.setItemMeta(meta);
        player.getInventory().setItem(0, shovel);
        player.getInventory().setHeldItemSlot(0);
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

    @Override
    public void onBlockBreak(BlockBreakEvent event) {
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!elimination.isAlive(player.getUniqueId())
                || !boundary.isInside(event.getBlock().getLocation())
                || !floorMaterials.contains(event.getBlock().getType())) {
            return;
        }

        Block block = event.getBlock();
        BlockData brokenData = block.getBlockData();
        block.setType(Material.AIR, false);

        Location effectLocation = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();
        world.spawnParticle(Particle.BLOCK, effectLocation, 15, 0.3, 0.3, 0.3, 0.0, brokenData);
        world.playSound(effectLocation, Sound.BLOCK_SNOW_BREAK, 1.0f, 1.0f);
    }

    @Override
    public void onDropItem(PlayerDropItemEvent event) {
        event.setCancelled(true);
    }

    @Override
    public void onQuit(Player player) {
        elimination.eliminate(player.getUniqueId());
        if (elimination.aliveCount() <= 1) {
            finish();
        }
    }

    private void eliminate(Player player, boolean announce) {
        if (!elimination.eliminate(player.getUniqueId())) {
            return;
        }
        scope.spectate(player.getUniqueId());
        if (announce) {
            scope.broadcast(
                    "minigame.spleef-eliminated",
                    MessageService.ph("player", player.getName())
            );
        }
    }

    private void finish() {
        scope.finish(elimination.result());
    }

    @Override
    public void cancel() {
        if (scope != null) {
            scope.close();
        }
    }
}
