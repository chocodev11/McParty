package dev.epicc.party;

import com.infernalsuite.asp.api.world.SlimeWorld;
import dev.epicc.config.MessageService;
import dev.epicc.config.PluginConfig;
import dev.epicc.containment.SlotBoundary;
import dev.epicc.player.PlayerSessionService;
import dev.epicc.seamless.SeamlessWorldChangeService;
import dev.epicc.slime.SlimeWorldService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.UUID;

public final class LobbyMatchmaker implements Listener {

    private final JavaPlugin plugin;
    private final PartyManager partyManager;
    private final PluginConfig config;
    private final MessageService messages;
    private final SlimeWorldService slime;
    private final SeamlessWorldChangeService seamless;
    private final PlayerSessionService sessions;

    public LobbyMatchmaker(
            JavaPlugin plugin,
            PartyManager partyManager,
            PluginConfig config,
            MessageService messages,
            SlimeWorldService slime,
            SeamlessWorldChangeService seamless,
            PlayerSessionService sessions
    ) {
        this.plugin = plugin;
        this.partyManager = partyManager;
        this.config = config;
        this.messages = messages;
        this.slime = slime;
        this.seamless = seamless;
        this.sessions = sessions;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (sessions.isInParty(player.getUniqueId())) {
            // Player is already in a party (e.g. rejoining after disconnect)
            Optional<PartyInstance> opt = partyManager.instanceOf(player.getUniqueId());
            if (opt.isPresent()) {
                PartyInstance instance = opt.get();
                if (instance.state() == PartyState.WAITING) {
                    teleportToLobby(player, instance.id());
                }
            }
            return;
        }

        // 1. Try to find an open WAITING party
        for (PartyInstance instance : partyManager.all()) {
            if (instance.state() == PartyState.WAITING && instance.playerCount() < instance.settings().maxPlayers()) {
                // We found one! Try to join.
                Optional<net.kyori.adventure.text.Component> error = partyManager.join(player, instance.shortId());
                if (error.isEmpty()) {
                    // Joined successfully, teleport to its lobby
                    teleportToLobby(player, instance.id());
                } else {
                    messages.send(player, "party.join-failed");
                }
                return;
            }
        }

        // 2. No open party found, create a new one
        Optional<net.kyori.adventure.text.Component> createError = partyManager.create(player);
        if (createError.isPresent()) {
            messages.send(player, "party.limit-reached");
            return;
        }

        Optional<PartyInstance> newInstanceOpt = partyManager.instanceOf(player.getUniqueId());
        if (newInstanceOpt.isEmpty()) {
            return; // Should not happen
        }
        PartyInstance newInstance = newInstanceOpt.get();
        final UUID instanceId = newInstance.id();

        if (!slime.isReady()) {
            // Slime not enabled, just leave them where they are (WAITING state in memory)
            return;
        }

        // 3. Clone and load the lobby template
        final String template = config.lobbySlimeTemplate();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<SlimeWorld> lobbyClone = slime.prepareClone(instanceId, template);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (newInstance.state() != PartyState.WAITING) {
                    slime.unloadForInstance(instanceId);
                    return;
                }
                if (lobbyClone.isEmpty()) {
                    newInstance.broadcast(messages.get("party.slime-load-failed"));
                    partyManager.cleanup(newInstance);
                    return;
                }

                Optional<World> lobbyWorld = slime.loadClone(instanceId, template, lobbyClone.get());
                if (lobbyWorld.isEmpty()) {
                    newInstance.broadcast(messages.get("party.slime-register-failed"));
                    partyManager.cleanup(newInstance);
                    return;
                }

                // Lobby loaded! Teleport host (and anyone who joined while it was loading)
                for (PartyPlayer pp : newInstance.players()) {
                    Player p = plugin.getServer().getPlayer(pp.uuid());
                    if (p != null && p.isOnline()) {
                        teleportToLobby(p, lobbyWorld.get());
                    }
                }
                // Only clamp once everyone is inside — the boundary lives in the clone world
                bindLobbyArea(newInstance, lobbyWorld.get());
            });
        });
    }

    private void teleportToLobby(Player player, UUID instanceId) {
        Optional<World> worldOpt = slime.getLoadedWorld(instanceId, config.lobbySlimeTemplate());
        worldOpt.ifPresent(world -> teleportToLobby(player, world));
    }

    private void teleportToLobby(Player player, World world) {
        seamless.teleport(player, lobbySpawn(world));
    }

    /** Contain waiting players inside the configured {@code lobby.boundary} of their own clone. */
    private void bindLobbyArea(PartyInstance instance, World world) {
        SlotBoundary boundary = new SlotBoundary(
                world,
                config.lobbyBoundMinX(), config.lobbyBoundMinY(), config.lobbyBoundMinZ(),
                config.lobbyBoundMaxX(), config.lobbyBoundMaxY(), config.lobbyBoundMaxZ()
        );
        instance.setActivePlayArea(new PartyPlayArea(world, lobbySpawn(world), boundary));
    }

    private Location lobbySpawn(World world) {
        return new Location(
                world,
                config.lobbySpawnX(),
                config.lobbySpawnY(),
                config.lobbySpawnZ(),
                config.lobbySpawnYaw(),
                config.lobbySpawnPitch()
        );
    }
}
