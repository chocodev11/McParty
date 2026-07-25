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
import dev.epicc.config.MessageService;
import dev.epicc.config.PluginConfig;
import dev.epicc.containment.BoundaryListener;
import dev.epicc.minigame.DummyMinigame;
import dev.epicc.minigame.HotPotatoMinigame;
import dev.epicc.minigame.MinigameManager;
import dev.epicc.minigame.MinigameRegistry;
import dev.epicc.party.LobbyMatchmaker;
import dev.epicc.party.PartyManager;
import dev.epicc.player.PlayerSessionService;
import dev.epicc.resourcepack.ResourcePackListener;
import dev.epicc.resourcepack.ResourcePackService;
import dev.epicc.seamless.SeamlessWorldChangeService;
import dev.epicc.slime.SlimeFallDamageListener;
import dev.epicc.slime.SlimeWorldService;
import dev.epicc.store.InMemoryInstanceStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class McPartyPlugin extends JavaPlugin {

    private PluginConfig config;
    private MessageService messages;
    private PartyManager partyManager;
    private BoardSlotRegistry slotRegistry;
    private PathSetupService pathSetupService;
    private ResourcePackService resourcePackService;
    private DicePresenter dicePresenter;
    private DiceHatService diceHats;
    private PathHopMover pathHopMover;
    private MinigameManager minigames;
    private final List<DummyMinigame> dummyMinigames = new ArrayList<>();
    private SlimeWorldService slimeWorldService;

    @Override
    public void onEnable() {
        config = new PluginConfig(this);
        messages = new MessageService(this);
        PlayerSessionService sessions = new PlayerSessionService();
        InMemoryInstanceStore store = new InMemoryInstanceStore();
        slotRegistry = new BoardSlotRegistry(this);
        slotRegistry.load();

        SeamlessWorldChangeService seamless = new SeamlessWorldChangeService(
                this, config.seamlessWorldChangeEnabled()
        );

        resourcePackService = new ResourcePackService(this, config, messages);
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
                    messages,
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
                    messages, config.hotPotatoBombSeconds(), config.hotPotatoThrowVelocity(),
                    config.hotPotatoArena(), config.dummyCoinRewards()
            ));
        } else {
            getLogger().severe("Hot Potato is disabled: minigame.hot_potato.arena requires a template, spawn, and valid boundary.");
        }
        minigames = new MinigameManager(
                this,
                messages,
                minigameRegistry,
                slimeWorldService,
                config.minigameRevealDurationTicks(),
                config.minigameRevealIntervalMinTicks(),
                config.minigameRevealIntervalMaxTicks(),
                config.minigameRevealExpandIntervalTicks(),
                config.minigameRevealColorSteps(),
                config.minigameRevealColorIntervalTicks()
        );


        diceHats = new DiceHatService(config.diceHatScale());
        dicePresenter = new DicePresenter(
                this,
                diceHats,
                config.diceSpawnDistance(),
                config.diceInteractSeconds(),
                config.diceSpinIntervalTicks(),
                config.diceDisplayScale(),
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
                dicePresenter, diceHats, pathHopMover, resourcePackService
        );

        getServer().getPluginManager().registerEvents(new BoundaryListener(partyManager, pathHopMover), this);
        getServer().getPluginManager().registerEvents(new DiceClickListener(dicePresenter), this);
        getServer().getPluginManager().registerEvents(pathHopMover, this);
        getServer().getPluginManager().registerEvents(new SlimeFallDamageListener(slimeWorldService), this);
        getServer().getPluginManager().registerEvents(new ResourcePackListener(resourcePackService), this);
        getServer().getPluginManager().registerEvents(new LobbyMatchmaker(this, partyManager, config, messages, slimeWorldService, seamless, sessions), this);

        PartyCommand partyCommand = new PartyCommand(partyManager, messages);
        PluginCommand party = getCommand("party");
        if (party != null) {
            party.setExecutor(partyCommand);
            party.setTabCompleter(partyCommand);
        }

        pathSetupService = new PathSetupService(this, slotRegistry, messages);
        getServer().getPluginManager().registerEvents(new PathSetupListener(this, pathSetupService), this);

        PartyAdminCommand adminCommand = new PartyAdminCommand(
                this, slotRegistry, pathSetupService, messages, slimeWorldService, minigames
        );
        PluginCommand partyAdmin = getCommand("partyadmin");
        if (partyAdmin != null) {
            partyAdmin.setExecutor(adminCommand);
            partyAdmin.setTabCompleter(adminCommand);
        }

        if (slimeWorldService.isReady()) {
            getLogger().info("McParty enabled (ASP slime worlds active)");
        } else if (config.slimeEnabled()) {
            getLogger().warning("McParty enabled but ASP slime service is not ready — parties fall back to permanent worlds");
        } else {
            getLogger().info("McParty enabled (slime disabled)");
        }
    }

    /**
     * Reload {@code config.yml} + {@code messages.yml} and re-apply hot settings
     * (party/board/minigame/resource pack). Slime loader + seamless PE hook stay as at enable.
     */
    public void reloadPluginConfig() {
        config.reload();
        messages.reload();

        diceHats.reconfigure(config.diceHatScale());
        dicePresenter.reconfigure(
                config.diceSpawnDistance(),
                config.diceInteractSeconds(),
                config.diceSpinIntervalTicks(),
                config.diceDisplayScale(),
                config.diceSpinScale()
        );
        pathHopMover.reconfigure(
                config.hopUpVelocity(),
                config.hopRiseMaxSeconds(),
                config.hopFallMaxSeconds()
        );
        minigames.reconfigure(
                config.minigameRevealDurationTicks(),
                config.minigameRevealIntervalMinTicks(),
                config.minigameRevealIntervalMaxTicks(),
                config.minigameRevealExpandIntervalTicks(),
                config.minigameRevealColorSteps(),
                config.minigameRevealColorIntervalTicks()
        );
        for (DummyMinigame dummy : dummyMinigames) {
            dummy.reconfigure(config.dummyDurationSeconds(), config.dummyCoinRewards());
        }
        minigames.registry().unregister("hot_potato");
        if (config.hotPotatoArena().isValid()) {
            minigames.registry().register(new HotPotatoMinigame(
                    messages, config.hotPotatoBombSeconds(), config.hotPotatoThrowVelocity(),
                    config.hotPotatoArena(), config.dummyCoinRewards()
            ));
        } else {
            getLogger().severe("Hot Potato remains disabled: minigame.hot_potato.arena is invalid or missing.");
        }

        resourcePackService.reload();
        getLogger().info("Config reloaded");
    }

    @Override
    public void onDisable() {
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
        getLogger().info("McParty disabled");
    }
}
