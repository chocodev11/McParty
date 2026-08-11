package dev.epicc;

import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.board.PathHopMover;
import dev.epicc.board.dice.DiceClickListener;
import dev.epicc.board.dice.DiceHatService;
import dev.epicc.board.dice.DicePresenter;
import dev.epicc.board.setup.PathSetupListener;
import dev.epicc.board.setup.PathSetupService;
import dev.epicc.command.PartyAdminCommand;
import dev.epicc.command.PartyCommand;
import dev.epicc.command.HologramCommand;
import dev.epicc.config.MessageService;
import dev.epicc.config.PluginConfig;
import dev.epicc.containment.BoundaryListener;
import dev.epicc.minigame.DummyMinigame;
import dev.epicc.minigame.ElytraCourse;
import dev.epicc.minigame.ElytraCourseStore;
import dev.epicc.minigame.ElytraMinigame;
import dev.epicc.minigame.HotPotatoMinigame;
import dev.epicc.minigame.MinigameEventBus;
import dev.epicc.minigame.MinigameManager;
import dev.epicc.minigame.MinigameRegistry;
import dev.epicc.minigame.SpleefMinigame;
import dev.epicc.hologram.HologramService;
import dev.epicc.lobby.parkour.LobbyParkourListener;
import dev.epicc.lobby.parkour.ParkourLeaderboardStore;
import dev.epicc.lobby.parkour.LobbyParkourService;
import dev.epicc.lobby.parkour.SqliteParkourLeaderboardStore;
import dev.epicc.party.LobbyMatchmaker;
import dev.epicc.party.PartyManager;
import dev.epicc.player.PlayerSessionService;
import dev.epicc.resourcepack.ResourcePackListener;
import dev.epicc.resourcepack.FontImageService;
import dev.epicc.resourcepack.ResourcePackService;
import dev.epicc.seamless.SeamlessWorldChangeService;
import dev.epicc.slime.SlimeFallDamageListener;
import dev.epicc.slime.SlimeWorldService;
import dev.epicc.store.InMemoryInstanceStore;
import dev.epicc.tablist.TabListService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;

public final class McPartyPlugin extends JavaPlugin {

    private PluginConfig config;
    private MessageService messages;
    private FontImageService fontImages;
    private PartyManager partyManager;
    private BoardSlotRegistry slotRegistry;
    private PathSetupService pathSetupService;
    private ResourcePackService resourcePackService;
    private DicePresenter dicePresenter;
    private DiceHatService diceHats;
    private PathHopMover pathHopMover;
    private MinigameManager minigames;
    private MinigameEventBus minigameEvents;
    private final List<DummyMinigame> dummyMinigames = new ArrayList<>();
    private SlimeWorldService slimeWorldService;
    private LobbyParkourService lobbyParkour;
    private ParkourLeaderboardStore parkourLeaderboard;
    private LobbyMatchmaker lobbyMatchmaker;
    private HologramService holograms;
    private ElytraCourseStore elytraCourses;
    private TabListService tabList;

    @Override
    public void onEnable() {
        config = new PluginConfig(this);
        fontImages = new FontImageService(this);
        messages = new MessageService(this, fontImages);
        elytraCourses = new ElytraCourseStore(this);
        elytraCourses.load();
        try {
            parkourLeaderboard = new SqliteParkourLeaderboardStore(
                    getDataFolder().toPath().resolve(config.databaseSqliteFile()),
                    getLogger()
            );
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE,
                    "Parkour leaderboard is disabled because its SQLite database could not be opened", exception);
        }
        boolean packetEventsReady = Bukkit.getPluginManager().isPluginEnabled("packetevents");
        holograms = new HologramService(
                this,
                config.hologramsEnabled() && packetEventsReady,
                config.hologramsFile(),
                config.hologramScanIntervalTicks(),
                config.hologramDefaultViewRange()
        );
        if (config.hologramsEnabled() && !packetEventsReady) {
            getLogger().warning("Holograms enabled but PacketEvents is unavailable; holograms are disabled");
        }
        PlayerSessionService sessions = new PlayerSessionService();
        InMemoryInstanceStore store = new InMemoryInstanceStore();
        slotRegistry = new BoardSlotRegistry(this);
        slotRegistry.load();

        SeamlessWorldChangeService seamless = new SeamlessWorldChangeService(
                this, config.seamlessWorldChangeEnabled()
        );

        resourcePackService = new ResourcePackService(this, config, messages, fontImages);
        resourcePackService.start();

        slimeWorldService = new SlimeWorldService(
                this,
                config.slimeEnabled(),
                config.slimeWorldsDirectory(),
                config.slimeTemplateWorld(),
                config.slimeWorldPrefix(),
                config.slimeAllowMonsters(),
                config.slimeAllowAnimals(),
                config.slimePvp(),
                seamless
        );

        // Five dummy entries so the reveal roulette has distinct names to spin through
        String[][] dummyDefs = {
                {"lightning-dash", "Lightning Dash"},
                {"block-bash", "Block Bash"},
                {"color-match", "Color Match"},
                {"hot-potato", "Hot Potato"},
                {"sky-race", "Sky Race"}
        };
        dummyMinigames.clear();
        DummyMinigame firstDummy = null;
        for (String[] def : dummyDefs) {
            DummyMinigame dummy = new DummyMinigame(
                    def[0],
                    def[1],
                    config.dummyDurationSeconds(),
                    config.dummyCoinRewards()
            );
            dummyMinigames.add(dummy);
            if (firstDummy == null) {
                firstDummy = dummy;
            }
        }
        MinigameRegistry minigameRegistry = new MinigameRegistry(firstDummy);
        for (int i = 1; i < dummyMinigames.size(); i++) {
            minigameRegistry.register(dummyMinigames.get(i));
        }

        if (config.hotPotatoArena().isValid()) {
            minigameRegistry.register(new HotPotatoMinigame(
                    config.hotPotatoBombSeconds(), config.hotPotatoThrowVelocity(),
                    config.hotPotatoArena(), config.dummyCoinRewards()
            ));
        } else {
            getLogger().severe("Hot Potato is disabled: minigame.hot_potato.arena requires a template, spawn, and valid boundary.");
        }
        if (config.spleefArena().isValid()
                && Double.isFinite(config.spleefFallY())
                && config.spleefFallY() > config.spleefArena().minY()) {
            minigameRegistry.register(new SpleefMinigame(
                    config.spleefTimeoutSeconds(), config.spleefFallY(), config.spleefSpawnRadius(),
                    config.spleefArena(), config.spleefFloorMaterials(), config.dummyCoinRewards(),
                    config.spleefPowerupSpawnSeconds(), config.spleefMultishotSeconds(),
                    config.spleefPowerupItemModel()
            ));
        } else {
            getLogger().severe("Spleef is disabled: minigame.spleef.arena is invalid or fall-y is not above boundary.minY.");
        }
        registerElytraMinigame(minigameRegistry);
        minigameEvents = new MinigameEventBus();
        minigames = new MinigameManager(
                this,
                messages,
                minigameRegistry,
                slimeWorldService,
                minigameEvents
        );


        diceHats = new DiceHatService(config.diceHatScale());
        dicePresenter = new DicePresenter(
                this,
                diceHats,
                config.diceSpawnDistance(),
                config.diceInteractSeconds(),
                config.diceSpinIntervalTicks(),
                config.diceSpinScale()
        );
        pathHopMover = new PathHopMover(
                this,
                config.hopUpVelocity(),
                config.hopRiseMaxSeconds(),
                config.hopFallMaxSeconds()
        );

        partyManager = new PartyManager(
                this, config, messages, store, sessions, slotRegistry, minigames, slimeWorldService, seamless,
                dicePresenter, diceHats, pathHopMover, resourcePackService, holograms
        );
        tabList = new TabListService(this, config, messages, partyManager);
        partyManager.setTabListRefresh(tabList::refreshAll);
        unloadStaleSlimeWorlds();
        holograms.setScopeVisibility((scopeId, player) -> partyManager.instanceOf(player.getUniqueId())
                .map(instance -> instance.id().equals(scopeId)).orElse(false));
        holograms.registerPlaceholder("mcparty.party_id", context -> partyManager.instanceOf(context.player().getUniqueId())
                .map(instance -> Component.text(instance.shortId())).orElse(Component.empty()));
        holograms.registerPlaceholder("mcparty.party_state", context -> partyManager.instanceOf(context.player().getUniqueId())
                .map(instance -> Component.text(instance.state().name())).orElse(Component.empty()));
        holograms.registerPlaceholder("mcparty.turn", context -> partyManager.instanceOf(context.player().getUniqueId())
                .map(instance -> Component.text(instance.round() + 1)).orElse(Component.empty()));
        holograms.registerPlaceholder("mcparty.coins", context -> partyManager.instanceOf(context.player().getUniqueId())
                .flatMap(instance -> instance.player(context.player().getUniqueId()))
                .map(player -> Component.text(player.coins())).orElse(Component.empty()));
        holograms.start();
        lobbyParkour = new LobbyParkourService(this, config, messages, parkourLeaderboard);
        partyManager.setLobbyParkour(lobbyParkour);
        lobbyParkour.refreshConfiguredWorld();
        lobbyMatchmaker = new LobbyMatchmaker(
                this, partyManager, config, messages, slimeWorldService, sessions, lobbyParkour, holograms
        );
        partyManager.setLobbyMatchmaker(lobbyMatchmaker);
        lobbyMatchmaker.configureFallbackWorld();

        // One shared listener for every running minigame session (see MinigameEventBus)
        getServer().getPluginManager().registerEvents(minigameEvents, this);
        getServer().getPluginManager().registerEvents(new BoundaryListener(partyManager, pathHopMover), this);
        getServer().getPluginManager().registerEvents(new DiceClickListener(dicePresenter), this);
        getServer().getPluginManager().registerEvents(pathHopMover, this);
        getServer().getPluginManager().registerEvents(new SlimeFallDamageListener(slimeWorldService), this);
        getServer().getPluginManager().registerEvents(new ResourcePackListener(resourcePackService), this);
        getServer().getPluginManager().registerEvents(tabList, this);
        getServer().getPluginManager().registerEvents(lobbyMatchmaker, this);
        getServer().getPluginManager().registerEvents(new LobbyParkourListener(lobbyParkour, partyManager), this);
        getServer().getPluginManager().registerEvents(holograms, this);
        tabList.start();

        PartyCommand partyCommand = new PartyCommand(partyManager, messages);
        PluginCommand party = getCommand("party");
        if (party != null) {
            party.setExecutor(partyCommand);
            party.setTabCompleter(partyCommand);
        }

        pathSetupService = new PathSetupService(this, slotRegistry, messages);
        getServer().getPluginManager().registerEvents(new PathSetupListener(this, pathSetupService), this);

        PartyAdminCommand adminCommand = new PartyAdminCommand(
                this, slotRegistry, pathSetupService, messages, slimeWorldService, minigames, config, lobbyParkour,
                elytraCourses
        );
        PluginCommand partyAdmin = getCommand("partyadmin");
        if (partyAdmin != null) {
            partyAdmin.setExecutor(adminCommand);
            partyAdmin.setTabCompleter(adminCommand);
        }

        HologramCommand hologramCommand = new HologramCommand(messages, holograms);
        PluginCommand hologram = getCommand("hologram");
        if (hologram != null) {
            hologram.setExecutor(hologramCommand);
            hologram.setTabCompleter(hologramCommand);
        }

        if (slimeWorldService.isReady()) {
            getLogger().info("McParty enabled (ASP slime worlds active)");
        } else if (config.slimeEnabled()) {
            getLogger().warning("McParty enabled but ASP slime service is not ready — parties fall back to permanent worlds");
        } else {
            getLogger().info("McParty enabled (slime disabled)");
        }
    }

    private void unloadStaleSlimeWorlds() {
        if (!slimeWorldService.isReady()) {
            return;
        }
        World fallbackWorld = getServer().getWorld(config.fallbackWorld());
        Location fallback = fallbackWorld == null
                ? getServer().getWorlds().getFirst().getSpawnLocation()
                : new Location(
                        fallbackWorld,
                        config.fallbackX(), config.fallbackY(), config.fallbackZ(),
                        config.fallbackYaw(), config.fallbackPitch()
                );
        int unloaded = slimeWorldService.unloadStaleInstanceWorlds(fallback);
        if (unloaded > 0) {
            getLogger().info("Unloaded " + unloaded + " stale McParty slime world(s) during startup.");
        }
    }

    /**
     * Reload {@code config.yml} + {@code messages.yml} and re-apply hot settings
     * (party/board/minigame/resource pack). Slime loader + seamless PE hook stay as at enable.
     */
    public void reloadPluginConfig() {
        config.reload();
        fontImages.reload();
        messages.reload();

        diceHats.reconfigure(config.diceHatScale());
        dicePresenter.reconfigure(
                config.diceSpawnDistance(),
                config.diceInteractSeconds(),
                config.diceSpinIntervalTicks(),
                config.diceSpinScale()
        );
        pathHopMover.reconfigure(
                config.hopUpVelocity(),
                config.hopRiseMaxSeconds(),
                config.hopFallMaxSeconds()
        );
        if (holograms != null) {
            holograms.reconfigure(
                    config.hologramsEnabled() && Bukkit.getPluginManager().isPluginEnabled("packetevents"),
                    config.hologramScanIntervalTicks(),
                    config.hologramDefaultViewRange()
            );
        }
        for (DummyMinigame dummy : dummyMinigames) {
            dummy.reconfigure(config.dummyDurationSeconds(), config.dummyCoinRewards());
        }
        minigames.registry().unregister("hot_potato");
        if (config.hotPotatoArena().isValid()) {
            minigames.registry().register(new HotPotatoMinigame(
                    config.hotPotatoBombSeconds(), config.hotPotatoThrowVelocity(),
                    config.hotPotatoArena(), config.dummyCoinRewards()
            ));
        } else {
            getLogger().severe("Hot Potato remains disabled: minigame.hot_potato.arena is invalid or missing.");
        }
        minigames.registry().unregister("spleef");
        if (config.spleefArena().isValid()
                && Double.isFinite(config.spleefFallY())
                && config.spleefFallY() > config.spleefArena().minY()) {
            minigames.registry().register(new SpleefMinigame(
                    config.spleefTimeoutSeconds(), config.spleefFallY(), config.spleefSpawnRadius(),
                    config.spleefArena(), config.spleefFloorMaterials(), config.dummyCoinRewards(),
                    config.spleefPowerupSpawnSeconds(), config.spleefMultishotSeconds(),
                    config.spleefPowerupItemModel()
            ));
        } else {
            getLogger().severe("Spleef remains disabled: minigame.spleef.arena is invalid or fall-y is not above boundary.minY.");
        }
        registerElytraMinigame(minigames.registry());

        resourcePackService.reload();
        tabList.reload();
        lobbyParkour.refreshConfiguredWorld();
        lobbyMatchmaker.configureFallbackWorld();
        getLogger().info("Config reloaded");
    }

    private void registerElytraMinigame(MinigameRegistry registry) {
        registry.unregister("elytra_race");
        ElytraCourse course = elytraCourses.get(config.elytraCourseId()).orElse(null);
        if (course != null && course.isReady()) {
            registry.register(new ElytraMinigame(
                    course,
                    config.elytraTimeoutSeconds(),
                    config.elytraCenterBonusCoins(),
                    config.dummyCoinRewards()
            ));
        } else {
            getLogger().warning("Elytra Race is disabled: configure a ready course named '"
                    + config.elytraCourseId() + "' in elytra-courses.yml.");
        }
    }

    @Override
    public void onDisable() {
        if (tabList != null) {
            tabList.shutdown();
        }
        if (holograms != null) {
            holograms.shutdown();
        }
        if (lobbyParkour != null) {
            lobbyParkour.shutdown();
        }
        if (parkourLeaderboard != null) {
            parkourLeaderboard.close();
        }
        if (pathSetupService != null) {
            pathSetupService.cancelAll();
        }
        if (partyManager != null) {
            partyManager.shutdown();
        }
        if (slimeWorldService != null) {
            slimeWorldService.unloadAll();
        }
        if (resourcePackService != null) {
            resourcePackService.shutdown();
        }
        if (slotRegistry != null) {
            slotRegistry.save();
        }
        if (elytraCourses != null) {
            elytraCourses.save();
        }
        getLogger().info("McParty disabled");
    }
}
