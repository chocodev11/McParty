package dev.epicc.board;

import dev.epicc.board.dice.DiceHatService;
import dev.epicc.board.dice.DicePresenter;
import dev.epicc.board.dice.DiceSkin;
import dev.epicc.board.dice.DieSpec;
import dev.epicc.board.economy.BoardEconomy;
import dev.epicc.board.economy.EconomySettings;
import dev.epicc.board.economy.PrepItems;
import dev.epicc.board.economy.PrepPhase;
import dev.epicc.board.economy.ShopItem;
import dev.epicc.board.economy.ShopMenu;
import dev.epicc.config.MessageService;
import dev.epicc.minigame.MinigameManager;
import dev.epicc.minigame.MinigameResult;
import dev.epicc.minigame.MinigameRunner;
import dev.epicc.minigame.ArenaTransitions;
import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyPlayer;
import dev.epicc.party.PartyState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Board round loop: optional prep phase (shop, items) → everyone rolls at once (private dice) →
 * when all settled, hop in order → minigame → coins and placement bonus dice.
 * Dice hats appear on settle and are cleared before the minigame starts.
 */
public final class BoardTurnController {

    private static final long MINIGAME_REVEAL_DELAY_TICKS = 60L;

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final MinigameRunner minigameRunner;
    private final Dice dice;
    private final DicePresenter dicePresenter;
    private final DiceHatService diceHats;
    private final PathHopMover pathHopMover;
    private final EconomySettings economySettings;
    private final BoardEconomy economy;

    private PartyInstance instance;
    private boolean waitingForRoll;
    private boolean inMinigame;
    private boolean moving;
    private BukkitTask minigameRevealDelayTask;
    private PrepPhase prep;

    private java.util.function.Consumer<MinigameResult> onRoundEnd;
    private ArenaTransitions arenaTransitions;

    /** Players still expected to finish their roll this round. */
    private final Map<UUID, PartyPlayer> pendingRollers = new LinkedHashMap<>();
    /** Dice each roller is rolling this round, in display order. */
    private final Map<UUID, List<DieSpec>> rollDice = new LinkedHashMap<>();
    /** Settled total per player this round (before hops). */
    private final Map<UUID, Integer> settledRolls = new LinkedHashMap<>();
    private List<UUID> moveQueue = List.of();
    private int moveIndex;

    public BoardTurnController(
            JavaPlugin plugin,
            MessageService messages,
            MinigameManager minigameManager,
            Dice dice,
            DicePresenter dicePresenter,
            DiceHatService diceHats,
            PathHopMover pathHopMover,
            EconomySettings economySettings
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.minigameRunner = minigameManager.createRunner();
        this.dice = dice;
        this.dicePresenter = dicePresenter;
        this.diceHats = diceHats;
        this.pathHopMover = pathHopMover;
        this.economySettings = economySettings;
        this.economy = new BoardEconomy(economySettings);
    }

    public void attach(PartyInstance instance, java.util.function.Consumer<MinigameResult> onRoundEnd,
                       ArenaTransitions arenaTransitions) {
        cancelMinigameRevealDelay();
        stopPrep();
        this.instance = instance;
        this.onRoundEnd = onRoundEnd;
        this.arenaTransitions = arenaTransitions;
        this.waitingForRoll = false;
        this.inMinigame = false;
        this.moving = false;
        this.pendingRollers.clear();
        this.rollDice.clear();
        this.settledRolls.clear();
        this.moveQueue = List.of();
        this.moveIndex = 0;
    }

    public void startTurns() {
        if (instance == null) {
            return;
        }
        beginRound();
    }

    public boolean isWaitingForRoll(UUID playerId) {
        if (!waitingForRoll || inMinigame || instance == null) {
            return false;
        }
        return pendingRollers.containsKey(playerId) && dicePresenter.isRolling(playerId);
    }

    /** Force-settle visual dice (/party roll) or no-op if not rolling. */
    public boolean roll(Player player) {
        if (!isWaitingForRoll(player.getUniqueId())) {
            return false;
        }
        return dicePresenter.trySettle(player);
    }

    /** Prep hotbar item used; stale items outside the prep phase are removed. */
    public Optional<Component> prepAction(Player player, String action) {
        if (prep == null) {
            PrepItems.strip(plugin, player);
            return Optional.of(messages.get("shop.closed"));
        }
        return prep.hotbarAction(player, action);
    }

    public void shopClick(Player player, ShopMenu menu, int slot) {
        if (prep != null) {
            prep.menuClick(player, menu, slot);
        }
    }

    public void onPlayerLeft(Player player) {
        if (prep != null) {
            prep.leave(player);
        }
        economy.remove(player.getUniqueId());
    }

    public void stop() {
        stopPrep();
        for (UUID id : new ArrayList<>(pendingRollers.keySet())) {
            dicePresenter.cancel(id);
        }
        pendingRollers.clear();
        rollDice.clear();
        settledRolls.clear();
        if (instance != null) {
            for (PartyPlayer pp : instance.players()) {
                pathHopMover.cancel(pp.uuid());
                diceHats.clear(pp.uuid());
            }
        }
        minigameRunner.cancel();
        cancelMinigameRevealDelay();
        waitingForRoll = false;
        inMinigame = false;
        moving = false;
        instance = null;
        onRoundEnd = null;
    }

    public void beginRound() {
        if (instance == null || instance.state() != PartyState.PLAYING) {
            return;
        }
        stopPrep();
        pendingRollers.clear();
        rollDice.clear();
        settledRolls.clear();
        moveQueue = List.of();
        moveIndex = 0;

        List<Player> online = onlinePlayers();
        if (online.isEmpty()) {
            instance.requestEnd("No online players");
            return;
        }

        int round = instance.round();
        boolean anyoneCanAct = false;
        for (Player player : online) {
            PartyPlayer partyPlayer = instance.player(player.getUniqueId()).orElse(null);
            if (partyPlayer != null && economy.canAct(partyPlayer, round)) {
                anyoneCanAct = true;
                break;
            }
        }
        if (!anyoneCanAct) {
            startRoll();
            return;
        }
        prep = new PrepPhase(plugin, messages, instance, economy, pathHopMover, round,
                economySettings.prepSeconds(), this::onPrepFinished);
        prep.start(online);
    }

    private void onPrepFinished() {
        prep = null;
        if (instance != null && instance.state() == PartyState.PLAYING) {
            startRoll();
        }
    }

    private void startRoll() {
        List<Player> online = onlinePlayers();
        if (online.isEmpty()) {
            instance.requestEnd("No online players");
            return;
        }

        waitingForRoll = true;
        instance.broadcast(messages.get("board.roll-all"));

        // Register every roller before starting any: an instant settle must not see an empty queue
        for (Player player : online) {
            UUID id = player.getUniqueId();
            instance.player(id).ifPresent(pp -> {
                pendingRollers.put(id, pp);
                rollDice.put(id, economy.takeRollDice(id, dice));
            });
        }
        for (UUID id : new ArrayList<>(pendingRollers.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            List<DieSpec> specs = rollDice.get(id);
            boolean started = player != null && player.isOnline() && dicePresenter.start(player, specs, faces -> {
                if (instance == null || instance.state() != PartyState.PLAYING) {
                    return;
                }
                onPlayerSettled(id, faces);
            });
            if (!started) {
                // Already had a session or passenger failed — apply instant roll
                onPlayerSettled(id, DieSpec.rollAll(specs));
            }
        }
    }

    private void onPlayerSettled(UUID playerId, List<Integer> faces) {
        PartyPlayer partyPlayer = pendingRollers.remove(playerId);
        if (partyPlayer == null) {
            // Duplicate settle callback
            return;
        }
        List<DieSpec> specs = rollDice.getOrDefault(playerId, List.of());
        int total = DieSpec.total(faces);
        settledRolls.put(playerId, total);

        if (instance != null) {
            if (faces.size() == 1) {
                instance.broadcast(messages.get(
                        "board.rolled-face",
                        "player", partyPlayer.name(),
                        "roll", Integer.toString(total)
                ));
            } else {
                instance.broadcast(messages.get(
                        "board.rolled-total",
                        MessageService.ph("player", partyPlayer.name()),
                        MessageService.ph("dice", diceText(specs, faces, false)),
                        MessageService.ph("total", total)
                ));
            }
        }

        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.sendActionBar(
                    Component.empty()
                            .append(diceText(specs, faces, true))
                            .append(Component.space())
                            .append(messages.get("board.rolled-actionbar", "roll", Integer.toString(total)))
            );
        }

        // Wait until everyone who started a roll has finished (presenter already held 1s)
        if (!pendingRollers.isEmpty()) {
            return;
        }

        waitingForRoll = false;
        beginMoves();
    }

    /**
     * Faces as glyphs (action bar) or "3 + 5" coloured numbers (chat, where the 16px glyphs would
     * overlap neighbouring lines). Faces without a glyph fall back to the coloured number.
     */
    private Component diceText(List<DieSpec> specs, List<Integer> faces, boolean glyphs) {
        List<Component> parts = new ArrayList<>(faces.size());
        for (int i = 0; i < faces.size(); i++) {
            DiceSkin skin = i < specs.size() ? specs.get(i).skin() : DiceSkin.STANDARD;
            int face = faces.get(i);
            Optional<String> glyph = glyphs ? skin.assetId(face).filter(messages::hasImage) : Optional.empty();
            parts.add(glyph.map(messages::image).orElseGet(() -> Component.text(face, skin.color())));
        }
        return Component.join(JoinConfiguration.separator(glyphs ? Component.space() : Component.text(" + ")), parts);
    }

    private void beginMoves() {
        if (instance == null || instance.state() != PartyState.PLAYING || instance.slot() == null) {
            return;
        }

        // Hop in party order for anyone who settled
        List<UUID> order = new ArrayList<>();
        for (PartyPlayer pp : instance.players()) {
            if (settledRolls.containsKey(pp.uuid())) {
                order.add(pp.uuid());
            }
        }
        if (order.isEmpty()) {
            scheduleMinigameThenContinue();
            return;
        }

        moveQueue = order;
        moveIndex = 0;
        hopNext();
    }

    private void hopNext() {
        if (instance == null || instance.state() != PartyState.PLAYING || instance.slot() == null) {
            moving = false;
            return;
        }
        if (moveIndex >= moveQueue.size()) {
            moving = false;
            scheduleMinigameThenContinue();
            return;
        }

        UUID id = moveQueue.get(moveIndex);
        Integer roll = settledRolls.get(id);
        PartyPlayer partyPlayer = instance.player(id).orElse(null);
        Player player = plugin.getServer().getPlayer(id);

        if (roll == null || partyPlayer == null || player == null || !player.isOnline()) {
            moveIndex++;
            hopNext();
            return;
        }

        int lastPathIndex = Math.max(0, instance.slot().path().size() - 1);
        int from = partyPlayer.boardIndex();
        int next = nextIndex(from, roll, lastPathIndex);
        if (next == from) {
            instance.broadcast(messages.get(
                    "board.rolled-stay",
                    "player", partyPlayer.name(),
                    "space", Integer.toString(from)
            ));
            moveIndex++;
            hopNext();
            return;
        }
        partyPlayer.setBoardIndex(next);
        Location dest = instance.slot().path().get(next);

        instance.broadcast(messages.get(
                next > from ? "board.rolled" : "board.rolled-back",
                "player", partyPlayer.name(),
                "roll", Integer.toString(Math.abs(next - from)),
                "space", Integer.toString(next)
        ));

        moving = true;
        pathHopMover.hop(player, dest, () -> {
            moveIndex++;
            Player landedPlayer = plugin.getServer().getPlayer(id);
            if (partyPlayer.boardIndex() >= lastPathIndex
                    && landedPlayer != null
                    && landedPlayer.isOnline()) {
                instance.requestEnd("Reached last slot");
                return;
            }
            hopNext();
        });
    }

    /** Board index after moving {@code total} spaces (negative = back), clamped to the path. */
    static int nextIndex(int index, int total, int last) {
        return Math.max(0, Math.min(last, index + total));
    }

    private void scheduleMinigameThenContinue() {
        if (instance == null || instance.state() != PartyState.PLAYING || minigameRevealDelayTask != null) {
            return;
        }
        minigameRevealDelayTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    minigameRevealDelayTask = null;
                    if (instance != null && instance.state() == PartyState.PLAYING) {
                        startMinigameThenContinue();
                    }
                },
                MINIGAME_REVEAL_DELAY_TICKS
        );
    }

    private void startMinigameThenContinue() {
        if (instance == null) {
            return;
        }
        inMinigame = true;
        waitingForRoll = false;
        for (UUID id : new ArrayList<>(pendingRollers.keySet())) {
            dicePresenter.cancel(id);
        }
        pendingRollers.clear();
        // Hats only live between settle and minigame (not during minigame / next roll wait)
        clearPartyHats();

        List<Player> online = onlinePlayers();
        if (online.isEmpty()) {
            inMinigame = false;
            instance.requestEnd("No online players");
            return;
        }

        instance.broadcast(messages.get("board.round-complete"));
        minigameRunner.runRandom(instance, online, arenaTransitions, result -> {
            if (instance == null) {
                return;
            }
            inMinigame = false;
            if (instance.state() == PartyState.PLAYING) {
                applyRewards(result);
            }
            if (onRoundEnd != null) {
                onRoundEnd.accept(result);
            }
        });
    }

    private void applyRewards(MinigameResult result) {
        for (BoardEconomy.Payout payout : economy.payout(instance, result)) {
            if (payout.doubled()) {
                instance.player(payout.playerId()).ifPresent(pp -> instance.broadcast(messages.get(
                        "board.double-coins-paid",
                        "player", pp.name(),
                        "coins", Integer.toString(payout.coins())
                )));
            }
        }
        for (BoardEconomy.BonusDie bonus : economy.awardBonusDice(instance, result)) {
            instance.player(bonus.playerId()).ifPresent(pp -> instance.broadcast(messages.get(
                    "board.bonus-die",
                    MessageService.ph("player", pp.name()),
                    MessageService.ph("place", bonus.place()),
                    MessageService.ph("die", dieName(bonus.skin()))
            )));
        }
    }

    private Component dieName(DiceSkin skin) {
        return ShopItem.forDie(skin)
                .map(item -> messages.get("shop.names." + item.id()))
                .orElseGet(() -> Component.text(skin.name()));
    }

    private void clearPartyHats() {
        if (instance == null) {
            return;
        }
        for (PartyPlayer pp : instance.players()) {
            Player player = plugin.getServer().getPlayer(pp.uuid());
            if (player != null && player.isOnline()) {
                player.sendActionBar(Component.empty());
            }
            diceHats.clear(pp.uuid());
        }
    }

    private List<Player> onlinePlayers() {
        List<Player> online = new ArrayList<>();
        for (PartyPlayer pp : instance.players()) {
            Player p = plugin.getServer().getPlayer(pp.uuid());
            if (p != null && p.isOnline()) {
                online.add(p);
            }
        }
        return online;
    }

    private void stopPrep() {
        if (prep != null) {
            prep.stop();
            prep = null;
        }
    }

    private void cancelMinigameRevealDelay() {
        if (minigameRevealDelayTask != null) {
            minigameRevealDelayTask.cancel();
            minigameRevealDelayTask = null;
        }
    }
}
