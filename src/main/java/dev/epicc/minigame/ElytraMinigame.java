package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import dev.epicc.containment.SlotBoundary;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Cytooxien-style Elytra ring race: ordered rings, center bonuses, and a finish ring. */
public final class ElytraMinigame implements Minigame, MinigameSession, MatchListener {

    private static final double RING_HITBOX_PADDING = 0.4;
    private static final double MOVEMENT_ORDER_EPSILON = 1.0e-6;
    private static final float RING_MODEL_OPENING_RADIUS = 18.0f / 16.0f;
    private static final NamespacedKey RING_ITEM_MODEL = new NamespacedKey("mcparty", "elytra_ring");

    private final ElytraCourse course;
    private final int timeoutSeconds;
    private final int centerBonusCoins;
    private final List<Integer> coinRewards;

    private final Set<UUID> active = new LinkedHashSet<>();
    private final List<UUID> finishOrder = new ArrayList<>();
    private final Map<UUID, Integer> progress = new HashMap<>();
    private final Map<UUID, Integer> bonusCoins = new HashMap<>();
    private final Map<UUID, Integer> partyOrder = new HashMap<>();
    private final List<ItemDisplay> ringDisplays = new ArrayList<>();

    private MessageService messages;
    private MatchScope scope;
    private SlotBoundary boundary;
    private int timeoutTicks;
    private boolean launched;
    private boolean finished;

    public ElytraMinigame(
            ElytraCourse course,
            int timeoutSeconds,
            int centerBonusCoins,
            List<Integer> coinRewards
    ) {
        this.course = course;
        this.timeoutSeconds = Math.max(30, timeoutSeconds);
        this.centerBonusCoins = Math.max(0, centerBonusCoins);
        this.coinRewards = coinRewards == null || coinRewards.isEmpty()
                ? List.of(10, 7, 5, 3)
                : List.copyOf(coinRewards);
    }

    @Override
    public String id() {
        return "elytra_race";
    }

    @Override
    public String displayName() {
        return "Elytra Race";
    }

    @Override
    public java.util.Optional<MinigameArenaSpec> arenaSpec() {
        return course == null || course.arenaSpec() == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(course.arenaSpec());
    }

    @Override
    public MinigameSession createSession() {
        return new ElytraMinigame(course, timeoutSeconds, centerBonusCoins, coinRewards);
    }

    @Override
    public void start(MinigameContext context, Consumer<MinigameResult> done) {
        messages = context.messages();
        scope = MatchScope.open(context, this, done);
        active.clear();
        finishOrder.clear();
        progress.clear();
        bonusCoins.clear();
        partyOrder.clear();
        launched = false;
        finished = false;

        if (course == null || !course.isReady() || context.arena().isEmpty()) {
            cleanupRingDisplays();
            scope.finish(new MinigameResult());
            return;
        }

        boundary = context.arena().orElseThrow().playArea().boundary();
        List<UUID> playerIds = scope.playerIds();
        if (playerIds.isEmpty()) {
            cleanupRingDisplays();
            scope.finish(new MinigameResult());
            return;
        }
        for (int i = 0; i < playerIds.size(); i++) {
            UUID id = playerIds.get(i);
            active.add(id);
            progress.put(id, 0);
            bonusCoins.put(id, 0);
            partyOrder.put(id, i);
        }

        spawnRingDisplays(boundary.world());
        scope.protectFromDamage();
        scope.broadcast("minigame.elytra-started",
                MessageService.ph("rings", Integer.toString(course.rings().size())));
        timeoutTicks = timeoutSeconds * 20;
        scope.later(2L, this::launchPlayers);
        scope.repeating(1L, 1L, this::tick);
    }

    private void launchPlayers() {
        if (scope.closed() || active.isEmpty()) {
            return;
        }
        Location spawn = new Location(
                boundary.world(),
                course.arenaSpec().spawnX(), course.arenaSpec().spawnY(), course.arenaSpec().spawnZ(),
                course.arenaSpec().spawnYaw(), course.arenaSpec().spawnPitch()
        );
        Vector direction = spawn.getDirection().setY(0.0).normalize();
        if (direction.lengthSquared() < 0.0001) {
            direction = new Vector(0.0, 0.0, 1.0);
        }
        Vector side = new Vector(-direction.getZ(), 0.0, direction.getX()).normalize();
        int index = 0;
        int playerCount = active.size();
        for (UUID id : active) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                index++;
                continue;
            }
            double offset = (index - (playerCount - 1) / 2.0) * 1.5;
            Location playerSpawn = spawn.clone().add(side.clone().multiply(offset));
            player.teleport(playerSpawn);
            giveElytra(player);
            player.setGliding(true);
            Vector velocity = direction.clone().multiply(0.65);
            velocity.setY(0.75);
            player.setVelocity(velocity);
            player.playSound(player.getLocation(), Sound.ITEM_ELYTRA_FLYING, 1.0f, 1.0f);
            index++;
        }
        launched = true;
    }

    private void giveElytra(Player player) {
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta meta = elytra.getItemMeta();
        meta.setUnbreakable(true);
        elytra.setItemMeta(meta);
        player.getInventory().setChestplate(elytra);
    }

    private void tick() {
        if (finished || !launched) {
            return;
        }
        timeoutTicks--;

        for (UUID id : new ArrayList<>(active)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || player.isDead()) {
                markOut(id, null);
                continue;
            }
            if (!boundary.isInside(player.getLocation()) || player.getY() <= boundary.minY()) {
                markOut(id, player);
                continue;
            }
            if (!player.isGliding()) {
                markOut(id, player);
                continue;
            }
        }

        if (active.isEmpty() || timeoutTicks <= 0) {
            finish();
            return;
        }
        if (timeoutTicks % 20 == 0) {
            for (Player player : scope.onlinePlayers()) {
                if (active.contains(player.getUniqueId())) {
                    player.sendActionBar(messages.get(
                            "minigame.elytra-time-left",
                            MessageService.ph("seconds", Integer.toString(timeoutTicks / 20))
                    ));
                }
            }
        }
    }

    @Override
    public void onMove(PlayerMoveEvent event) {
        if (finished || !launched || event.isCancelled() || event instanceof PlayerTeleportEvent) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!active.contains(id) || !player.isGliding() || event.getTo() == null) {
            return;
        }
        if (boundary == null || event.getFrom().getWorld() != boundary.world()
                || event.getTo().getWorld() != boundary.world()) {
            return;
        }

        double eyeHeight = player.getEyeHeight();
        Location from = eyePosition(event.getFrom(), eyeHeight);
        Location to = eyePosition(event.getTo(), eyeHeight);
        checkNextRings(id, player, from, to);
    }

    private void checkNextRings(UUID id, Player player, Location from, Location to) {
        double previousCrossing = -1.0;
        while (active.contains(id)) {
            int next = progress.getOrDefault(id, 0);
            if (next >= course.rings().size()) {
                finishPlayer(id, player);
                return;
            }

            ElytraRing ring = course.rings().get(next);
            Optional<ElytraRing.Collision> collision = ring.collision(from, to, RING_HITBOX_PADDING);
            if (collision.isEmpty()) {
                return;
            }
            ElytraRing.Collision crossing = collision.orElseThrow();
            if (crossing.segmentProgress() + MOVEMENT_ORDER_EPSILON < previousCrossing) {
                return;
            }
            previousCrossing = crossing.segmentProgress();

            progress.put(id, next + 1);
            if (ring.centerHit(crossing.point())) {
                bonusCoins.compute(id, (ignored, coins) -> coins + centerBonusCoins);
                messages.send(player, "minigame.elytra-center",
                        MessageService.ph("coins", Integer.toString(centerBonusCoins)));
            }
            messages.send(player, "minigame.elytra-ring",
                    MessageService.ph("ring", Integer.toString(next + 1)),
                    MessageService.ph("total", Integer.toString(course.rings().size())));
            if (next + 1 == course.rings().size()) {
                finishPlayer(id, player);
                return;
            }
        }
    }

    private void spawnRingDisplays(World world) {
        for (ElytraRing ring : course.rings()) {
            Location center = new Location(world, ring.x(), ring.y(), ring.z());
            Vector normal = ring.normalVector();
            Quaternionf rotation = new Quaternionf().rotationTo(
                    new Vector3f(0f, 0f, 1f),
                    new Vector3f((float) normal.getX(), (float) normal.getY(), (float) normal.getZ())
            );
            // The visible opening covers the padded hitbox, so a pass never looks like a miss.
            double visibleRadius = ring.radius() + RING_HITBOX_PADDING;
            float scale = (float) (visibleRadius / RING_MODEL_OPENING_RADIUS);
            float displaySize = (float) Math.max(1.0, visibleRadius * 3.0);
            ItemDisplay display = world.spawn(center, ItemDisplay.class, entity -> {
                entity.setItemStack(ringItem());
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                entity.setDisplayWidth(displaySize);
                entity.setDisplayHeight(displaySize);
                entity.setTransformation(new Transformation(
                        new Vector3f(),
                        new Quaternionf(rotation),
                        new Vector3f(scale, scale, scale),
                        new Quaternionf()
                ));
                entity.setInterpolationDuration(0);
                entity.setTeleportDuration(0);
                entity.setGravity(false);
                entity.setNoPhysics(true);
                entity.setInvulnerable(true);
                entity.setPersistent(false);
                entity.setShadowRadius(0f);
                entity.setShadowStrength(0f);
                entity.setViewRange(128f);
            });
            display.setRotation(0f, 0f);
            ringDisplays.add(display);
        }
    }

    private static ItemStack ringItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(RING_ITEM_MODEL);
        item.setItemMeta(meta);
        return item;
    }

    private static Location eyePosition(Location location, double eyeHeight) {
        return location.clone().add(0.0, eyeHeight, 0.0);
    }

    private void cleanupRingDisplays() {
        for (ItemDisplay display : ringDisplays) {
            if (display.isValid()) {
                display.remove();
            }
        }
        ringDisplays.clear();
    }

    private void finishPlayer(UUID id, Player player) {
        if (!active.remove(id)) {
            return;
        }
        finishOrder.add(id);
        if (player != null && player.isOnline()) {
            messages.send(player, "minigame.elytra-finished",
                    MessageService.ph("place", Integer.toString(finishOrder.size())));
            scope.spectate(id);
        }
    }

    private void markOut(UUID id, Player player) {
        if (!active.remove(id)) {
            return;
        }
        if (player != null && player.isOnline()) {
            messages.send(player, "minigame.elytra-fell");
            scope.spectate(id);
        }
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;

        List<UUID> ranked = new ArrayList<>(finishOrder);
        Comparator<UUID> progressComparator = Comparator
                .comparingInt((UUID id) -> progress.getOrDefault(id, 0)).reversed()
                .thenComparing(Comparator.comparingInt(
                        (UUID id) -> bonusCoins.getOrDefault(id, 0)
                ).reversed())
                .thenComparingInt(id -> partyOrder.getOrDefault(id, Integer.MAX_VALUE));
        List<UUID> unfinished = scope.playerIds().stream()
                .filter(id -> !finishOrder.contains(id))
                .sorted(progressComparator)
                .toList();
        ranked.addAll(unfinished);

        MinigameResult result = new MinigameResult();
        for (int i = 0; i < ranked.size(); i++) {
            UUID id = ranked.get(i);
            int placementCoins = i < coinRewards.size() ? coinRewards.get(i) : 1;
            result.setPlacement(id, i + 1);
            result.setCoins(id, placementCoins + bonusCoins.getOrDefault(id, 0));
        }
        cleanupRingDisplays();
        scope.finish(result);
    }

    @Override
    public void onQuit(Player player) {
        active.remove(player.getUniqueId());
        if (active.isEmpty()) {
            finish();
        }
    }

    @Override
    public void cancel() {
        cleanupRingDisplays();
        if (scope != null) {
            scope.close();
        }
    }
}
