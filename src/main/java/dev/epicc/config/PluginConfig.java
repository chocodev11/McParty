package dev.epicc.config;

import dev.epicc.minigame.MinigameArenaSpec;
import dev.epicc.minigame.MinigameRevealSettings;
import dev.epicc.lobby.parkour.LobbyParkourDefinition;
import dev.epicc.lobby.parkour.LobbyParkourPoint;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PluginConfig {

    private final JavaPlugin plugin;

    private int minPlayers;
    private int maxPlayers;
    private int maxInstances;
    private int startCountdownSeconds;
    private int maxTurns;
    private int startingCoins;
    private int diceMin;
    private int diceMax;
    private int diceInteractSeconds;
    private int diceSpinIntervalTicks;
    private double diceSpawnDistance;
    private float diceSpinScale;
    private float diceHatScale;
    private double hopUpVelocity;
    private double hopRiseMaxSeconds;
    private double hopFallMaxSeconds;
    private int dummyDurationSeconds;
    private List<Integer> dummyCoinRewards;
    private MinigameRevealSettings minigameReveal;

    private String hotPotatoSlimeTemplate;
    private int hotPotatoBombSeconds;
    private double hotPotatoThrowVelocity;
    private int hotPotatoMaxCycles;
    private MinigameArenaSpec hotPotatoArena;

    private int spleefTimeoutSeconds;
    private double spleefFallY;
    private double spleefSpawnRadius;
    private List<Material> spleefFloorMaterials;
    private MinigameArenaSpec spleefArena;
    private int spleefPowerupSpawnSeconds;
    private int spleefMultishotSeconds;
    private String spleefPowerupItemModel;

    private boolean seamlessWorldChangeEnabled;

    private boolean resourcePackEnabled;
    private String resourcePackMode;
    private String resourcePackSendOn;
    private boolean resourcePackRequired;
    private boolean resourcePackKickOnDecline;
    private int resourcePackSendDelayTicks;
    private String resourcePackExternalUrl;
    private String resourcePackExternalSha1;
    private String resourcePackLocalSourceFolder;
    private String resourcePackLocalZipName;
    private String resourcePackLocalBind;
    private int resourcePackLocalPort;
    private String resourcePackLocalPublicUrl;

    private String databaseSqliteFile;

    private boolean slimeEnabled;
    private String slimeWorldsDirectory;
    private String slimeTemplateWorld;
    private String slimeWorldPrefix;
    private boolean slimeAllowMonsters;
    private boolean slimeAllowAnimals;
    private boolean slimePvp;
    private String fallbackWorld;
    private double fallbackX, fallbackY, fallbackZ;
    private float fallbackYaw, fallbackPitch;

    private String lobbySlimeTemplate;
    private double lobbySpawnX;
    private double lobbySpawnY;
    private double lobbySpawnZ;
    private float lobbySpawnYaw;
    private float lobbySpawnPitch;
    private int lobbyBoundMinX;
    private int lobbyBoundMinY;
    private int lobbyBoundMinZ;
    private int lobbyBoundMaxX;
    private int lobbyBoundMaxY;
    private int lobbyBoundMaxZ;
    private String lobbyParkourCourseId;
    private LobbyParkourDefinition lobbyParkour;

    private boolean hologramsEnabled;
    private String hologramsFile;
    private int hologramScanIntervalTicks;
    private float hologramDefaultViewRange;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        reload();
    }

    /**
     * Re-read {@code config.yml} from disk into this instance.
     * Callers must re-apply values to services that snapshot config at construction.
     */
    public void reload() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        minPlayers = c.getInt("party.min-players", 4);
        maxPlayers = c.getInt("party.max-players", 8);
        maxInstances = c.getInt("party.max-instances", 12);
        startCountdownSeconds = c.getInt("party.start-countdown-seconds", 5);
        maxTurns = c.getInt("party.max-turns", 10);
        startingCoins = c.getInt("party.starting-coins", 10);
        diceMin = c.getInt("board.dice-min", 1);
        diceMax = c.getInt("board.dice-max", 6);
        diceInteractSeconds = c.getInt("board.dice-interact-seconds", 5);
        diceSpinIntervalTicks = c.getInt("board.dice-spin-interval-ticks", 4);
        diceSpawnDistance = c.getDouble("board.dice-spawn-distance", 2.5);
        diceSpinScale = (float) c.getDouble("board.dice-spin-scale", 0.5);
        diceHatScale = (float) c.getDouble("board.dice-hat-scale", 0.55);
        hopUpVelocity = c.getDouble("board.hop-up-velocity", 1.70);
        hopRiseMaxSeconds = c.getDouble("board.hop-rise-max-seconds", 3.0);
        hopFallMaxSeconds = c.getDouble("board.hop-fall-max-seconds", 6.0);

        dummyDurationSeconds = c.getInt("minigame.dummy-duration-seconds", 5);
        List<Integer> rewards = c.getIntegerList("minigame.dummy-coin-rewards");
        dummyCoinRewards = rewards.isEmpty() ? List.of(10, 7, 5, 3) : new ArrayList<>(rewards);
        int revealIntervalMin = c.getInt("minigame.reveal-interval-min-ticks", 2);
        // Legacy key fallback if min not set in older configs
        if (!c.isSet("minigame.reveal-interval-min-ticks") && c.isSet("minigame.reveal-interval-ticks")) {
            revealIntervalMin = c.getInt("minigame.reveal-interval-ticks", 2);
        }
        minigameReveal = new MinigameRevealSettings(
                c.getInt("minigame.reveal-duration-ticks", 60),
                revealIntervalMin,
                c.getInt("minigame.reveal-interval-max-ticks", 14),
                c.getInt("minigame.reveal-expand-interval-ticks", 4),
                c.getInt("minigame.reveal-color-steps", 5),
                c.getInt("minigame.reveal-color-interval-ticks", 3)
        );

        hotPotatoSlimeTemplate = c.getString("minigame.hot_potato.slime-template", "hot_potato_arena");
        hotPotatoBombSeconds = c.getInt("minigame.hot_potato.bomb-seconds", 20);
        hotPotatoThrowVelocity = c.getDouble("minigame.hot_potato.throw-velocity", 0.9);
        hotPotatoMaxCycles = c.getInt("minigame.hot_potato.max-cycles", 10);
        hotPotatoArena = new MinigameArenaSpec(
                c.getString("minigame.hot_potato.arena.template", hotPotatoSlimeTemplate),
                c.getDouble("minigame.hot_potato.arena.spawn.x", Double.NaN),
                c.getDouble("minigame.hot_potato.arena.spawn.y", Double.NaN),
                c.getDouble("minigame.hot_potato.arena.spawn.z", Double.NaN),
                (float) c.getDouble("minigame.hot_potato.arena.spawn.yaw", 0),
                (float) c.getDouble("minigame.hot_potato.arena.spawn.pitch", 0),
                c.getInt("minigame.hot_potato.arena.boundary.minX", Integer.MAX_VALUE),
                c.getInt("minigame.hot_potato.arena.boundary.minY", Integer.MAX_VALUE),
                c.getInt("minigame.hot_potato.arena.boundary.minZ", Integer.MAX_VALUE),
                c.getInt("minigame.hot_potato.arena.boundary.maxX", Integer.MIN_VALUE),
                c.getInt("minigame.hot_potato.arena.boundary.maxY", Integer.MIN_VALUE),
                c.getInt("minigame.hot_potato.arena.boundary.maxZ", Integer.MIN_VALUE)
        );

        spleefTimeoutSeconds = c.getInt("minigame.spleef.timeout-seconds", 90);
        spleefFallY = c.getDouble("minigame.spleef.fall-y", 60.0);
        spleefSpawnRadius = c.getDouble("minigame.spleef.spawn-radius", 7.0);
        spleefPowerupSpawnSeconds = c.getInt("minigame.spleef.powerup.spawn-interval-seconds", 10);
        spleefMultishotSeconds = c.getInt("minigame.spleef.powerup.multishot-duration-seconds", 10);
        spleefPowerupItemModel = nullToEmpty(
                c.getString("minigame.spleef.powerup.item-model", "tnt_multishot")
        );
        if (spleefPowerupItemModel.isBlank()) {
            spleefPowerupItemModel = "tnt_multishot";
        }
        List<Material> configuredFloorMaterials = new ArrayList<>();
        for (String name : c.getStringList("minigame.spleef.floor-materials")) {
            Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                plugin.getLogger().warning("Unknown Spleef floor material: " + name);
                continue;
            }
            if (!configuredFloorMaterials.contains(material)) {
                configuredFloorMaterials.add(material);
            }
        }
        spleefFloorMaterials = configuredFloorMaterials.isEmpty()
                ? List.of(Material.TNT)
                : List.copyOf(configuredFloorMaterials);
        spleefArena = new MinigameArenaSpec(
                c.getString("minigame.spleef.arena.template", "spleef_arena"),
                c.getDouble("minigame.spleef.arena.spawn.x", Double.NaN),
                c.getDouble("minigame.spleef.arena.spawn.y", Double.NaN),
                c.getDouble("minigame.spleef.arena.spawn.z", Double.NaN),
                (float) c.getDouble("minigame.spleef.arena.spawn.yaw", 0),
                (float) c.getDouble("minigame.spleef.arena.spawn.pitch", 0),
                c.getInt("minigame.spleef.arena.boundary.minX", Integer.MAX_VALUE),
                c.getInt("minigame.spleef.arena.boundary.minY", Integer.MAX_VALUE),
                c.getInt("minigame.spleef.arena.boundary.minZ", Integer.MAX_VALUE),
                c.getInt("minigame.spleef.arena.boundary.maxX", Integer.MIN_VALUE),
                c.getInt("minigame.spleef.arena.boundary.maxY", Integer.MIN_VALUE),
                c.getInt("minigame.spleef.arena.boundary.maxZ", Integer.MIN_VALUE)
        );

        seamlessWorldChangeEnabled = c.getBoolean("seamless-world-change.enabled", true);

        resourcePackEnabled = c.getBoolean("resource-pack.enabled", true);
        resourcePackMode = c.getString("resource-pack.mode", "local");
        resourcePackSendOn = c.getString("resource-pack.send-on", "party");
        resourcePackRequired = c.getBoolean("resource-pack.required", false);
        resourcePackKickOnDecline = c.getBoolean("resource-pack.kick-on-decline", false);
        resourcePackSendDelayTicks = Math.max(0, c.getInt("resource-pack.send-delay-ticks", 20));
        resourcePackExternalUrl = nullToEmpty(c.getString("resource-pack.external.url"));
        resourcePackExternalSha1 = nullToEmpty(c.getString("resource-pack.external.sha1")).toLowerCase();
        resourcePackLocalSourceFolder = c.getString("resource-pack.local.source-folder", "resourcepack");
        resourcePackLocalZipName = c.getString("resource-pack.local.zip-name", "mcparty.zip");
        resourcePackLocalBind = c.getString("resource-pack.local.bind", "0.0.0.0");
        resourcePackLocalPort = c.getInt("resource-pack.local.port", 8163);
        resourcePackLocalPublicUrl = nullToEmpty(c.getString("resource-pack.local.public-url"));

        databaseSqliteFile = nullToEmpty(c.getString("database.sqlite-file", "parkour.db"));
        if (databaseSqliteFile.isBlank()) {
            databaseSqliteFile = "parkour.db";
        }

        slimeEnabled = c.getBoolean("slime.enabled", true);
        slimeWorldsDirectory = c.getString("slime.worlds-directory", "slime_worlds");
        slimeTemplateWorld = c.getString("slime.template-world", "party_board");
        slimeWorldPrefix = c.getString("slime.world-prefix", "party-");
        slimeAllowMonsters = c.getBoolean("slime.allow-monsters", false);
        slimeAllowAnimals = c.getBoolean("slime.allow-animals", false);
        slimePvp = c.getBoolean("slime.pvp", true);
        fallbackWorld = c.getString("slime.fallback.world", "world");
        fallbackX = c.getDouble("slime.fallback.x", 0.5);
        fallbackY = c.getDouble("slime.fallback.y", 64.0);
        fallbackZ = c.getDouble("slime.fallback.z", 0.5);
        fallbackYaw = (float) c.getDouble("slime.fallback.yaw", 0.0);
        fallbackPitch = (float) c.getDouble("slime.fallback.pitch", 0.0);

        lobbySlimeTemplate = c.getString("lobby.slime-template", "lobby_template");
        lobbySpawnX = c.getDouble("lobby.spawn.x", 0.5);
        lobbySpawnY = c.getDouble("lobby.spawn.y", 64.0);
        lobbySpawnZ = c.getDouble("lobby.spawn.z", 0.5);
        lobbySpawnYaw = (float) c.getDouble("lobby.spawn.yaw", 0.0);
        lobbySpawnPitch = (float) c.getDouble("lobby.spawn.pitch", 0.0);
        lobbyBoundMinX = c.getInt("lobby.boundary.minX", -50);
        lobbyBoundMinY = c.getInt("lobby.boundary.minY", 0);
        lobbyBoundMinZ = c.getInt("lobby.boundary.minZ", -50);
        lobbyBoundMaxX = c.getInt("lobby.boundary.maxX", 50);
        lobbyBoundMaxY = c.getInt("lobby.boundary.maxY", 256);
        lobbyBoundMaxZ = c.getInt("lobby.boundary.maxZ", 50);
        lobbyParkourCourseId = nullToEmpty(c.getString("lobby.parkour.course-id", "lobby"));
        if (!lobbyParkourCourseId.matches("[a-z0-9_-]{1,64}")) {
            plugin.getLogger().warning("Invalid lobby.parkour.course-id; using 'lobby'");
            lobbyParkourCourseId = "lobby";
        }
        String parkourWorld = nullToEmpty(c.getString("lobby.parkour.world"));
        if (parkourWorld.isBlank()) {
            parkourWorld = fallbackWorld;
        }
        lobbyParkour = new LobbyParkourDefinition(
                parkourWorld,
                point(c, "lobby.parkour.start"),
                c.getMapList("lobby.parkour.checkpoints").stream()
                        .map(PluginConfig::point)
                        .filter(java.util.Objects::nonNull)
                        .toList(),
                point(c, "lobby.parkour.goal"),
                point(c, "lobby.parkour.leaderboard")
        );

        hologramsEnabled = c.getBoolean("holograms.enabled", true);
        hologramsFile = c.getString("holograms.file", "holograms.yml");
        hologramScanIntervalTicks = Math.max(1, c.getInt("holograms.view-scan-interval-ticks", 5));
        hologramDefaultViewRange = (float) c.getDouble("holograms.default-view-range", 32.0);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    public int minPlayers() { return minPlayers; }
    public int maxPlayers() { return maxPlayers; }
    public int maxInstances() { return maxInstances; }
    public int startCountdownSeconds() { return startCountdownSeconds; }
    public int maxTurns() { return maxTurns; }
    public int startingCoins() { return startingCoins; }
    public int diceMin() { return diceMin; }
    public int diceMax() { return diceMax; }
    public int diceInteractSeconds() { return diceInteractSeconds; }
    public int diceSpinIntervalTicks() { return diceSpinIntervalTicks; }
    public double diceSpawnDistance() { return diceSpawnDistance; }
    public float diceSpinScale() { return diceSpinScale; }
    public float diceHatScale() { return diceHatScale; }
    public double hopUpVelocity() { return hopUpVelocity; }
    public double hopRiseMaxSeconds() { return hopRiseMaxSeconds; }
    public double hopFallMaxSeconds() { return hopFallMaxSeconds; }
    public int dummyDurationSeconds() { return dummyDurationSeconds; }
    public List<Integer> dummyCoinRewards() { return dummyCoinRewards; }
    public MinigameRevealSettings minigameReveal() { return minigameReveal; }

    public String hotPotatoSlimeTemplate() { return hotPotatoSlimeTemplate; }
    public int hotPotatoBombSeconds() { return hotPotatoBombSeconds; }
    public double hotPotatoThrowVelocity() { return hotPotatoThrowVelocity; }
    public int hotPotatoMaxCycles() { return hotPotatoMaxCycles; }
    public MinigameArenaSpec hotPotatoArena() { return hotPotatoArena; }

    public int spleefTimeoutSeconds() { return spleefTimeoutSeconds; }
    public double spleefFallY() { return spleefFallY; }
    public double spleefSpawnRadius() { return spleefSpawnRadius; }
    public List<Material> spleefFloorMaterials() { return spleefFloorMaterials; }
    public MinigameArenaSpec spleefArena() { return spleefArena; }
    public int spleefPowerupSpawnSeconds() { return spleefPowerupSpawnSeconds; }
    public int spleefMultishotSeconds() { return spleefMultishotSeconds; }
    public String spleefPowerupItemModel() { return spleefPowerupItemModel; }

    public boolean seamlessWorldChangeEnabled() { return seamlessWorldChangeEnabled; }

    public boolean resourcePackEnabled() { return resourcePackEnabled; }
    public String resourcePackMode() { return resourcePackMode; }
    public String resourcePackSendOn() { return resourcePackSendOn; }
    public boolean resourcePackRequired() { return resourcePackRequired; }
    public boolean resourcePackKickOnDecline() { return resourcePackKickOnDecline; }
    public int resourcePackSendDelayTicks() { return resourcePackSendDelayTicks; }
    public String resourcePackExternalUrl() { return resourcePackExternalUrl; }
    public String resourcePackExternalSha1() { return resourcePackExternalSha1; }
    public String resourcePackLocalSourceFolder() { return resourcePackLocalSourceFolder; }
    public String resourcePackLocalZipName() { return resourcePackLocalZipName; }
    public String resourcePackLocalBind() { return resourcePackLocalBind; }
    public int resourcePackLocalPort() { return resourcePackLocalPort; }
    public String resourcePackLocalPublicUrl() { return resourcePackLocalPublicUrl; }

    public String databaseSqliteFile() { return databaseSqliteFile; }

    public boolean slimeEnabled() { return slimeEnabled; }
    public String slimeWorldsDirectory() { return slimeWorldsDirectory; }
    public String slimeTemplateWorld() { return slimeTemplateWorld; }
    public String slimeWorldPrefix() { return slimeWorldPrefix; }
    public boolean slimeAllowMonsters() { return slimeAllowMonsters; }
    public boolean slimeAllowAnimals() { return slimeAllowAnimals; }
    public boolean slimePvp() { return slimePvp; }
    public String fallbackWorld() { return fallbackWorld; }
    public double fallbackX() { return fallbackX; }
    public double fallbackY() { return fallbackY; }
    public double fallbackZ() { return fallbackZ; }
    public float fallbackYaw() { return fallbackYaw; }
    public float fallbackPitch() { return fallbackPitch; }

    public String lobbySlimeTemplate() { return lobbySlimeTemplate; }
    public double lobbySpawnX() { return lobbySpawnX; }
    public double lobbySpawnY() { return lobbySpawnY; }
    public double lobbySpawnZ() { return lobbySpawnZ; }
    public float lobbySpawnYaw() { return lobbySpawnYaw; }
    public float lobbySpawnPitch() { return lobbySpawnPitch; }
    public int lobbyBoundMinX() { return lobbyBoundMinX; }
    public int lobbyBoundMinY() { return lobbyBoundMinY; }
    public int lobbyBoundMinZ() { return lobbyBoundMinZ; }
    public int lobbyBoundMaxX() { return lobbyBoundMaxX; }
    public int lobbyBoundMaxY() { return lobbyBoundMaxY; }
    public int lobbyBoundMaxZ() { return lobbyBoundMaxZ; }
    public String lobbyParkourCourseId() { return lobbyParkourCourseId; }
    public LobbyParkourDefinition lobbyParkour() { return lobbyParkour; }

    public boolean hologramsEnabled() { return hologramsEnabled; }
    public String hologramsFile() { return hologramsFile; }
    public int hologramScanIntervalTicks() { return hologramScanIntervalTicks; }
    public float hologramDefaultViewRange() { return hologramDefaultViewRange; }

    public void setLobbySpawn(Location location) {
        lobbySpawnX = location.getX();
        lobbySpawnY = location.getY();
        lobbySpawnZ = location.getZ();
        lobbySpawnYaw = location.getYaw();
        lobbySpawnPitch = location.getPitch();
        plugin.getConfig().set("lobby.spawn.x", lobbySpawnX);
        plugin.getConfig().set("lobby.spawn.y", lobbySpawnY);
        plugin.getConfig().set("lobby.spawn.z", lobbySpawnZ);
        plugin.getConfig().set("lobby.spawn.yaw", lobbySpawnYaw);
        plugin.getConfig().set("lobby.spawn.pitch", lobbySpawnPitch);
        plugin.saveConfig();
    }

    public void setLobbyParkourStart(String worldName, LobbyParkourPoint point) {
        saveLobbyParkourWorld(worldName);
        plugin.getConfig().set("lobby.parkour.start", pointMap(point));
        plugin.saveConfig();
        lobbyParkour = new LobbyParkourDefinition(worldName, point, lobbyParkour.checkpoints(), lobbyParkour.goal(), lobbyParkour.leaderboard());
    }

    public void addLobbyParkourCheckpoint(LobbyParkourPoint point) {
        List<LobbyParkourPoint> checkpoints = new ArrayList<>(lobbyParkour.checkpoints());
        checkpoints.add(point);
        saveLobbyParkourCheckpoints(checkpoints);
    }

    public boolean removeLobbyParkourCheckpoint(int index) {
        List<LobbyParkourPoint> checkpoints = new ArrayList<>(lobbyParkour.checkpoints());
        if (index < 0 || index >= checkpoints.size()) {
            return false;
        }
        checkpoints.remove(index);
        saveLobbyParkourCheckpoints(checkpoints);
        return true;
    }

    public void setLobbyParkourGoal(String worldName, LobbyParkourPoint point) {
        saveLobbyParkourWorld(worldName);
        plugin.getConfig().set("lobby.parkour.goal", pointMap(point));
        plugin.saveConfig();
        lobbyParkour = new LobbyParkourDefinition(worldName, lobbyParkour.start(), lobbyParkour.checkpoints(), point, lobbyParkour.leaderboard());
    }

    public void setLobbyParkourLeaderboard(String worldName, LobbyParkourPoint point) {
        saveLobbyParkourWorld(worldName);
        plugin.getConfig().set("lobby.parkour.leaderboard", pointMap(point));
        plugin.saveConfig();
        lobbyParkour = new LobbyParkourDefinition(worldName, lobbyParkour.start(), lobbyParkour.checkpoints(), lobbyParkour.goal(), point);
    }

    public void clearLobbyParkour() {
        plugin.getConfig().set("lobby.parkour", null);
        plugin.saveConfig();
        lobbyParkour = new LobbyParkourDefinition("", null, List.of(), null, null);
    }

    private void saveLobbyParkourCheckpoints(List<LobbyParkourPoint> checkpoints) {
        plugin.getConfig().set("lobby.parkour.checkpoints", checkpoints.stream().map(PluginConfig::pointMap).toList());
        plugin.saveConfig();
        lobbyParkour = new LobbyParkourDefinition(
                lobbyParkour.fallbackWorld(), lobbyParkour.start(), checkpoints, lobbyParkour.goal(), lobbyParkour.leaderboard()
        );
    }

    private void saveLobbyParkourWorld(String worldName) {
        plugin.getConfig().set("lobby.parkour.world", worldName);
    }

    private static LobbyParkourPoint point(FileConfiguration config, String path) {
        if (!config.isConfigurationSection(path)) {
            return null;
        }
        return new LobbyParkourPoint(config.getInt(path + ".x"), config.getInt(path + ".y"), config.getInt(path + ".z"));
    }

    private static LobbyParkourPoint point(Map<?, ?> map) {
        Object x = map.get("x");
        Object y = map.get("y");
        Object z = map.get("z");
        if (!(x instanceof Number xNumber) || !(y instanceof Number yNumber) || !(z instanceof Number zNumber)) {
            return null;
        }
        return new LobbyParkourPoint(xNumber.intValue(), yNumber.intValue(), zNumber.intValue());
    }

    private static Map<String, Integer> pointMap(LobbyParkourPoint point) {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("x", point.x());
        out.put("y", point.y());
        out.put("z", point.z());
        return out;
    }
}
