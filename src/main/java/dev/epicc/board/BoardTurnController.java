package dev.epicc.board;

import dev.epicc.minigame.MinigameManager;
import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyPlayer;
import dev.epicc.party.PartyState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class BoardTurnController {

    private final JavaPlugin plugin;
    private final MinigameManager minigameManager;
    private final Dice dice;

    private PartyInstance instance;
    private int turnIndex;
    private int turnsTakenThisRound;
    private boolean waitingForRoll;
    private boolean inMinigame;
    private BukkitTask autoRollTask;

    public BoardTurnController(JavaPlugin plugin, MinigameManager minigameManager, Dice dice) {
        this.plugin = plugin;
        this.minigameManager = minigameManager;
        this.dice = dice;
    }

    public void attach(PartyInstance instance) {
        this.instance = instance;
        this.turnIndex = 0;
        this.turnsTakenThisRound = 0;
        this.waitingForRoll = false;
        this.inMinigame = false;
        cancelAuto();
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
        PartyPlayer current = currentPlayer();
        return current != null && current.uuid().equals(playerId);
    }

    public boolean roll(Player player) {
        if (!isWaitingForRoll(player.getUniqueId())) {
            return false;
        }
        cancelAuto();
        waitingForRoll = false;

        PartyPlayer partyPlayer = currentPlayer();
        if (partyPlayer == null || instance.slot() == null) {
            return false;
        }

        int roll = dice.roll();
        int maxIndex = Math.max(0, instance.slot().path().size() - 1);
        int next = Math.min(partyPlayer.boardIndex() + roll, maxIndex);
        partyPlayer.setBoardIndex(next);

        Location dest = instance.slot().path().get(next);
        if (dest != null) {
            player.teleport(dest);
        }

        instance.broadcast(Component.text(
                "[McParty] " + partyPlayer.name() + " rolled " + roll + " → space " + next,
                NamedTextColor.GOLD
        ));

        turnsTakenThisRound++;
        if (turnsTakenThisRound >= instance.playerCount()) {
            turnsTakenThisRound = 0;
            startMinigameThenContinue();
        } else {
            turnIndex = (turnIndex + 1) % instance.playerCount();
            beginTurn();
        }
        return true;
    }

    public void stop() {
        cancelAuto();
        minigameManager.cancelActive();
        waitingForRoll = false;
        inMinigame = false;
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

        waitingForRoll = true;
        instance.broadcast(Component.text(
                "[McParty] " + current.name() + "'s turn — /party roll",
                NamedTextColor.AQUA
        ));

        // auto-roll after 15s so games do not stall
        autoRollTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!waitingForRoll || instance == null) {
                return;
            }
            Player p = plugin.getServer().getPlayer(current.uuid());
            if (p != null && p.isOnline()) {
                roll(p);
            } else {
                waitingForRoll = false;
                turnsTakenThisRound++;
                turnIndex = (turnIndex + 1) % Math.max(1, instance.playerCount());
                if (turnsTakenThisRound >= instance.playerCount()) {
                    turnsTakenThisRound = 0;
                    startMinigameThenContinue();
                } else {
                    beginTurn();
                }
            }
        }, 15 * 20L);
    }

    private void startMinigameThenContinue() {
        if (instance == null) {
            return;
        }
        inMinigame = true;
        waitingForRoll = false;
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

        instance.broadcast(Component.text("[McParty] Round complete — minigame!", NamedTextColor.LIGHT_PURPLE));
        minigameManager.runDummy(instance, online, result -> {
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

    private void cancelAuto() {
        if (autoRollTask != null) {
            autoRollTask.cancel();
            autoRollTask = null;
        }
    }
}
