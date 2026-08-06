package dev.epicc.party;

import com.infernalsuite.asp.api.world.SlimeWorld;
import dev.epicc.config.MessageService;
import dev.epicc.config.PluginConfig;
import dev.epicc.containment.SlotBoundary;
import dev.epicc.hologram.HologramService;
import dev.epicc.player.PlayerSessionService;
import dev.epicc.lobby.parkour.LobbyParkourService;
import dev.epicc.slime.SlimeWorldService;
import org.bukkit.Location;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Optional;
import java.util.UUID;

public final class LobbyMatchmaker implements Listener {

    private static final double LOBBY_FALL_Y = 30.0;

    private final JavaPlugin plugin;
    private final PartyManager partyManager;
    private final PluginConfig config;
    private final MessageService messages;
    private final SlimeWorldService slime;
    private final PlayerSessionService sessions;
    private final LobbyParkourService lobbyParkour;
    private final HologramService holograms;

    public LobbyMatchmaker(
            JavaPlugin plugin,
            PartyManager partyManager,
            PluginConfig config,
            MessageService messages,
            SlimeWorldService slime,
            PlayerSessionService sessions,
            LobbyParkourService lobbyParkour,
            HologramService holograms
    ) {
        this.plugin = plugin;
        this.partyManager = partyManager;
        this.config = config;
        this.messages = messages;
        this.slime = slime;
        this.sessions = sessions;
        this.lobbyParkour = lobbyParkour;
        this.holograms = holograms;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        preparePlayer(player);

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

                configureLobbyWorld(lobbyWorld.get());
                holograms.openLobbyScope(instanceId, lobbyWorld.get());
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
        configureLobbyWorld(world);
        preparePlayer(player);
        Location destination = lobbySpawn(world);
        // ASP clone registration can send a respawn packet immediately after load. Suppressing
        // that packet leaves the client at the clone's world spawn, so lobby teleports must use
        // the normal Bukkit path and complete the dimension transition reliably.
        player.teleport(destination);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getWorld() != world) {
                return;
            }
            if (player.getLocation().distanceSquared(destination) <= 4.0) {
                return;
            }
            partyManager.instanceOf(player.getUniqueId())
                    .filter(instance -> instance.state() == PartyState.WAITING)
                    .ifPresent(instance -> player.teleport(destination));
        });
    }

    public void configureFallbackWorld() {
        String worldName = config.lobbyParkour().fallbackWorld();
        if (!worldName.isBlank()) {
            World world = plugin.getServer().getWorld(worldName);
            if (world != null) {
                configureLobbyWorld(world);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLobbyFall(PlayerMoveEvent event) {
        Location to = event.getTo();
        Player player = event.getPlayer();
        if (to == null || to.getY() > LOBBY_FALL_Y || !isLobbyWorld(player.getWorld())) {
            return;
        }

        event.setTo(lobbySpawn(player.getWorld()));
        player.setFallDistance(0.0f);
        player.setVelocity(new Vector());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !isLobbyWorld(player.getWorld())
                || event.getFoodLevel() >= player.getFoodLevel()) {
            return;
        }
        event.setCancelled(true);
    }

    /** Contain waiting players inside the configured {@code lobby.boundary} of their own clone. */
    private void bindLobbyArea(PartyInstance instance, World world) {
        configureLobbyWorld(world);
        SlotBoundary boundary = new SlotBoundary(
                world,
                config.lobbyBoundMinX(), config.lobbyBoundMinY(), config.lobbyBoundMinZ(),
                config.lobbyBoundMaxX(), config.lobbyBoundMaxY(), config.lobbyBoundMaxZ()
        );
        instance.setActivePlayArea(new PartyPlayArea(world, lobbySpawn(world), boundary));
        lobbyParkour.refresh(world);
    }

    private void preparePlayer(Player player) {
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setExhaustion(0.0f);
    }

    private void configureLobbyWorld(World world) {
        world.setTime(6000L);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
    }

    private boolean isLobbyWorld(World world) {
        if (world.getName().equals(config.lobbyParkour().fallbackWorld())) {
            return true;
        }
        for (PartyInstance instance : partyManager.all()) {
            if (instance.state() != PartyState.WAITING || instance.activePlayArea() == null) {
                continue;
            }
            if (instance.activePlayArea().world().equals(world)) {
                return true;
            }
        }
        return false;
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
