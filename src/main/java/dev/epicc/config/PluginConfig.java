package dev.epicc.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

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
    private float diceDisplayScale;
    private float diceSpinScale;
    private float diceHatScale;
    private double hopUpVelocity;
    private double hopRiseMaxSeconds;
    private double hopFallMaxSeconds;
    private int dummyDurationSeconds;
    private List<Integer> dummyCoinRewards;
    private int minigameRevealDurationTicks;
    private int minigameRevealIntervalMinTicks;
    private int minigameRevealIntervalMaxTicks;
    private int minigameRevealExpandIntervalTicks;
    private int minigameRevealColorSteps;
    private int minigameRevealColorIntervalTicks;

    private String hotPotatoSlimeTemplate;
    private int hotPotatoBombSeconds;
    private double hotPotatoThrowVelocity;
    private int hotPotatoMaxCycles;

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

    private boolean slimeEnabled;
    private String slimeWorldsDirectory;
    private String slimeTemplateWorld;
    private String slimeWorldPrefix;
    private boolean slimeAllowMonsters;
    private boolean slimeAllowAnimals;
    private boolean slimePvp;

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
        diceSpawnDistance = c.getDouble("board.dice-spawn-distance", 2.0);
        diceDisplayScale = (float) c.getDouble("board.dice-display-scale", 1.0);
        diceSpinScale = (float) c.getDouble("board.dice-spin-scale", 0.5);
        diceHatScale = (float) c.getDouble("board.dice-hat-scale", 0.55);
        hopUpVelocity = c.getDouble("board.hop-up-velocity", 1.70);
        hopRiseMaxSeconds = c.getDouble("board.hop-rise-max-seconds", 3.0);
        hopFallMaxSeconds = c.getDouble("board.hop-fall-max-seconds", 6.0);

        dummyDurationSeconds = c.getInt("minigame.dummy-duration-seconds", 5);
        List<Integer> rewards = c.getIntegerList("minigame.dummy-coin-rewards");
        dummyCoinRewards = rewards.isEmpty() ? List.of(10, 7, 5, 3) : new ArrayList<>(rewards);
        minigameRevealDurationTicks = c.getInt("minigame.reveal-duration-ticks", 60);
        minigameRevealIntervalMinTicks = c.getInt("minigame.reveal-interval-min-ticks", 2);
        // Legacy key fallback if min not set in older configs
        if (!c.isSet("minigame.reveal-interval-min-ticks") && c.isSet("minigame.reveal-interval-ticks")) {
            minigameRevealIntervalMinTicks = c.getInt("minigame.reveal-interval-ticks", 2);
        }
        minigameRevealIntervalMaxTicks = c.getInt("minigame.reveal-interval-max-ticks", 14);
        minigameRevealExpandIntervalTicks = c.getInt("minigame.reveal-expand-interval-ticks", 4);
        minigameRevealColorSteps = c.getInt("minigame.reveal-color-steps", 5);
        minigameRevealColorIntervalTicks = c.getInt("minigame.reveal-color-interval-ticks", 3);

        hotPotatoSlimeTemplate = c.getString("minigame.hot_potato.slime-template", "hot_potato_arena");
        hotPotatoBombSeconds = c.getInt("minigame.hot_potato.bomb-seconds", 20);
        hotPotatoThrowVelocity = c.getDouble("minigame.hot_potato.throw-velocity", 0.9);
        hotPotatoMaxCycles = c.getInt("minigame.hot_potato.max-cycles", 10);

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

        slimeEnabled = c.getBoolean("slime.enabled", true);
        slimeWorldsDirectory = c.getString("slime.worlds-directory", "slime_worlds");
        slimeTemplateWorld = c.getString("slime.template-world", "party_board");
        slimeWorldPrefix = c.getString("slime.world-prefix", "party-");
        slimeAllowMonsters = c.getBoolean("slime.allow-monsters", false);
        slimeAllowAnimals = c.getBoolean("slime.allow-animals", false);
        slimePvp = c.getBoolean("slime.pvp", true);
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
    public float diceDisplayScale() { return diceDisplayScale; }
    public float diceSpinScale() { return diceSpinScale; }
    public float diceHatScale() { return diceHatScale; }
    public double hopUpVelocity() { return hopUpVelocity; }
    public double hopRiseMaxSeconds() { return hopRiseMaxSeconds; }
    public double hopFallMaxSeconds() { return hopFallMaxSeconds; }
    public int dummyDurationSeconds() { return dummyDurationSeconds; }
    public List<Integer> dummyCoinRewards() { return dummyCoinRewards; }
    public int minigameRevealDurationTicks() { return minigameRevealDurationTicks; }
    public int minigameRevealIntervalMinTicks() { return minigameRevealIntervalMinTicks; }
    public int minigameRevealIntervalMaxTicks() { return minigameRevealIntervalMaxTicks; }
    public int minigameRevealExpandIntervalTicks() { return minigameRevealExpandIntervalTicks; }
    public int minigameRevealColorSteps() { return minigameRevealColorSteps; }
    public int minigameRevealColorIntervalTicks() { return minigameRevealColorIntervalTicks; }

    public String hotPotatoSlimeTemplate() { return hotPotatoSlimeTemplate; }
    public int hotPotatoBombSeconds() { return hotPotatoBombSeconds; }
    public double hotPotatoThrowVelocity() { return hotPotatoThrowVelocity; }
    public int hotPotatoMaxCycles() { return hotPotatoMaxCycles; }

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

    public boolean slimeEnabled() { return slimeEnabled; }
    public String slimeWorldsDirectory() { return slimeWorldsDirectory; }
    public String slimeTemplateWorld() { return slimeTemplateWorld; }
    public String slimeWorldPrefix() { return slimeWorldPrefix; }
    public boolean slimeAllowMonsters() { return slimeAllowMonsters; }
    public boolean slimeAllowAnimals() { return slimeAllowAnimals; }
    public boolean slimePvp() { return slimePvp; }
}
