package dev.epicc.party;

import com.infernalsuite.asp.api.world.SlimeWorld;
import dev.epicc.board.BoardSlot;
import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.board.BoardTurnController;
import dev.epicc.board.Dice;
import dev.epicc.config.PluginConfig;
import dev.epicc.containment.FakeWallService;
import dev.epicc.minigame.MinigameManager;
import dev.epicc.player.PlayerSessionService;
import dev.epicc.slime.SlimeWorldService;
import dev.epicc.store.InstanceStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

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
    private final InstanceStore store;
    private final PlayerSessionService sessions;
    private final BoardSlotRegistry slots;
    private final FakeWallService walls;
    private final MinigameManager minigames;
    private final SlimeWorldService slime;
    private final Map<UUID, BoardTurnController> controllers = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> countdowns = new ConcurrentHashMap<>();

    public PartyManager(
            JavaPlugin plugin,
            PluginConfig config,
            InstanceStore store,
            PlayerSessionService sessions,
            BoardSlotRegistry slots,
            FakeWallService walls,
            MinigameManager minigames,
            SlimeWorldService slime
    ) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.sessions = sessions;
        this.slots = slots;
        this.walls = walls;
        this.minigames = minigames;
        this.slime = slime;
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public Optional<PartyInstance> instanceOf(UUID playerId) {
        return sessions.instanceOf(playerId).flatMap(store::get);
    }

    public Collection<PartyInstance> all() {
        return store.all();
    }

    public Optional<String> create(Player host) {
        if (sessions.isInParty(host.getUniqueId())) {
            return Optional.of("You are already in a party.");
        }
        if (store.size() >= config.maxInstances()) {
            return Optional.of("Server party limit reached.");
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
        host.sendMessage(Component.text(
                "[McParty] Created party " + instance.shortId() + " — others: /party join " + instance.shortId(),
                NamedTextColor.GREEN
        ));
        return Optional.empty();
    }

    public Optional<String> join(Player player, String shortOrFullId) {
        if (sessions.isInParty(player.getUniqueId())) {
            return Optional.of("You are already in a party.");
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
            return Optional.of("No open party found.");
        }
        if (instance.state() != PartyState.WAITING) {
            return Optional.of("That party already started.");
        }
        PartyPlayer pp = new PartyPlayer(player.getUniqueId(), player.getName(), instance.settings().startingCoins());
        if (!instance.addPlayer(pp)) {
            return Optional.of("Could not join (full or invalid).");
        }
        sessions.bind(player.getUniqueId(), instance.id());
        instance.broadcast(Component.text(
                "[McParty] " + player.getName() + " joined (" + instance.playerCount() + "/" + instance.settings().maxPlayers() + ")",
                NamedTextColor.YELLOW
        ));
        return Optional.empty();
    }

    public Optional<String> leave(Player player, boolean silent) {
        Optional<PartyInstance> opt = instanceOf(player.getUniqueId());
        if (opt.isEmpty()) {
            return silent ? Optional.empty() : Optional.of("You are not in a party.");
        }
        PartyInstance instance = opt.get();
        walls.clear(player);
        sessions.unbind(player.getUniqueId());
        instance.removePlayer(player.getUniqueId());

        if (!silent) {
            player.sendMessage(Component.text("[McParty] You left the party.", NamedTextColor.GRAY));
            instance.broadcast(Component.text(
                    "[McParty] " + player.getName() + " left.",
                    NamedTextColor.GRAY
            ));
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

    public Optional<String> start(Player requester) {
        PartyInstance instance = instanceOf(requester.getUniqueId()).orElse(null);
        if (instance == null) {
            return Optional.of("You are not in a party.");
        }
        if (!instance.isHost(requester.getUniqueId()) && !requester.hasPermission("mcparty.admin")) {
            return Optional.of("Only the host can start.");
        }
        if (!instance.canStart()) {
            return Optional.of("Need at least " + instance.settings().minPlayers() + " players.");
        }
        if (instance.state() != PartyState.WAITING) {
            return Optional.of("Party already starting/started.");
        }

        BoardSlot templateSlot = slots.claimFree(instance.id()).orElse(null);
        if (templateSlot == null) {
            return Optional.of("No free ready board slot. Setup with /partyadmin.");
        }

        instance.setState(PartyState.STARTING);
        instance.broadcast(Component.text("[McParty] Loading world…", NamedTextColor.GREEN));

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
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<SlimeWorld> clone = slime.prepareClone(instanceId);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (instance.state() != PartyState.STARTING) {
                    templateSlot.release();
                    return;
                }
                if (clone.isEmpty()) {
                    instance.broadcast(Component.text(
                            "[McParty] Failed to load slime world (missing template?). Aborting.",
                            NamedTextColor.RED
                    ));
                    instance.setState(PartyState.WAITING);
                    templateSlot.release();
                    return;
                }

                Optional<World> world = slime.loadClone(instanceId, clone.get());
                if (world.isEmpty()) {
                    instance.broadcast(Component.text(
                            "[McParty] Failed to register slime world. Aborting.",
                            NamedTextColor.RED
                    ));
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
                instance.broadcast(Component.text("[McParty] " + left + "…", NamedTextColor.YELLOW));
                left--;
            }
        }, 0L, 20L);
        countdowns.put(instance.id(), task);
    }

    public Optional<String> forceEnd(Player admin, String shortId) {
        PartyInstance instance;
        if (shortId == null || shortId.isBlank()) {
            instance = instanceOf(admin.getUniqueId()).orElse(null);
        } else {
            instance = findByShortId(shortId).orElse(null);
        }
        if (instance == null) {
            return Optional.of("Party not found.");
        }
        endInternal(instance);
        return Optional.empty();
    }

    public Optional<String> roll(Player player) {
        PartyInstance instance = instanceOf(player.getUniqueId()).orElse(null);
        if (instance == null) {
            return Optional.of("Not in a party.");
        }
        BoardTurnController controller = controllers.get(instance.id());
        if (controller == null) {
            return Optional.of("Game not running.");
        }
        if (!controller.roll(player)) {
            return Optional.of("Not your turn.");
        }
        return Optional.empty();
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(store.all().stream().map(PartyInstance::id).toList())) {
            store.get(id).ifPresent(this::cleanup);
        }
        controllers.clear();
        sessions.clearAll();
        slots.releaseAll();
        slime.unloadAll();
    }

    private void beginPlaying(PartyInstance instance) {
        BoardSlot slot = instance.slot();
        if (slot == null || !slot.isReady()) {
            instance.broadcast(Component.text("[McParty] Slot invalid — aborting.", NamedTextColor.RED));
            cleanup(instance);
            return;
        }

        Location spawn = slot.spawn();
        for (PartyPlayer pp : instance.players()) {
            Player p = plugin.getServer().getPlayer(pp.uuid());
            if (p != null && p.isOnline()) {
                p.teleport(spawn);
                walls.apply(p, slot.boundary());
            }
        }

        BoardTurnController controller = new BoardTurnController(
                plugin,
                minigames,
                new Dice(instance.settings().diceMin(), instance.settings().diceMax())
        );
        controller.attach(instance);
        controllers.put(instance.id(), controller);
        controller.startTurns();
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
        instance.broadcast(Component.text("[McParty] === Results ===", NamedTextColor.GOLD));
        for (int i = 0; i < ranked.size(); i++) {
            PartyPlayer pp = ranked.get(i);
            instance.broadcast(Component.text(
                    "#" + (i + 1) + " " + pp.name() + " — space " + pp.boardIndex() + ", " + pp.coins() + " coins",
                    NamedTextColor.YELLOW
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
            Player p = plugin.getServer().getPlayer(pp.uuid());
            if (p != null) {
                walls.clear(p);
            }
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
}
