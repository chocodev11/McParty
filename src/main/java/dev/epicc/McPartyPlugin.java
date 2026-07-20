package dev.epicc;

import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.board.setup.WorldEditHook;
import dev.epicc.command.PartyAdminCommand;
import dev.epicc.command.PartyCommand;
import dev.epicc.config.PluginConfig;
import dev.epicc.containment.BoundaryListener;
import dev.epicc.containment.FakeWallService;
import dev.epicc.minigame.DummyMinigame;
import dev.epicc.minigame.MinigameManager;
import dev.epicc.party.PartyManager;
import dev.epicc.player.PlayerSessionService;
import dev.epicc.slime.SlimeWorldService;
import dev.epicc.store.InMemoryInstanceStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class McPartyPlugin extends JavaPlugin {

    private PartyManager partyManager;
    private BoardSlotRegistry slotRegistry;
    private SlimeWorldService slimeWorldService;

    @Override
    public void onEnable() {
        PluginConfig config = new PluginConfig(this);
        PlayerSessionService sessions = new PlayerSessionService();
        InMemoryInstanceStore store = new InMemoryInstanceStore();
        slotRegistry = new BoardSlotRegistry(this);
        slotRegistry.load();

        slimeWorldService = new SlimeWorldService(
                this,
                config.slimeEnabled(),
                config.slimeWorldsDirectory(),
                config.slimeTemplateWorld(),
                config.slimeWorldPrefix(),
                config.slimeAllowMonsters(),
                config.slimeAllowAnimals(),
                config.slimePvp()
        );

        FakeWallService walls = new FakeWallService(config.wallMaterial(), config.wallHeight());
        MinigameManager minigames = new MinigameManager(
                this,
                new DummyMinigame(config.dummyDurationSeconds(), config.dummyCoinRewards())
        );
        partyManager = new PartyManager(
                this, config, store, sessions, slotRegistry, walls, minigames, slimeWorldService
        );

        getServer().getPluginManager().registerEvents(new BoundaryListener(partyManager, walls), this);

        PartyCommand partyCommand = new PartyCommand(partyManager);
        PluginCommand party = getCommand("party");
        if (party != null) {
            party.setExecutor(partyCommand);
            party.setTabCompleter(partyCommand);
        }

        PartyAdminCommand adminCommand = new PartyAdminCommand(slotRegistry, new WorldEditHook(), walls);
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
        if (slotRegistry != null) {
            slotRegistry.save();
        }
        getLogger().info("McParty disabled");
    }
}
