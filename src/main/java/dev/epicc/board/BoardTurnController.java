package dev.epicc.board;

import dev.epicc.board.dice.DicePresenter;
import dev.epicc.config.MessageService;
import dev.epicc.minigame.MinigameManager;
import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyPlayer;
import dev.epicc.party.PartyState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BoardTurnController {

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final MinigameManager minigameManager;
    private final Dice dice;
    private final DicePresenter dicePresenter;
    private final PathHopMover pathHopMover;

    private PartyInstance instance;
    private int turnIndex;
    private int turnsTakenThisRound;
    private boolean waitingForRoll;
    private boolean inMinigame;
    private boolean moving;
    private UUID activeRoller;

    public BoardTurnController(
            JavaPlugin plugin,
            MessageService messages,
            MinigameManager minigameManager,
            Dice dice,
            DicePresenter dicePresenter,
            PathHopMover pathHopMover
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.minigameManager = minigameManager;
        this.dice = dice;
        this.dicePresenter = dicePresenter;
        this.pathHopMover = pathHopMover;
    }

    public void attach(PartyInstance instance) {
        this.instance = instance;
        this.turnIndex = 0;
        this.turnsTakenThisRound = 0;
        this.waitingForRoll = false;
        this.inMinigame = false;
        this.moving = false;
        this.activeRoller = null;
    }

    public void startTurns() {
        if (instance == null) {
            return;
        }
        instance.setState(PartyState.PLAYING);
        beginTurn();
    }

    public boolean isWaitingForRoll(UUID playerId) {
        if (!waitingForRoll || inMinigame || instance == null) {
            return false;
        }
        return activeRoller != null && activeRoller.equals(playerId);
    }

    /** Force-settle visual dice (/party roll) or no-op if not your turn. */
    public boolean roll(Player player) {
        if (!isWaitingForRoll(player.getUniqueId())) {
            return false;
        }
        return dicePresenter.trySettle(player);
    }

    public void stop() {
        if (activeRoller != null) {
            dicePresenter.cancel(activeRoller);
            activeRoller = null;
        }
        if (instance != null) {
            for (PartyPlayer pp : instance.players()) {
                pathHopMover.cancel(pp.uuid());
            }
        }
        minigameManager.cancelActive();
        waitingForRoll = false;
        inMinigame = false;
        moving = false;
        instance = null;
    }

    private void beginTurn() {
        if (instance == null || instance.state() != PartyState.PLAYING) {
            return;
        }
        if (instance.round() >= instance.settings().maxTurns()) {
            instance.requestEnd("Max turns reached");
            return;
        }

        PartyPlayer current = currentPlayer();
        if (current == null) {
            instance.requestEnd("No players left");
            return;
        }

        Player player = plugin.getServer().getPlayer(current.uuid());
        if (player == null || !player.isOnline()) {
            skipAbsentTurn();
            return;
        }

        waitingForRoll = true;
        activeRoller = current.uuid();
        instance.broadcast(messages.get("board.turn", "player", current.name()));

        boolean started = dicePresenter.start(player, dice, result -> {
            if (instance == null || instance.state() != PartyState.PLAYING) {
                return;
            }
            applyRoll(player, current, result);
        });
        if (!started) {
            // Should not happen; fall back to instant roll
            applyRoll(player, current, dice.roll());
        }
    }

    private void applyRoll(Player player, PartyPlayer partyPlayer, int roll) {
        waitingForRoll = false;
        activeRoller = null;

        if (partyPlayer == null || instance == null || instance.slot() == null) {
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
        pathHopMover.hop(player, dest, this::afterMove);
    }

    private void afterMove() {
        if (instance == null || instance.state() != PartyState.PLAYING) {
            moving = false;
            return;
        }
        moving = false;
        turnsTakenThisRound++;
        if (turnsTakenThisRound >= instance.playerCount()) {
            turnsTakenThisRound = 0;
            startMinigameThenContinue();
        } else {
            turnIndex = (turnIndex + 1) % instance.playerCount();
            beginTurn();
        }
    }

    private void skipAbsentTurn() {
        waitingForRoll = false;
        activeRoller = null;
        turnsTakenThisRound++;
        turnIndex = (turnIndex + 1) % Math.max(1, instance.playerCount());
        if (turnsTakenThisRound >= instance.playerCount()) {
            turnsTakenThisRound = 0;
            startMinigameThenContinue();
        } else {
            beginTurn();
        }
    }

    private void startMinigameThenContinue() {
        if (instance == null) {
            return;
        }
        inMinigame = true;
        waitingForRoll = false;
        if (activeRoller != null) {
            dicePresenter.cancel(activeRoller);
            activeRoller = null;
        }
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
        minigameManager.runRandom(instance, online, result -> {
            if (instance == null) {
                return;
            }
            result.coinRewards().forEach((uuid, coins) ->
                    instance.player(uuid).ifPresent(pp -> pp.addCoins(coins))
            );
            instance.incrementRound();
            inMinigame = false;

            if (instance.round() >= instance.settings().maxTurns()) {
                instance.requestEnd("Max turns reached");
            } else {
                turnIndex = 0;
                beginTurn();
            }
        });
    }

    private PartyPlayer currentPlayer() {
        if (instance == null || instance.players().isEmpty()) {
            return null;
        }
        List<PartyPlayer> list = instance.players();
        if (turnIndex >= list.size()) {
            turnIndex = 0;
        }
        return list.get(turnIndex);
    }
}
