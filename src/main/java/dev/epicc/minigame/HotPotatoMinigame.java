package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Hot Potato minigame implementation.
 * <p>
 * Players throw the potato short distances to pass it around before the bomb explodes.
 * Melee attacks do not pass the potato.
 */
public final class HotPotatoMinigame implements Minigame, MinigameSession, MatchListener {

    private final int defaultBombSeconds;
    private final double throwVelocity;
    private final MinigameArenaSpec arenaSpec;
    private final List<Integer> coinRewards;

    private MessageService messages;
    private MatchScope scope;
    private EliminationTracker elimination;
    private NamespacedKey potatoKey;

    private UUID currentHolder;
    private int bombTimerTicks;
    private Projectile activeThrownPotato;
    /** Potato is mid-flight: nobody holds the item until the projectile resolves. */
    private boolean potatoInFlight;

    public HotPotatoMinigame(
            int bombSeconds,
            double throwVelocity,
            MinigameArenaSpec arenaSpec,
            List<Integer> coinRewards
    ) {
        this.defaultBombSeconds = Math.max(5, bombSeconds);
        this.throwVelocity = Math.max(0.3, throwVelocity);
        this.arenaSpec = arenaSpec;
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
    public Optional<MinigameArenaSpec> arenaSpec() {
        return Optional.ofNullable(arenaSpec);
    }

    @Override
    public MinigameSession createSession() {
        return new HotPotatoMinigame(defaultBombSeconds, throwVelocity, arenaSpec, coinRewards);
    }

    @Override
    public void start(MinigameContext context, Consumer<MinigameResult> done) {
        this.messages = context.messages();
        this.potatoKey = new NamespacedKey(context.plugin(), "hot_potato");
        this.scope = MatchScope.open(context, this, done);
        this.elimination = new EliminationTracker(scope.playerIds(), coinRewards);
        this.activeThrownPotato = null;
        this.potatoInFlight = false;

        if (elimination.aliveCount() == 0) {
            scope.finish(elimination.result());
            return;
        }

        scope.protectFromDamage();
        givePotatoTo(elimination.randomAlive());
        scope.broadcast("minigame.hot-potato-started");

        bombTimerTicks = defaultBombSeconds * 20;
        scope.repeating(1L, 1L, this::tickBomb);
    }

    @Override
    public void cancel() {
        if (scope == null) {
            return;
        }
        clearThrownPotato();
        scope.close();
    }

    private void tickBomb() {
        if (elimination.aliveCount() <= 1) {
            finish();
            return;
        }

        bombTimerTicks--;

        // Projectile died without a hit event (despawn / world unload) — give the potato back
        if (potatoInFlight && (activeThrownPotato == null || !activeThrownPotato.isValid())) {
            activeThrownPotato = null;
            givePotatoTo(currentHolder != null ? currentHolder : elimination.randomAlive());
        }

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
    }

    private void explodeHolder() {
        if (currentHolder == null) {
            selectRandomHolder();
            return;
        }

        UUID explodedId = currentHolder;
        Player holder = Bukkit.getPlayer(explodedId);
        if (holder != null && holder.isOnline()) {
            playExplosion(holder.getLocation().add(0, 1.0, 0));
            messages.send(holder, "minigame.hot-potato-exploded", "player", holder.getName());
        }

        elimination.eliminate(explodedId);
        scope.spectate(explodedId);
        // A snowball still in the air would hand the potato out after the bomb already went off
        clearThrownPotato();

        scope.broadcast("minigame.hot-potato-exploded",
                MessageService.ph("player", holder != null ? holder.getName() : "A player"));

        if (elimination.aliveCount() <= 1) {
            finish();
            return;
        }
        bombTimerTicks = defaultBombSeconds * 20;
        selectRandomHolder();
    }

    private static void playExplosion(Location loc) {
        World world = loc.getWorld();
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 5, 1.0, 1.0, 1.0, 0.1);
        world.spawnParticle(Particle.EXPLOSION, loc, 25, 1.5, 1.5, 1.5, 0.2);
        world.spawnParticle(Particle.FLASH, loc, 3, 0.5, 0.5, 0.5, 0.0);
        world.spawnParticle(Particle.LAVA, loc, 30, 0.8, 0.8, 0.8, 0.1);
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc, 20, 0.8, 0.8, 0.8, 0.05);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.8f);
        world.playSound(loc, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.5f, 0.9f);
    }

    private void selectRandomHolder() {
        UUID next = elimination.randomAlive();
        if (next == null) {
            finish();
            return;
        }
        givePotatoTo(next);
    }

    private void givePotatoTo(UUID playerId) {
        currentHolder = playerId;
        potatoInFlight = false;
        Player p = playerId != null ? Bukkit.getPlayer(playerId) : null;
        if (p != null && p.isOnline()) {
            p.getInventory().clear();
            p.getInventory().setItem(0, createPotatoItem("hot_potato"));
            p.getInventory().setHeldItemSlot(0);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);
        }
    }

    /** {@code modelId} selects the flat in-hand model or the 3D thrown variant. */
    private ItemStack createPotatoItem(String modelId) {
        ItemStack item = new ItemStack(Material.BAKED_POTATO);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Hot Potato", NamedTextColor.RED, TextDecoration.BOLD));
            meta.getPersistentDataContainer().set(potatoKey, PersistentDataType.BYTE, (byte) 1);
            meta.setItemModel(new NamespacedKey("mcparty", modelId));
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

    private boolean isHolder(Player player) {
        return !potatoInFlight
                && elimination.isAlive(player.getUniqueId())
                && Objects.equals(player.getUniqueId(), currentHolder);
    }

    @Override
    public void onInteract(PlayerInteractEvent event) {
        if (!isHolder(event.getPlayer()) || !isPotatoItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        throwPotato(event.getPlayer());
    }

    @Override
    public void onDropItem(PlayerDropItemEvent event) {
        if (!isHolder(event.getPlayer()) || !isPotatoItem(event.getItemDrop().getItemStack())) {
            return;
        }
        event.setCancelled(true);
        throwPotato(event.getPlayer());
    }

    @Override
    public void onPickupItem(Player player, EntityPickupItemEvent event) {
        if (!elimination.isAlive(player.getUniqueId()) || !isPotatoItem(event.getItem().getItemStack())) {
            return;
        }
        event.setCancelled(true);
        event.getItem().remove();
        givePotatoTo(player.getUniqueId());
    }

    @Override
    public void onDamageByEntity(Player attacker, EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player hitPlayer)
                || !isHolder(attacker)
                || !elimination.isAlive(hitPlayer.getUniqueId())
                || attacker.getUniqueId().equals(hitPlayer.getUniqueId())) {
            return;
        }
        passTo(attacker, hitPlayer.getUniqueId());
    }

    @Override
    public void onProjectileHit(Player shooter, ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)
                || !snowball.getPersistentDataContainer().has(potatoKey, PersistentDataType.BYTE)) {
            return;
        }

        snowball.remove();
        if (snowball.equals(activeThrownPotato)) {
            activeThrownPotato = null;
            potatoInFlight = false;
        }

        if (event.getHitEntity() instanceof Player hitPlayer && elimination.isAlive(hitPlayer.getUniqueId())) {
            passTo(shooter, hitPlayer.getUniqueId());
            return;
        }

        // Hit a block or missed — return the potato to the thrower.
        passTo(shooter, shooter.getUniqueId());
    }

    private void passTo(Player from, UUID toId) {
        givePotatoTo(toId);
        Player to = Bukkit.getPlayer(toId);
        if (to != null) {
            scope.broadcast("minigame.hot-potato-passed",
                    MessageService.ph("from", from.getName()),
                    MessageService.ph("to", to.getName()));
        }
    }

    @Override
    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        if (elimination.eliminate(id) && Objects.equals(currentHolder, id)) {
            selectRandomHolder();
        }
        if (elimination.aliveCount() <= 1) {
            finish();
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
        projectile.setItem(createPotatoItem("hot_potato_3d"));
        projectile.setVelocity(direction);
        projectile.setPersistent(false);
        projectile.getPersistentDataContainer().set(potatoKey, PersistentDataType.BYTE, (byte) 1);

        activeThrownPotato = projectile;
        potatoInFlight = true;
        shooter.playSound(shooter.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1.0f, 1.0f);
    }

    private void clearThrownPotato() {
        if (activeThrownPotato != null && !activeThrownPotato.isDead()) {
            activeThrownPotato.remove();
        }
        activeThrownPotato = null;
        potatoInFlight = false;
    }

    private void finish() {
        clearThrownPotato();
        scope.finish(elimination.result());
    }
}
