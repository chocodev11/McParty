package dev.epicc;

import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.board.PathHopMover;
import dev.epicc.board.dice.DiceClickListener;
import dev.epicc.board.dice.DiceHatService;
import dev.epicc.board.dice.DicePresenter;
import dev.epicc.board.setup.WorldEditHook;
import dev.epicc.command.PartyAdminCommand;
import dev.epicc.command.PartyCommand;
import dev.epicc.config.PluginConfig;
import dev.epicc.containment.BoundaryListener;
import dev.epicc.minigame.DummyMinigame;
import dev.epicc.minigame.MinigameManager;
import dev.epicc.minigame.MinigameRegistry;
import dev.epicc.party.PartyManager;
import dev.epicc.player.PlayerSessionService;
import dev.epicc.resourcepack.ResourcePackListener;
import dev.epicc.resourcepack.ResourcePackService;
import dev.epicc.seamless.SeamlessWorldChangeService;
import dev.epicc.slime.SlimeWorldService;
import dev.epicc.store.InMemoryInstanceStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class McPartyPlugin extends JavaPlugin {

    private PartyManager partyManager;
    private BoardSlotRegistry slotRegistry;
    private SlimeWorldService slimeWorldService;
    private ResourcePackService resourcePackService;

    @Override
    public void onEnable() {
        PluginConfig config = new PluginConfig(this);
        PlayerSessionService sessions = new PlayerSessionService();
        InMemoryInstanceStore store = new InMemoryInstanceStore();
        slotRegistry = new BoardSlotRegistry(this);
        slotRegistry.load();

        SeamlessWorldChangeService seamless = new SeamlessWorldChangeService(
                this, config.seamlessWorldChangeEnabled()
        );

        resourcePackService = new ResourcePackService(this, config);
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

        MinigameRegistry minigameRegistry = new MinigameRegistry(
                new DummyMinigame(config.dummyDurationSeconds(), config.dummyCoinRewards())
        );
        // Register more Minigame impls here later: minigameRegistry.register(...)
        MinigameManager minigames = new MinigameManager(
                this,
                minigameRegistry,
                config.minigameRevealDurationTicks(),
                config.minigameRevealIntervalTicks()
        );

        DiceHatService diceHats = new DiceHatService(config.diceHatScale());
        DicePresenter dicePresenter = new DicePresenter(
                this,
                diceHats,
                config.diceSpawnDistance(),
                config.diceInteractSeconds(),
                config.diceSpinIntervalTicks(),
                config.diceDisplayScale()
        );
        PathHopMover pathHopMover = new PathHopMover(
                this,
                config.hopHeight(),
                config.hopRiseSeconds(),
                config.hopFallMaxSeconds()
        );

        partyManager = new PartyManager(
                this, config, store, sessions, slotRegistry, minigames, slimeWorldService, seamless,
                dicePresenter, diceHats, pathHopMover, resourcePackService
        );

        getServer().getPluginManager().registerEvents(new BoundaryListener(partyManager, pathHopMover), this);
        getServer().getPluginManager().registerEvents(new DiceClickListener(dicePresenter), this);
        getServer().getPluginManager().registerEvents(pathHopMover, this);
        getServer().getPluginManager().registerEvents(new ResourcePackListener(resourcePackService), this);

        PartyCommand partyCommand = new PartyCommand(partyManager);
        PluginCommand party = getCommand("party");
        if (party != null) {
            party.setExecutor(partyCommand);
            party.setTabCompleter(partyCommand);
        }

        PartyAdminCommand adminCommand = new PartyAdminCommand(slotRegistry, new WorldEditHook());
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

    @Override
    public void onDisable() {
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
