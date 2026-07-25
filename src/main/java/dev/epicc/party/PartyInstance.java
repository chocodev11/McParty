package dev.epicc.party;

import dev.epicc.board.BoardSlot;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class PartyInstance {

    private final UUID id;
    private UUID hostId;
    private final PartySettings settings;
    private final LinkedHashMap<UUID, PartyPlayer> players = new LinkedHashMap<>();
    private final PartyLifecycle lifecycle = new PartyLifecycle();
    private BoardSlot slot;
    private int round;
    private Consumer<PartyInstance> endRequestHandler;
    private BukkitTask countdownTask;
    private CompletableFuture<Optional<World>> worldLoadFuture;
    private PartyPlayArea boardPlayArea;
    private PartyPlayArea activePlayArea;

    public PartyInstance(UUID id, UUID hostId, PartySettings settings) {
        this.id = id;
        this.hostId = hostId;
        this.settings = settings;
    }

    public UUID id() { return id; }
    public UUID hostId() { return hostId; }
    public PartySettings settings() { return settings; }
    public PartyState state() { return lifecycle.state(); }
    public long operationToken() { return lifecycle.operationToken(); }
    public BoardSlot slot() { return slot; }
    public int round() { return round; }

    public long beginStarting() { return lifecycle.beginStarting(); }
    public boolean isStarting(long token) { return lifecycle.isStarting(token); }
    public boolean failStart(long token) { return lifecycle.failStart(token); }
    public boolean beginPlaying(long token) { return lifecycle.beginPlaying(token); }
    public boolean beginEnding() { return lifecycle.beginEnding(); }
    public boolean beginCleanup() { return lifecycle.beginCleanup(); }

    public void setSlot(BoardSlot slot) {
        this.slot = slot;
    }

    public PartyPlayArea boardPlayArea() { return boardPlayArea; }
    public PartyPlayArea activePlayArea() { return activePlayArea; }
    public void setBoardPlayArea(PartyPlayArea playArea) { this.boardPlayArea = playArea; this.activePlayArea = playArea; }
    public void setActivePlayArea(PartyPlayArea playArea) { this.activePlayArea = playArea; }
    public void clearPlayAreas() { boardPlayArea = null; activePlayArea = null; }

    public void setEndRequestHandler(Consumer<PartyInstance> handler) {
        this.endRequestHandler = handler;
    }

    public void setCountdownTask(BukkitTask task) {
        this.countdownTask = task;
    }

    public BukkitTask countdownTask() {
        return countdownTask;
    }

    public void setWorldLoadFuture(CompletableFuture<Optional<World>> future) {
        this.worldLoadFuture = future;
    }

    public CompletableFuture<Optional<World>> worldLoadFuture() {
        return worldLoadFuture;
    }

    public void cancelPendingTasks() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        if (worldLoadFuture != null) {
            worldLoadFuture.cancel(false);
            worldLoadFuture = null;
        }
    }

    public void requestEnd(String reason) {
        if (endRequestHandler != null) {
            endRequestHandler.accept(this);
        }
    }

    public void incrementRound() {
        round++;
    }

    public boolean addPlayer(PartyPlayer player) {
        if (state() != PartyState.WAITING) {
            return false;
        }
        if (players.size() >= settings.maxPlayers()) {
            return false;
        }
        if (players.containsKey(player.uuid())) {
            return false;
        }
        players.put(player.uuid(), player);
        return true;
    }

    public boolean removePlayer(UUID uuid) {
        return players.remove(uuid) != null;
    }

    public boolean transferHostIf(UUID departingHost) {
        if (!hostId.equals(departingHost) || state() != PartyState.WAITING || players.isEmpty()) return false;
        UUID replacement = PartyHostSelector.firstRemaining(players);
        if (replacement == null) return false;
        hostId = replacement;
        return true;
    }

    public Optional<PartyPlayer> player(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    public List<PartyPlayer> players() {
        return Collections.unmodifiableList(new ArrayList<>(players.values()));
    }

    public int playerCount() {
        return players.size();
    }

    public boolean canStart() {
        return state() == PartyState.WAITING && players.size() >= settings.minPlayers();
    }

    public boolean isHost(UUID uuid) {
        return hostId.equals(uuid);
    }

    public void broadcast(Component message) {
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(message);
            }
        }
    }

    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
