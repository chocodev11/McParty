package dev.epicc.party;

import dev.epicc.board.BoardSlot;
import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.board.BoardTurnController;
import dev.epicc.board.Dice;
import dev.epicc.board.PathHopMover;
import dev.epicc.board.dice.DiceHatService;
import dev.epicc.board.dice.DicePresenter;
import dev.epicc.config.MessageService;
import dev.epicc.config.PluginConfig;
import dev.epicc.hologram.HologramService;
import dev.epicc.minigame.MinigameManager;
import dev.epicc.minigame.ArenaTransitions;
import dev.epicc.minigame.MinigameArena;
import dev.epicc.minigame.PlayerStateSnapshot;
import dev.epicc.player.PlayerSessionService;
import dev.epicc.resourcepack.ResourcePackService;
import dev.epicc.lobby.parkour.LobbyParkourService;
import dev.epicc.seamless.SeamlessWorldChangeService;
import dev.epicc.slime.SlimeWorldService;
import dev.epicc.store.InstanceStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PartyManager {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final MessageService messages;
    private final InstanceStore store;
    private final PlayerSessionService sessions;
    private final BoardSlotRegistry slots;
    private final MinigameManager minigames;
    private final SlimeWorldService slime;
    private final SeamlessWorldChangeService seamless;
    private final DicePresenter dicePresenter;
    private final DiceHatService diceHats;
    private final PathHopMover pathHopMover;
    private final ResourcePackService resourcePacks;
    private final HologramService holograms;
    private final PartyTransitionService transitions;
    private final Map<UUID, BoardTurnController> controllers = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Location>> arenaReturnLocations = new ConcurrentHashMap<>();
    private LobbyParkourService lobbyParkour;


    public PartyManager(
            JavaPlugin plugin,
            PluginConfig config,
            MessageService messages,
            InstanceStore store,
            PlayerSessionService sessions,
            BoardSlotRegistry slots,
            MinigameManager minigames,
            SlimeWorldService slime,
            SeamlessWorldChangeService seamless,
            DicePresenter dicePresenter,
            DiceHatService diceHats,
            PathHopMover pathHopMover,
            ResourcePackService resourcePacks,
            HologramService holograms
    ) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.store = store;
        this.sessions = sessions;
        this.slots = slots;
        this.minigames = minigames;
        this.slime = slime;
        this.seamless = seamless;
        this.dicePresenter = dicePresenter;
        this.diceHats = diceHats;
        this.pathHopMover = pathHopMover;
        this.resourcePacks = resourcePacks;
        this.holograms = holograms;
        this.transitions = new PartyTransitionService(plugin, seamless);
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public MessageService messages() {
        return messages;
    }

    public void setLobbyParkour(LobbyParkourService lobbyParkour) {
        this.lobbyParkour = lobbyParkour;
    }

    public Optional<PartyInstance> instanceOf(UUID playerId) {
        return sessions.instanceOf(playerId).flatMap(store::get);
    }

    public boolean consumeTransitionPermit(Player player, Location destination) {
        return transitions.consumeIfAllowed(player, destination);
    }

    public void leaveLobbyParkour(Player player) {
        if (lobbyParkour == null || !lobbyParkour.isRunning(player.getUniqueId())) {
            return;
        }
        lobbyParkour.stopSilently(player);

        Location destination = lobbySpawn(player);
        transitions.permit(player, destination);
        seamless.teleport(player, destination);
        messages.send(player, "parkour.left");
    }

    public Collection<PartyInstance> all() {
        return store.all();
    }

    public Optional<Component> create(Player host) {
        if (sessions.isInParty(host.getUniqueId())) {
            return Optional.of(messages.get("party.already-in"));
        }
        if (store.size() >= config.maxInstances()) {
            return Optional.of(messages.get("party.limit-reached"));
        }
        PartySettings settings = new PartySettings(
                config.minPlayers(),
                config.maxPlayers(),
                config.maxTurns(),
                config.startingCoins(),
                config.diceMin(),
                config.diceMax()
        );
        PartyInstance instance = new PartyInstance(UUID.randomUUID(), host.getUniqueId(), settings);
        instance.setEndRequestHandler(this::endInternal);
        PartyPlayer pp = new PartyPlayer(host.getUniqueId(), host.getName(), settings.startingCoins());
        instance.addPlayer(pp);
        store.put(instance);
        sessions.bind(host.getUniqueId(), instance.id());
        messages.send(host, "party.created", "id", instance.shortId());
        offerResourcePack(host);
        return Optional.empty();
    }

    public Optional<Component> join(Player player, String shortOrFullId) {
        if (sessions.isInParty(player.getUniqueId())) {
            return Optional.of(messages.get("party.already-in"));
        }
        PartyInstance instance = findByShortId(shortOrFullId).orElse(null);
        if (instance == null) {
            instance = store.all().stream()
                    .filter(i -> i.state() == PartyState.WAITING)
                    .filter(i -> i.playerCount() < i.settings().maxPlayers())
                    .findFirst()
                    .orElse(null);
        }
        if (instance == null) {
            return Optional.of(messages.get("party.no-open"));
        }
        if (instance.state() != PartyState.WAITING) {
            return Optional.of(messages.get("party.already-started"));
        }
        PartyPlayer pp = new PartyPlayer(player.getUniqueId(), player.getName(), instance.settings().startingCoins());
        if (!instance.addPlayer(pp)) {
            return Optional.of(messages.get("party.join-failed"));
        }
        sessions.bind(player.getUniqueId(), instance.id());
        instance.broadcast(messages.get(
                "party.joined",
                "player", player.getName(),
                "count", Integer.toString(instance.playerCount()),
                "max", Integer.toString(instance.settings().maxPlayers())
        ));
        offerResourcePack(player);

        if (instance.playerCount() >= instance.settings().maxPlayers()) {
            Player host = plugin.getServer().getPlayer(instance.hostId());
            if (host != null && host.isOnline()) {
                start(host);
            }
        }

        return Optional.empty();
    }

    private void offerResourcePack(Player player) {
        if (resourcePacks != null && resourcePacks.isReady() && resourcePacks.sendOnParty()) {
            resourcePacks.offerLater(player);
        }
    }

    public Optional<Component> leave(Player player, boolean silent) {
        if (lobbyParkour != null) {
            lobbyParkour.stopSilently(player);
        }
        Optional<PartyInstance> opt = instanceOf(player.getUniqueId());
        if (opt.isEmpty()) {
            return silent ? Optional.empty() : Optional.of(messages.get("party.not-in"));
        }
        PartyInstance instance = opt.get();
        UUID playerId = player.getUniqueId();
        // Drop this player's board visuals without stalling the round: a pending roll is
        // settled (not cancelled) and an active hop still fires its callback.
        dicePresenter.trySettle(player);
        pathHopMover.release(playerId);
        diceHats.clear(playerId);

        if (instance.state() != PartyState.WAITING && player.isOnline()) {
            Location fallback = fallbackLocation();
            transitions.permit(player, fallback);
            seamless.teleport(player, fallback);
        }
        transitions.clear(playerId);
        sessions.unbind(playerId);
        instance.removePlayer(playerId);

        if (!silent) {
            messages.send(player, "party.left-self");
            instance.broadcast(messages.get("party.left-broadcast", "player", player.getName()));
        }

        if (instance.playerCount() == 0) {
            cleanup(instance);
            return Optional.empty();
        }

        if (instance.state() == PartyState.WAITING) {
            if (instance.transferHostIf(player.getUniqueId())) {
                instance.player(instance.hostId()).ifPresent(newHost -> instance.broadcast(
                        messages.get("party.host-transferred", "player", newHost.name())
                ));
            }
            return Optional.empty();
        }

        if (instance.playerCount() < 2) {
            endInternal(instance);
        }
        return Optional.empty();
    }

    public Optional<Component> start(Player requester) {
        PartyInstance instance = instanceOf(requester.getUniqueId()).orElse(null);
        if (instance == null) {
            return Optional.of(messages.get("party.not-in"));
        }
        if (!instance.isHost(requester.getUniqueId()) && !requester.hasPermission("mcparty.admin")) {
            return Optional.of(messages.get("party.only-host-start"));
        }
        if (!instance.canStart()) {
            return Optional.of(messages.get(
                    "party.need-players",
                    "min", Integer.toString(instance.settings().minPlayers())
            ));
        }
        if (instance.state() != PartyState.WAITING) {
            return Optional.of(messages.get("party.already-starting"));
        }

        boolean exclusive = !slime.isReady();
        BoardSlot templateSlot = slots.acquire(instance.id(), exclusive).orElse(null);
        if (templateSlot == null) {
            return Optional.of(messages.get("party.no-board-slot"));
        }

        long token = instance.beginStarting();
        if (token < 0) return Optional.of(messages.get("party.already-starting"));
        beginCountdown(instance, templateSlot, token);

        return Optional.empty();
    }

    private void loadBoardWorld(PartyInstance instance, BoardSlot templateSlot, long token) {
        final UUID instanceId = instance.id();

        if (!slime.isReady()) {
            instance.setSlot(templateSlot);
            beginPlaying(instance, token);
            return;
        }

        final String boardTemplate = templateSlot.slimeTemplate();
        instance.broadcast(messages.get("party.loading-world"));
        java.util.concurrent.CompletableFuture<Optional<World>> future = slime.loadCloneAsync(instanceId, boardTemplate);
        instance.setWorldLoadFuture(future);
        future.thenAccept(boardWorldOpt -> {
            if (!instance.isStarting(token)) {
                boardWorldOpt.ifPresent(world -> slime.unloadWorldForInstance(instanceId, world));
                return;
            }

            if (boardWorldOpt.isEmpty()) {
                instance.broadcast(messages.get("party.slime-load-failed"));
                instance.failStart(token);
                slots.release(instance.id(), templateSlot.id());
                instance.cancelPendingTasks();
                return;
            }

            BoardSlot runtime = templateSlot.forWorld(boardWorldOpt.get());
            instance.setSlot(runtime);
            beginPlaying(instance, token);
        });
    }

    private void beginCountdown(PartyInstance instance, BoardSlot templateSlot, long token) {
        int countdown = config.startCountdownSeconds();
        Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(200));

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int left = countdown;

            @Override
            public void run() {
                if (!instance.isStarting(token)) {
                    instance.cancelPendingTasks();
                    return;
                }
                if (left <= 0) {
                    if (instance.countdownTask() != null) {
                        instance.countdownTask().cancel();
                        instance.setCountdownTask(null);
                    }
                    loadBoardWorld(instance, templateSlot, token);
                    return;
                }
                Title title = Title.title(
                        messages.get("party.countdown-title", "seconds", Integer.toString(left)),
                        messages.get("party.countdown-subtitle", "seconds", Integer.toString(left)),
                        times
                );
                for (PartyPlayer pp : instance.players()) {
                    Player p = plugin.getServer().getPlayer(pp.uuid());
                    if (p != null && p.isOnline()) {
                        p.showTitle(title);
                    }
                }
                left--;
            }
        }, 0L, 20L);
        instance.setCountdownTask(task);
    }


    public Optional<Component> forceEnd(Player admin, String shortId) {
        PartyInstance instance;
        if (shortId == null || shortId.isBlank()) {
            instance = instanceOf(admin.getUniqueId()).orElse(null);
        } else {
            instance = findByShortId(shortId).orElse(null);
        }
        if (instance == null) {
            return Optional.of(messages.get("party.not-found"));
        }
        endInternal(instance);
        return Optional.empty();
    }

    public Optional<Component> roll(Player player) {
        PartyInstance instance = instanceOf(player.getUniqueId()).orElse(null);
        if (instance == null) {
            return Optional.of(messages.get("party.not-in-short"));
        }
        BoardTurnController controller = controllers.get(instance.id());
        if (controller == null) {
            return Optional.of(messages.get("party.game-not-running"));
        }
        if (!controller.roll(player)) {
            return Optional.of(messages.get("party.not-your-turn"));
        }
        return Optional.empty();
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(store.all().stream().map(PartyInstance::id).toList())) {
            store.get(id).ifPresent(this::cleanup);
        }
        controllers.clear();
        dicePresenter.cancelAll();
        diceHats.clearAll();
        pathHopMover.cancelAll();
        sessions.clearAll();
        slots.releaseAll();
        slime.unloadAll();
    }

    private void beginPlaying(PartyInstance instance, long token) {
        if (!instance.beginPlaying(token)) return;
        BoardSlot slot = instance.slot();
        if (slot == null || !slot.isReady()) {
            instance.broadcast(messages.get("party.slot-invalid"));
            cleanup(instance);
            return;
        }
        holograms.closeLobbyScope(instance.id());
        holograms.openPartyScope(instance.id(), slot.world());

        Title startTitle = Title.title(
                messages.get("party.start-title"),
                messages.get("party.start-subtitle"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))
        );
        PartyPlayArea boardArea = new PartyPlayArea(slot.world(), slot.spawn(), slot.boundary());
        instance.setBoardPlayArea(boardArea);
        List<Player> online = new ArrayList<>();
        for (PartyPlayer pp : instance.players()) {
            Player p = plugin.getServer().getPlayer(pp.uuid());
            if (p != null && p.isOnline()) {
                if (lobbyParkour != null) {
                    lobbyParkour.stopSilently(p);
                }
                PlayerStateSnapshot.preparePhase(p);
                online.add(p);
                p.showTitle(startTitle);
            }
        }
        transitions.transition(online, boardArea);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Optional<World> lobbyOpt = slime.getLoadedWorld(instance.id(), config.lobbySlimeTemplate());
            lobbyOpt.ifPresent(lobbyWorld -> slime.unloadWorldForInstance(instance.id(), lobbyWorld));
        }, 20L); // wait 1 second to ensure teleports complete

        BoardTurnController controller = new BoardTurnController(
                plugin,
                messages,
                minigames,
                new Dice(instance.settings().diceMin(), instance.settings().diceMax()),
                dicePresenter,
                diceHats,
                pathHopMover
        );
        controller.attach(instance, result -> {
            if (instance.state() != PartyState.PLAYING) return;
            result.coinRewards().forEach((uuid, coins) -> instance.player(uuid).ifPresent(pp -> pp.addCoins(coins)));
            instance.incrementRound();
            if (instance.round() >= instance.settings().maxTurns()) instance.requestEnd("Max turns reached");
            else Optional.ofNullable(controllers.get(instance.id())).ifPresent(BoardTurnController::beginRound);
        }, new ArenaTransitions(
                arena -> enterArena(instance, arena),
                () -> exitArena(instance)
        ));
        controllers.put(instance.id(), controller);
        // Delay first dice: private ItemDisplay passengers often fail same-tick as slime/world TP
        // (spawn = "first slot", no pad — later rounds on pads already have client tracking).
        final UUID instanceId = instance.id();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (instance.state() != PartyState.PLAYING) {
                return;
            }
            BoardTurnController live = controllers.get(instanceId);
            if (live == null) {
                return;
            }
            live.startTurns();
        }, 10L);
    }

    private void endInternal(PartyInstance instance) {
        if (!instance.beginEnding()) return;
        instance.cancelPendingTasks();
        BoardTurnController controller = controllers.remove(instance.id());
        if (controller != null) {
            controller.stop();
        }
        announcePodium(instance);
        cleanup(instance);
    }

    private void announcePodium(PartyInstance instance) {
        List<PartyPlayer> ranked = new ArrayList<>(instance.players());
        ranked.sort(Comparator
                .comparingInt(PartyPlayer::boardIndex).reversed()
                .thenComparing(Comparator.comparingInt(PartyPlayer::coins).reversed()));
        instance.broadcast(messages.get("party.results-header"));
        for (int i = 0; i < ranked.size(); i++) {
            PartyPlayer pp = ranked.get(i);
            instance.broadcast(messages.get(
                    "party.results-line",
                    "place", Integer.toString(i + 1),
                    "player", pp.name(),
                    "space", Integer.toString(pp.boardIndex()),
                    "coins", Integer.toString(pp.coins())
            ));
        }
    }

    public void cleanup(PartyInstance instance) {
        if (!instance.beginCleanup()) return;
        holograms.closeLobbyScope(instance.id());
        holograms.closePartyScope(instance.id());
        instance.cancelPendingTasks();
        BoardTurnController controller = controllers.remove(instance.id());
        arenaReturnLocations.remove(instance.id());

        if (controller != null) {
            controller.stop();
        }

        for (PartyPlayer pp : instance.players()) {
            dicePresenter.cancel(pp.uuid());
            diceHats.clear(pp.uuid());
            pathHopMover.cancel(pp.uuid());
            transitions.clear(pp.uuid());
            Player player = plugin.getServer().getPlayer(pp.uuid());
            if (player != null && player.isOnline()) {
                if (lobbyParkour != null) {
                    lobbyParkour.stopSilently(player);
                }
                Location fallback = fallbackLocation();
                transitions.permit(player, fallback);
                seamless.teleport(player, fallback);
            }
        }

        sessions.clearInstance(instance.id());

        // Players were explicitly evacuated to the configured persistent fallback above.
        slime.unloadForInstance(instance.id());

        if (instance.slot() != null) {
            // Release the template slot (runtime copy may not be in the registry)
            slots.release(instance.id(), instance.slot().id());
            instance.setSlot(null);
        }
        instance.clearPlayAreas();
        store.remove(instance.id());
    }

    private void enterArena(PartyInstance instance, MinigameArena arena) {
        if (instance.state() != PartyState.PLAYING) return;
        List<Player> players = onlinePlayers(instance);
        Map<UUID, Location> returnLocations = new ConcurrentHashMap<>();
        for (Player player : players) {
            returnLocations.put(player.getUniqueId(), player.getLocation().clone());
        }
        arenaReturnLocations.put(instance.id(), returnLocations);
        instance.setActivePlayArea(arena.playArea());
        transitions.transition(players, arena.playArea());
    }

    private void exitArena(PartyInstance instance) {
        PartyPlayArea board = instance.boardPlayArea();
        if (board == null || instance.state() != PartyState.PLAYING) return;
        instance.setActivePlayArea(board);
        Map<UUID, Location> returnLocations = arenaReturnLocations.remove(instance.id());
        if (returnLocations == null) return;
        for (Player player : onlinePlayers(instance)) {
            Location destination = returnLocations.get(player.getUniqueId());
            if (destination == null || destination.getWorld() != board.world()) {
                destination = board.spawn();
            }
            transitions.permit(player, destination);
            seamless.teleport(player, destination);
        }
    }

    private List<Player> onlinePlayers(PartyInstance instance) {
        List<Player> players = new ArrayList<>();
        for (PartyPlayer pp : instance.players()) {
            Player player = plugin.getServer().getPlayer(pp.uuid());
            if (player != null && player.isOnline()) players.add(player);
        }
        return players;
    }

    private Location fallbackLocation() {
        World world = plugin.getServer().getWorld(config.fallbackWorld());
        if (world == null) {
            plugin.getLogger().severe("Configured slime.fallback.world is unavailable: " + config.fallbackWorld());
            return plugin.getServer().getWorlds().getFirst().getSpawnLocation();
        }
        return new Location(world, config.fallbackX(), config.fallbackY(), config.fallbackZ(), config.fallbackYaw(), config.fallbackPitch());
    }

    private Location lobbySpawn(Player player) {
        PartyInstance instance = instanceOf(player.getUniqueId()).orElse(null);
        if (instance != null && instance.state() == PartyState.WAITING && instance.activePlayArea() != null) {
            return instance.activePlayArea().spawn();
        }
        World world = Bukkit.getWorld(config.lobbyParkour().fallbackWorld());
        if (world == null) {
            return fallbackLocation();
        }
        return new Location(world, config.lobbySpawnX(), config.lobbySpawnY(), config.lobbySpawnZ(),
                config.lobbySpawnYaw(), config.lobbySpawnPitch());
    }



    private Optional<PartyInstance> findByShortId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String lower = id.toLowerCase();
        try {
            UUID full = UUID.fromString(id);
            return store.get(full);
        } catch (IllegalArgumentException ignored) {
            // short id
        }
        return store.all().stream()
                .filter(i -> i.shortId().equalsIgnoreCase(lower) || i.id().toString().startsWith(lower))
                .findFirst();
    }
}
