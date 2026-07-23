package dev.epicc.party;

import com.infernalsuite.asp.api.world.SlimeWorld;
import dev.epicc.board.BoardSlot;
import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.board.BoardTurnController;
import dev.epicc.board.Dice;
import dev.epicc.board.PathHopMover;
import dev.epicc.board.dice.DiceHatService;
import dev.epicc.board.dice.DicePresenter;
import dev.epicc.config.MessageService;
import dev.epicc.config.PluginConfig;
import dev.epicc.minigame.MinigameManager;
import dev.epicc.player.PlayerSessionService;
import dev.epicc.resourcepack.ResourcePackService;
import dev.epicc.seamless.SeamlessWorldChangeService;
import dev.epicc.slime.SlimeWorldService;
import dev.epicc.store.InstanceStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
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
import java.util.concurrent.ThreadLocalRandom;

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
    private final Map<UUID, BoardTurnController> controllers = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> countdowns = new ConcurrentHashMap<>();

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
            ResourcePackService resourcePacks
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
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public MessageService messages() {
        return messages;
    }

    public Optional<PartyInstance> instanceOf(UUID playerId) {
        return sessions.instanceOf(playerId).flatMap(store::get);
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
        return Optional.empty();
    }

    private void offerResourcePack(Player player) {
        if (resourcePacks != null && resourcePacks.isReady() && resourcePacks.sendOnParty()) {
            resourcePacks.offerLater(player);
        }
    }

    public Optional<Component> leave(Player player, boolean silent) {
        Optional<PartyInstance> opt = instanceOf(player.getUniqueId());
        if (opt.isEmpty()) {
            return silent ? Optional.empty() : Optional.of(messages.get("party.not-in"));
        }
        PartyInstance instance = opt.get();
        sessions.unbind(player.getUniqueId());
        instance.removePlayer(player.getUniqueId());

        if (!silent) {
            messages.send(player, "party.left-self");
            instance.broadcast(messages.get("party.left-broadcast", "player", player.getName()));
        }

        if (instance.playerCount() == 0) {
            cleanup(instance);
            return Optional.empty();
        }

        if (instance.state() == PartyState.WAITING) {
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

        BoardSlot templateSlot = slots.claimFree(instance.id()).orElse(null);
        if (templateSlot == null) {
            return Optional.of(messages.get("party.no-board-slot"));
        }

        instance.setState(PartyState.STARTING);
        instance.broadcast(messages.get("party.loading-world"));

        if (slime.isReady()) {
            startWithSlime(instance, templateSlot);
        } else {
            // Fallback: use the permanent slot world (no ASP)
            instance.setSlot(templateSlot);
            beginCountdown(instance);
        }
        return Optional.empty();
    }

    private void startWithSlime(PartyInstance instance, BoardSlot templateSlot) {
        final UUID instanceId = instance.id();
        final String slimeTemplate = templateSlot.slimeTemplate();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<SlimeWorld> clone = slime.prepareClone(instanceId, slimeTemplate);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (instance.state() != PartyState.STARTING) {
                    templateSlot.release();
                    return;
                }
                if (clone.isEmpty()) {
                    instance.broadcast(messages.get("party.slime-load-failed"));
                    instance.setState(PartyState.WAITING);
                    templateSlot.release();
                    return;
                }

                Optional<World> world = slime.loadClone(instanceId, clone.get());
                if (world.isEmpty()) {
                    instance.broadcast(messages.get("party.slime-register-failed"));
                    instance.setState(PartyState.WAITING);
                    templateSlot.release();
                    return;
                }

                // Keep template claimed; use a runtime slot bound to the clone world
                BoardSlot runtime = templateSlot.forWorld(world.get());
                runtime.claim(instanceId);
                instance.setSlot(runtime);
                beginCountdown(instance);
            });
        });
    }

    private void beginCountdown(PartyInstance instance) {
        int countdown = config.startCountdownSeconds();
        Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(200));
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int left = countdown;

            @Override
            public void run() {
                if (instance.state() != PartyState.STARTING) {
                    cancelCountdown(instance.id());
                    return;
                }
                if (left <= 0) {
                    cancelCountdown(instance.id());
                    beginPlaying(instance);
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
        countdowns.put(instance.id(), task);
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

    private void beginPlaying(PartyInstance instance) {
        BoardSlot slot = instance.slot();
        if (slot == null || !slot.isReady()) {
            instance.broadcast(messages.get("party.slot-invalid"));
            cleanup(instance);
            return;
        }

        Title startTitle = Title.title(
                messages.get("party.start-title"),
                messages.get("party.start-subtitle"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))
        );
        Location spawn = slot.spawn();
        for (PartyPlayer pp : instance.players()) {
            Player p = plugin.getServer().getPlayer(pp.uuid());
            if (p != null && p.isOnline()) {
                seamless.teleport(p, scatterAround(spawn, 4.0));
                p.showTitle(startTitle);
            }
        }

        BoardTurnController controller = new BoardTurnController(
                plugin,
                messages,
                minigames,
                new Dice(instance.settings().diceMin(), instance.settings().diceMax()),
                dicePresenter,
                pathHopMover
        );
        controller.attach(instance);
        controllers.put(instance.id(), controller);
        // Delay first dice: private ItemDisplay passengers often fail same-tick as slime/world TP
        // (spawn = "first slot", no pad — later rounds on pads already have client tracking).
        final UUID instanceId = instance.id();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (instance.state() != PartyState.STARTING) {
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
        if (instance.state() == PartyState.CLEANUP || instance.state() == PartyState.ENDING) {
            // still run cleanup if ending mid-way twice
        }
        cancelCountdown(instance.id());
        BoardTurnController controller = controllers.remove(instance.id());
        if (controller != null) {
            controller.stop();
        }
        minigames.cancelActive();

        instance.setState(PartyState.ENDING);
        announcePodium(instance);
        instance.setState(PartyState.CLEANUP);
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

    private void cleanup(PartyInstance instance) {
        cancelCountdown(instance.id());
        BoardTurnController controller = controllers.remove(instance.id());
        if (controller != null) {
            controller.stop();
        }

        for (PartyPlayer pp : instance.players()) {
            dicePresenter.cancel(pp.uuid());
            diceHats.clear(pp.uuid());
            pathHopMover.cancel(pp.uuid());
        }

        sessions.clearInstance(instance.id());

        // Unload slime clone (teleports remaining players out)
        slime.unloadForInstance(instance.id());

        if (instance.slot() != null) {
            // Release the template slot (runtime copy may not be in the registry)
            slots.get(instance.slot().id()).ifPresent(BoardSlot::release);
            instance.slot().release();
            instance.setSlot(null);
        }
        store.remove(instance.id());
    }

    private void cancelCountdown(UUID instanceId) {
        BukkitTask task = countdowns.remove(instanceId);
        if (task != null) {
            task.cancel();
        }
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

    /** Random horizontal offset within {@code radius} blocks of center (same Y/yaw/pitch). */
    private static Location scatterAround(Location center, double radius) {
        if (center == null || center.getWorld() == null) {
            return center;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double dist = Math.sqrt(rng.nextDouble()) * radius;
        return new Location(
                center.getWorld(),
                center.getX() + Math.cos(angle) * dist,
                center.getY(),
                center.getZ() + Math.sin(angle) * dist,
                center.getYaw(),
                center.getPitch()
        );
    }
}
