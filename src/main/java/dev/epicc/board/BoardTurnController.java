package dev.epicc.board;

import dev.epicc.board.dice.DiceHatService;
import dev.epicc.board.dice.DicePresenter;
import dev.epicc.config.MessageService;
import dev.epicc.minigame.MinigameManager;
import dev.epicc.minigame.MinigameRunner;
import dev.epicc.minigame.ArenaTransitions;
import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyPlayer;
import dev.epicc.party.PartyState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Board round loop: everyone rolls at once (private dice) → when all settled, hop in order → minigame.
 * Dice hats appear on settle and are cleared before the minigame starts.
 */
public final class BoardTurnController {

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final MinigameRunner minigameRunner;
    private final Dice dice;
    private final DicePresenter dicePresenter;
    private final DiceHatService diceHats;
    private final PathHopMover pathHopMover;

    private PartyInstance instance;
    private boolean waitingForRoll;
    private boolean inMinigame;
    private boolean moving;

    private java.util.function.Consumer<dev.epicc.minigame.MinigameResult> onRoundEnd;
    private ArenaTransitions arenaTransitions;

    /** Players still expected to finish their roll this round. */
    private final Map<UUID, PartyPlayer> pendingRollers = new LinkedHashMap<>();
    /** Settled face per player this round (before hops). */
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
            PathHopMover pathHopMover
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.minigameRunner = minigameManager.createRunner();
        this.dice = dice;
        this.dicePresenter = dicePresenter;
        this.diceHats = diceHats;
        this.pathHopMover = pathHopMover;
    }

    public void attach(PartyInstance instance, java.util.function.Consumer<dev.epicc.minigame.MinigameResult> onRoundEnd,
                       ArenaTransitions arenaTransitions) {
        this.instance = instance;
        this.onRoundEnd = onRoundEnd;
        this.arenaTransitions = arenaTransitions;
        this.waitingForRoll = false;
        this.inMinigame = false;
        this.moving = false;
        this.pendingRollers.clear();
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

    public void stop() {
        for (UUID id : new ArrayList<>(pendingRollers.keySet())) {
            dicePresenter.cancel(id);
        }
        pendingRollers.clear();
        settledRolls.clear();
        if (instance != null) {
            for (PartyPlayer pp : instance.players()) {
                pathHopMover.cancel(pp.uuid());
                diceHats.clear(pp.uuid());
            }
        }
        minigameRunner.cancel();
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
        if (instance.round() >= instance.settings().maxTurns()) {
            instance.requestEnd("Max turns reached");
            return;
        }

        pendingRollers.clear();
        settledRolls.clear();
        moveQueue = List.of();
        moveIndex = 0;

        List<PartyPlayer> online = new ArrayList<>();
        for (PartyPlayer pp : instance.players()) {
            Player p = plugin.getServer().getPlayer(pp.uuid());
            if (p != null && p.isOnline()) {
                online.add(pp);
            }
        }
        if (online.isEmpty()) {
            instance.requestEnd("No online players");
            return;
        }

        waitingForRoll = true;
        instance.broadcast(messages.get("board.roll-all"));

        for (PartyPlayer pp : online) {
            Player player = plugin.getServer().getPlayer(pp.uuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            pendingRollers.put(pp.uuid(), pp);
            boolean started = dicePresenter.start(player, dice, result -> {
                if (instance == null || instance.state() != PartyState.PLAYING) {
                    return;
                }
                onPlayerSettled(pp.uuid(), result);
            });
            if (!started) {
                // Already had a session or passenger failed — apply instant roll
                onPlayerSettled(pp.uuid(), dice.roll());
            }
        }

        if (pendingRollers.isEmpty()) {
            waitingForRoll = false;
            instance.requestEnd("No online players");
        }
    }

    private void onPlayerSettled(UUID playerId, int roll) {
        PartyPlayer partyPlayer = pendingRollers.remove(playerId);
        if (partyPlayer == null) {
            // Duplicate settle callback
            return;
        }
        settledRolls.put(playerId, roll);

        if (instance != null) {
            instance.broadcast(messages.get(
                    "board.rolled-face",
                    "player", partyPlayer.name(),
                    "roll", Integer.toString(roll)
            ));
        }

        // Wait until everyone who started a roll has finished (presenter already held 1s)
        if (!pendingRollers.isEmpty()) {
            return;
        }

        waitingForRoll = false;
        beginMoves();
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
            startMinigameThenContinue();
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
            startMinigameThenContinue();
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

        int maxIndex = Math.max(0, instance.slot().path().size() - 1);
        int next = Math.min(partyPlayer.boardIndex() + roll, maxIndex);
        partyPlayer.setBoardIndex(next);
        Location dest = instance.slot().path().get(next);

        instance.broadcast(messages.get(
                "board.rolled",
                "player", partyPlayer.name(),
                "roll", Integer.toString(roll),
                "space", Integer.toString(next)
        ));

        moving = true;
        pathHopMover.hop(player, dest, () -> {
            moveIndex++;
            hopNext();
        });
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

        List<Player> online = new ArrayList<>();
        for (PartyPlayer pp : instance.players()) {
            Player p = plugin.getServer().getPlayer(pp.uuid());
            if (p != null && p.isOnline()) {
                online.add(p);
            }
        }
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
            if (onRoundEnd != null) {
                onRoundEnd.accept(result);
            }
        });
    }

    private void clearPartyHats() {
        if (instance == null) {
            return;
        }
        for (PartyPlayer pp : instance.players()) {
            diceHats.clear(pp.uuid());
        }
    }
}
