package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import dev.epicc.party.PartyInstance;
import dev.epicc.slime.SlimeWorldService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Hot Potato minigame implementation.
 * <p>
 * Players throw the potato short distances to pass it around before the bomb explodes.
 * Melee attacks do not pass the potato.
 */
public final class HotPotatoMinigame implements Minigame, Listener {

    private final MessageService messages;
    private final SlimeWorldService slimeWorldService;
    private final int defaultBombSeconds;
    private final double throwVelocity;
    private final String slimeTemplate;
    private final List<Integer> coinRewards;

    private MinigameContext context;
    private Consumer<MinigameResult> doneCallback;
    private NamespacedKey potatoKey;

    private final Map<UUID, PlayerStateSnapshot> snapshots = new HashMap<>();
    private final Set<UUID> matchPlayers = new HashSet<>();
    private final Set<UUID> alivePlayers = new HashSet<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();

    private UUID currentHolder;
    private int bombTimerTicks;
    private BukkitTask task;
    private World loadedSlimeWorld;
    private Item activeGroundPotato;
    private Projectile activeThrownPotato;

    public HotPotatoMinigame(
            MessageService messages,
            SlimeWorldService slimeWorldService,
            int bombSeconds,
            double throwVelocity,
            String slimeTemplate,
            List<Integer> coinRewards
    ) {
        this.messages = messages;
        this.slimeWorldService = slimeWorldService;
        this.defaultBombSeconds = Math.max(5, bombSeconds);
        this.throwVelocity = Math.max(0.3, throwVelocity);
        this.slimeTemplate = slimeTemplate;
        this.coinRewards = coinRewards == null || coinRewards.isEmpty() ? List.of(10, 7, 5, 3) : coinRewards;
    }

    @Override
    public String id() {
        return "hot_potato";
    }

    @Override
    public String displayName() {
        return "Hot Potato";
    }

    @Override
    public void start(MinigameContext context, Consumer<MinigameResult> done) {
        cancel();

        this.context = context;
        this.doneCallback = done;
        this.potatoKey = new NamespacedKey(context.plugin(), "hot_potato");

        matchPlayers.clear();
        alivePlayers.clear();
        eliminationOrder.clear();
        snapshots.clear();
        loadedSlimeWorld = null;
        activeGroundPotato = null;
        activeThrownPotato = null;

        List<Player> players = new ArrayList<>(context.onlinePlayers());
        if (players.size() < 2) {
            // Not enough players
            finishWithDefaultResult(players);
            return;
        }

        for (Player p : players) {
            matchPlayers.add(p.getUniqueId());
            alivePlayers.add(p.getUniqueId());
            snapshots.put(p.getUniqueId(), PlayerStateSnapshot.capture(p));
            PlayerStateSnapshot.preparePhase(p);
        }

        // Register listener
        context.plugin().getServer().getPluginManager().registerEvents(this, context.plugin());

        // Check if slime world can be loaded
        PartyInstance instance = context.instance();
        if (slimeWorldService != null && slimeWorldService.isReady() && slimeTemplate != null && !slimeTemplate.isBlank()) {
            Optional<World> worldOpt = slimeWorldService.loadForInstance(instance.id(), slimeTemplate);
            if (worldOpt.isPresent()) {
                loadedSlimeWorld = worldOpt.get();
                Location spawn = loadedSlimeWorld.getSpawnLocation();
                for (Player p : players) {
                    p.teleport(spawn);
                }
            }
        }

        // Select initial holder
        currentHolder = players.get(ThreadLocalRandom.current().nextInt(players.size())).getUniqueId();
        givePotatoTo(currentHolder);

        for (Player p : players) {
            messages.send(p, "minigame.hot-potato-started");
        }

        startBombTimer();
    }

    private void startBombTimer() {
        bombTimerTicks = defaultBombSeconds * 20;

        task = context.plugin().getServer().getScheduler().runTaskTimer(context.plugin(), () -> {
            if (alivePlayers.size() <= 1) {
                finish();
                return;
            }

            bombTimerTicks--;

            // Action bar & visual FX for current holder
            Player holder = currentHolder != null ? Bukkit.getPlayer(currentHolder) : null;
            if (holder != null && holder.isOnline()) {
                int secondsLeft = Math.max(0, (bombTimerTicks + 19) / 20);
                holder.sendActionBar(messages.get("minigame.hot-potato-holding", "seconds", Integer.toString(secondsLeft)));
                Location loc = holder.getLocation().add(0, 1.2, 0);
                holder.getWorld().spawnParticle(Particle.FLAME, loc, 3, 0.2, 0.3, 0.2, 0.02);
                holder.getWorld().spawnParticle(Particle.SMOKE, loc, 2, 0.2, 0.3, 0.2, 0.01);
            }

            if (bombTimerTicks <= 0) {
                explodeHolder();
            }
        }, 1L, 1L);
    }

    private void explodeHolder() {
        if (currentHolder == null) {
            selectRandomHolder();
            return;
        }

        Player holder = Bukkit.getPlayer(currentHolder);
        UUID explodedId = currentHolder;

        if (holder != null && holder.isOnline()) {
            Location loc = holder.getLocation().add(0, 1.0, 0);
            World world = loc.getWorld();
            world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 5, 1.0, 1.0, 1.0, 0.1);
            world.spawnParticle(Particle.EXPLOSION, loc, 25, 1.5, 1.5, 1.5, 0.2);
            world.spawnParticle(Particle.FLASH, loc, 3, 0.5, 0.5, 0.5, 0.0);
            world.spawnParticle(Particle.LAVA, loc, 30, 0.8, 0.8, 0.8, 0.1);
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc, 20, 0.8, 0.8, 0.8, 0.05);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.8f);
            world.playSound(loc, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.5f, 0.9f);
            holder.getInventory().clear();
            holder.setGameMode(GameMode.SPECTATOR);
            messages.send(holder, "minigame.hot-potato-exploded", "player", holder.getName());
        }

        alivePlayers.remove(explodedId);
        eliminationOrder.add(explodedId);

        broadcastMessage("minigame.hot-potato-exploded", "player", holder != null ? holder.getName() : "A player");

        if (alivePlayers.size() <= 1) {
            finish();
        } else {
            bombTimerTicks = defaultBombSeconds * 20;
            selectRandomHolder();
        }
    }

    private void selectRandomHolder() {
        List<UUID> aliveList = new ArrayList<>(alivePlayers);
        if (aliveList.isEmpty()) {
            finish();
            return;
        }
        currentHolder = aliveList.get(ThreadLocalRandom.current().nextInt(aliveList.size()));
        givePotatoTo(currentHolder);
    }

    private void givePotatoTo(UUID playerId) {
        currentHolder = playerId;
        Player p = Bukkit.getPlayer(playerId);
        if (p != null && p.isOnline()) {
            p.getInventory().clear();
            p.getInventory().setItem(0, createPotatoItem());
            p.getInventory().setHeldItemSlot(0);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);
        }
    }

    private ItemStack createPotatoItem() {
        ItemStack item = new ItemStack(Material.BAKED_POTATO);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Hot Potato", NamedTextColor.RED, TextDecoration.BOLD));
            meta.getPersistentDataContainer().set(potatoKey, PersistentDataType.BYTE, (byte) 1);
            meta.setItemModel(new NamespacedKey("mcparty", "hot_potato"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createPotato3DItem() {
        ItemStack item = new ItemStack(Material.BAKED_POTATO);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Hot Potato", NamedTextColor.RED, TextDecoration.BOLD));
            meta.getPersistentDataContainer().set(potatoKey, PersistentDataType.BYTE, (byte) 1);
            meta.setItemModel(new NamespacedKey("mcparty", "hot_potato_3d"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isPotatoItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(potatoKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!alivePlayers.contains(player.getUniqueId())) return;
        if (!Objects.equals(player.getUniqueId(), currentHolder)) return;

        ItemStack item = event.getItem();
        if (isPotatoItem(item)) {
            event.setCancelled(true);
            throwPotato(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!alivePlayers.contains(player.getUniqueId())) return;
        if (!Objects.equals(player.getUniqueId(), currentHolder)) return;

        if (isPotatoItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            throwPotato(player);
        }
    }

    private void throwPotato(Player shooter) {
        if (activeThrownPotato != null && !activeThrownPotato.isDead()) {
            return;
        }

        shooter.getInventory().clear();

        Location eyeLoc = shooter.getEyeLocation();
        Vector direction = eyeLoc.getDirection().normalize().multiply(throwVelocity);

        Snowball projectile = shooter.getWorld().spawn(eyeLoc, Snowball.class);
        projectile.setShooter(shooter);
        projectile.setItem(createPotato3DItem());
        projectile.setVelocity(direction);
        projectile.setPersistent(false);
        projectile.getPersistentDataContainer().set(potatoKey, PersistentDataType.BYTE, (byte) 1);

        activeThrownPotato = projectile;
        shooter.playSound(shooter.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1.0f, 1.0f);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)) return;
        if (!snowball.getPersistentDataContainer().has(potatoKey, PersistentDataType.BYTE)) return;

        snowball.remove();
        if (snowball.equals(activeThrownPotato)) {
            activeThrownPotato = null;
        }

        Player shooter = snowball.getShooter() instanceof Player ? (Player) snowball.getShooter() : null;
        UUID shooterId = shooter != null ? shooter.getUniqueId() : null;

        if (event.getHitEntity() instanceof Player hitPlayer && alivePlayers.contains(hitPlayer.getUniqueId())) {
            givePotatoTo(hitPlayer.getUniqueId());
            if (shooter != null) {
                broadcastMessage("minigame.hot-potato-passed", "from", shooter.getName(), "to", hitPlayer.getName());
            }
        } else {
            // Hit block or missed — pass to nearest alive player
            Location hitLoc = event.getHitBlock() != null
                    ? event.getHitBlock().getLocation().add(0.5, 1.1, 0.5)
                    : snowball.getLocation();

            Player target = findNearestAlivePlayer(hitLoc, shooterId);
            if (target != null) {
                givePotatoTo(target.getUniqueId());
                if (shooter != null) {
                    broadcastMessage("minigame.hot-potato-passed", "from", shooter.getName(), "to", target.getName());
                }
            } else if (shooterId != null) {
                givePotatoTo(shooterId);
            }
        }
    }

    private Player findNearestAlivePlayer(Location targetLoc, UUID shooterId) {
        Player nearestOther = null;
        double nearestOtherDistSq = Double.MAX_VALUE;
        Player nearestAny = null;
        double nearestAnyDistSq = Double.MAX_VALUE;

        for (UUID uuid : alivePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline() && p.getWorld().equals(targetLoc.getWorld())) {
                double distSq = p.getLocation().distanceSquared(targetLoc);
                if (distSq < nearestAnyDistSq) {
                    nearestAnyDistSq = distSq;
                    nearestAny = p;
                }
                if (!uuid.equals(shooterId) && distSq < nearestOtherDistSq) {
                    nearestOtherDistSq = distSq;
                    nearestOther = p;
                }
            }
        }
        return nearestOther != null ? nearestOther : nearestAny;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!alivePlayers.contains(player.getUniqueId())) return;

        if (isPotatoItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
            if (event.getItem().equals(activeGroundPotato)) {
                activeGroundPotato = null;
            }
            givePotatoTo(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && matchPlayers.contains(p.getUniqueId())) {
            event.setCancelled(true); // Disable all damage during minigame
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && matchPlayers.contains(attacker.getUniqueId())) {
            event.setCancelled(true); // Disable melee punching completely
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!matchPlayers.contains(id)) return;

        if (alivePlayers.remove(id)) {
            eliminationOrder.add(id);
            if (Objects.equals(currentHolder, id)) {
                selectRandomHolder();
            }
        }

        if (alivePlayers.size() <= 1) {
            finish();
        }
    }

    private void finish() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);

        if (activeGroundPotato != null && !activeGroundPotato.isDead()) {
            activeGroundPotato.remove();
            activeGroundPotato = null;
        }
        if (activeThrownPotato != null && !activeThrownPotato.isDead()) {
            activeThrownPotato.remove();
            activeThrownPotato = null;
        }

        // Add remaining survivor(s) to elimination order in 1st place
        List<UUID> finalPlacements = new ArrayList<>(alivePlayers);
        Collections.reverse(eliminationOrder);
        finalPlacements.addAll(eliminationOrder);

        MinigameResult result = new MinigameResult();
        for (int i = 0; i < finalPlacements.size(); i++) {
            UUID id = finalPlacements.get(i);
            int place = i + 1;
            int coins = i < coinRewards.size() ? coinRewards.get(i) : 1;
            result.setPlacement(id, place);
            result.setCoins(id, coins);
        }

        // Restore player states & teleport back to board if using slime world
        PartyInstance instance = context != null ? context.instance() : null;
        Location returnLocation = instance != null ? instance.slot().spawn() : null;

        for (UUID id : matchPlayers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                PlayerStateSnapshot.preparePhase(p);
                if (loadedSlimeWorld != null && returnLocation != null) {
                    p.teleport(returnLocation);
                }
            }
        }

        if (loadedSlimeWorld != null && instance != null && slimeWorldService != null) {
            slimeWorldService.unloadForInstance(instance.id());
            loadedSlimeWorld = null;
        }

        if (doneCallback != null) {
            doneCallback.accept(result);
            doneCallback = null;
        }
    }

    private void finishWithDefaultResult(List<Player> players) {
        MinigameResult result = new MinigameResult();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            int coins = i < coinRewards.size() ? coinRewards.get(i) : 1;
            result.setPlacement(p.getUniqueId(), i + 1);
            result.setCoins(p.getUniqueId(), coins);
        }
        if (doneCallback != null) {
            doneCallback.accept(result);
        }
    }

    private void broadcastMessage(String key, String k1, String v1) {
        if (context == null) return;
        for (Player p : context.onlinePlayers()) {
            messages.send(p, key, k1, v1);
        }
    }

    private void broadcastMessage(String key, String k1, String v1, String k2, String v2) {
        if (context == null) return;
        for (Player p : context.onlinePlayers()) {
            messages.send(p, key, k1, v1, k2, v2);
        }
    }

    @Override
    public void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);

        if (activeGroundPotato != null && !activeGroundPotato.isDead()) {
            activeGroundPotato.remove();
            activeGroundPotato = null;
        }
        if (activeThrownPotato != null && !activeThrownPotato.isDead()) {
            activeThrownPotato.remove();
            activeThrownPotato = null;
        }

        for (UUID id : matchPlayers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                PlayerStateSnapshot.preparePhase(p);
            }
        }

        if (loadedSlimeWorld != null && context != null && context.instance() != null && slimeWorldService != null) {
            slimeWorldService.unloadForInstance(context.instance().id());
            loadedSlimeWorld = null;
        }

        matchPlayers.clear();
        alivePlayers.clear();
        snapshots.clear();
    }
}
