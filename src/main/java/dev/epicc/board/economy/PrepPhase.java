package dev.epicc.board.economy;

import dev.epicc.board.BoardSlot;
import dev.epicc.board.PathHopMover;
import dev.epicc.config.MessageService;
import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One round's prep phase before the dice: hotbar (Ready, Shop, held items), shop menus, item use
 * and the countdown. It ends when time runs out, or early once every participant is ready and
 * nobody is still warping. The dice start one tick later so the final Ready click cannot also
 * settle a freshly spawned die.
 */
public final class PrepPhase {

    /** Right-clicking a block can fire the interact event twice. */
    private static final long USE_COOLDOWN_NANOS = 300_000_000L;

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final PartyInstance instance;
    private final BoardEconomy economy;
    private final PathHopMover hops;
    private final int round;
    private final int seconds;
    private final Runnable onFinished;

    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> ready = new HashSet<>();
    private final Map<UUID, Long> nextUse = new HashMap<>();
    private BukkitTask timer;
    private int secondsLeft;
    private boolean finishing;
    private boolean stopped;

    public PrepPhase(
            JavaPlugin plugin,
            MessageService messages,
            PartyInstance instance,
            BoardEconomy economy,
            PathHopMover hops,
            int round,
            int seconds,
            Runnable onFinished
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.instance = instance;
        this.economy = economy;
        this.hops = hops;
        this.round = round;
        this.seconds = seconds;
        this.onFinished = onFinished;
    }

    public void start(List<Player> players) {
        secondsLeft = seconds;
        for (Player player : players) {
            UUID id = player.getUniqueId();
            PartyPlayer partyPlayer = instance.player(id).orElse(null);
            if (partyPlayer == null) {
                continue;
            }
            participants.add(id);
            // Nothing to buy or use: start ready, but the shop can still be browsed
            if (!economy.canAct(partyPlayer, round)) {
                ready.add(id);
            }
            render(player);
        }
        instance.broadcast(messages.get("board.prep-started", "seconds", Integer.toString(seconds)));
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 0L, 20L);
    }

    /** Close menus, remove prep items and stop the countdown. Safe to call more than once. */
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        cancelTimer();
        for (UUID id : participants) {
            Player player = online(id);
            if (player == null) {
                continue;
            }
            // Clear the cursor before closing so no prep item is put back into the inventory
            PrepItems.strip(plugin, player);
            closeMenu(player);
            player.sendActionBar(Component.empty());
        }
    }

    public Optional<Component> hotbarAction(Player player, String action) {
        UUID id = player.getUniqueId();
        if (stopped || finishing || !participants.contains(id)) {
            return Optional.of(messages.get("shop.closed"));
        }
        if (!tryUse(id)) {
            return Optional.empty();
        }
        if (PrepItems.READY.equals(action)) {
            ready.add(id);
            render(player);
            finishIfAllReady();
            return Optional.empty();
        }
        if (PrepItems.SHOP.equals(action)) {
            openShop(player);
            return Optional.empty();
        }
        if (action.startsWith(PrepItems.USE_PREFIX)) {
            return ShopItem.byId(action.substring(PrepItems.USE_PREFIX.length()))
                    .flatMap(item -> useItem(player, item));
        }
        return Optional.empty();
    }

    /** Runs inside the click event: buys, re-renders, never closes the inventory. */
    public void menuClick(Player player, ShopMenu menu, int slot) {
        UUID id = player.getUniqueId();
        if (stopped || finishing || menu.round() != round || !participants.contains(id)) {
            return;
        }
        ShopItem item = menu.itemAt(slot).orElse(null);
        PartyPlayer partyPlayer = instance.player(id).orElse(null);
        if (item == null || partyPlayer == null) {
            return;
        }
        int price = economy.price(id, item);
        BoardEconomy.Purchase purchase = economy.buy(partyPlayer, item, round);
        player.sendMessage(switch (purchase) {
            case OK -> messages.get("shop.bought",
                    MessageService.ph("item", itemName(item)), MessageService.ph("price", price));
            case CLOSED -> messages.get("shop.closed");
            case LIMIT -> messages.get("shop.purchase-limit",
                    "limit", Integer.toString(economy.settings().maxPurchasesPerRound()));
            case NOT_ENOUGH_COINS -> messages.get("shop.not-enough-coins",
                    "price", Integer.toString(price), "coins", Integer.toString(partyPlayer.coins()));
        });
        if (purchase == BoardEconomy.Purchase.OK) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
        }
        menu.render(messages, economy, partyPlayer);
        render(player);
    }

    public void leave(Player player) {
        UUID id = player.getUniqueId();
        if (!participants.remove(id)) {
            return;
        }
        ready.remove(id);
        nextUse.remove(id);
        PrepItems.strip(plugin, player);
        if (player.isOnline()) {
            closeMenu(player);
        }
        finishIfAllReady();
    }

    private void tick() {
        if (secondsLeft <= 0) {
            finish();
            return;
        }
        int readyCount = 0;
        for (UUID id : participants) {
            if (ready.contains(id)) {
                readyCount++;
            }
        }
        for (UUID id : participants) {
            Player player = online(id);
            if (player == null) {
                continue;
            }
            // Re-render after anything that emptied the hotbar (respawn, another plugin)
            if (!PrepItems.action(plugin, player.getInventory().getItem(0)).map(PrepItems.READY::equals).orElse(false)) {
                render(player);
            }
            int coins = instance.player(id).map(PartyPlayer::coins).orElse(0);
            player.sendActionBar(messages.get(
                    "board.prep-actionbar",
                    "seconds", Integer.toString(secondsLeft),
                    "coins", Integer.toString(coins),
                    "ready", Integer.toString(readyCount),
                    "total", Integer.toString(participants.size())
            ));
        }
        secondsLeft--;
        finishIfAllReady();
    }

    private void finishIfAllReady() {
        for (UUID id : participants) {
            if (online(id) != null && (!ready.contains(id) || hops.isHopping(id))) {
                return;
            }
        }
        finish();
    }

    private void finish() {
        if (finishing || stopped) {
            return;
        }
        finishing = true;
        cancelTimer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (stopped) {
                return;
            }
            stop();
            onFinished.run();
        });
    }

    private Optional<Component> useItem(Player player, ShopItem item) {
        UUID id = player.getUniqueId();
        PartyPlayer partyPlayer = instance.player(id).orElse(null);
        if (partyPlayer == null) {
            return Optional.of(messages.get("shop.closed"));
        }
        PartyPlayer target = null;
        if (item == ShopItem.WARP_PIPE) {
            target = warpTarget(partyPlayer);
            if (target == null) {
                return Optional.of(messages.get("shop.warp-no-target"));
            }
        }
        Optional<Component> refused = switch (economy.use(id, item, round)) {
            case OK -> Optional.empty();
            case NOT_OWNED -> Optional.of(messages.get("shop.not-owned"));
            case LOCKED -> Optional.of(messages.get("shop.locked"));
            case LIMIT -> Optional.of(messages.get("shop.use-limit",
                    "limit", Integer.toString(economy.settings().maxUsesPerRound())));
            case ALREADY_ACTIVE -> Optional.of(messages.get("shop.double-coins-active"));
        };
        if (refused.isPresent()) {
            return refused;
        }
        instance.broadcast(messages.get("shop.used",
                MessageService.ph("player", player.getName()), MessageService.ph("item", itemName(item))));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        if (target != null) {
            warp(player, partyPlayer, target);
        }
        render(player);
        return Optional.empty();
    }

    /** A random online opponent standing on another space. */
    private PartyPlayer warpTarget(PartyPlayer self) {
        List<PartyPlayer> targets = new ArrayList<>();
        for (PartyPlayer other : instance.players()) {
            if (!other.uuid().equals(self.uuid()) && other.boardIndex() != self.boardIndex() && online(other.uuid()) != null) {
                targets.add(other);
            }
        }
        return targets.isEmpty() ? null : targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
    }

    private void warp(Player player, PartyPlayer self, PartyPlayer target) {
        BoardSlot slot = instance.slot();
        if (slot == null) {
            return;
        }
        // The index moves now; the hop is only the animation and may be cut short by the roll's hop
        self.setBoardIndex(target.boardIndex());
        Location destination = slot.path().get(target.boardIndex());
        instance.broadcast(messages.get("shop.warp-used",
                "player", player.getName(),
                "target", target.name(),
                "space", Integer.toString(target.boardIndex())));
        hops.hop(player, destination, () -> {
        });
    }

    private void openShop(Player player) {
        PartyPlayer partyPlayer = instance.player(player.getUniqueId()).orElse(null);
        if (partyPlayer == null) {
            return;
        }
        ShopMenu menu = new ShopMenu(instance.id(), round, messages.get("shop.title"));
        menu.render(messages, economy, partyPlayer);
        player.openInventory(menu.getInventory());
    }

    private void closeMenu(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof ShopMenu menu
                && menu.partyId().equals(instance.id())) {
            player.closeInventory();
        }
    }

    private void render(Player player) {
        UUID id = player.getUniqueId();
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(0, PrepItems.ready(plugin, messages, ready.contains(id)));
        inventory.setItem(1, PrepItems.shop(plugin, messages));
        for (ShopItem item : ShopItem.values()) {
            int usable = economy.usable(id, item, round);
            int locked = economy.locked(id, item, round);
            inventory.setItem(item.hotbarSlot(),
                    usable + locked == 0 ? null : PrepItems.held(plugin, messages, item, usable, locked));
        }
    }

    private Component itemName(ShopItem item) {
        return messages.get("shop.names." + item.id());
    }

    private boolean tryUse(UUID id) {
        long now = System.nanoTime();
        Long next = nextUse.get(id);
        if (next != null && now < next) {
            return false;
        }
        nextUse.put(id, now + USE_COOLDOWN_NANOS);
        return true;
    }

    private Player online(UUID id) {
        Player player = plugin.getServer().getPlayer(id);
        return player != null && player.isOnline() ? player : null;
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }
}
