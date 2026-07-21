package dev.epicc.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class PluginConfig {

    private final int minPlayers;
    private final int maxPlayers;
    private final int maxInstances;
    private final int startCountdownSeconds;
    private final int maxTurns;
    private final int startingCoins;
    private final int diceMin;
    private final int diceMax;
    private final int diceInteractSeconds;
    private final int diceSpinIntervalTicks;
    private final double diceSpawnDistance;
    private final float diceDisplayScale;
    private final float diceHatScale;
    private final double hopHeight;
    private final double hopRiseSeconds;
    private final double hopFallMaxSeconds;
    private final int dummyDurationSeconds;
    private final List<Integer> dummyCoinRewards;
    private final int minigameRevealDurationTicks;
    private final int minigameRevealIntervalTicks;

    private final boolean seamlessWorldChangeEnabled;

    private final boolean resourcePackEnabled;
    private final String resourcePackMode;
    private final String resourcePackSendOn;
    private final boolean resourcePackRequired;
    private final String resourcePackPrompt;
    private final boolean resourcePackKickOnDecline;
    private final String resourcePackKickMessage;
    private final int resourcePackSendDelayTicks;
    private final String resourcePackExternalUrl;
    private final String resourcePackExternalSha1;
    private final String resourcePackLocalSourceFolder;
    private final String resourcePackLocalZipName;
    private final String resourcePackLocalBind;
    private final int resourcePackLocalPort;
    private final String resourcePackLocalPublicUrl;

    private final boolean slimeEnabled;
    private final String slimeWorldsDirectory;
    private final String slimeTemplateWorld;
    private final String slimeWorldPrefix;
    private final boolean slimeAllowMonsters;
    private final boolean slimeAllowAnimals;
    private final boolean slimePvp;

    public PluginConfig(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
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
        diceSpinIntervalTicks = c.getInt("board.dice-spin-interval-ticks", 2);
        diceSpawnDistance = c.getDouble("board.dice-spawn-distance", 2.0);
        diceDisplayScale = (float) c.getDouble("board.dice-display-scale", 0.6);
        diceHatScale = (float) c.getDouble("board.dice-hat-scale", 0.35);
        hopHeight = c.getDouble("board.hop-height", 5.0);
        hopRiseSeconds = c.getDouble("board.hop-rise-seconds", 0.4);
        hopFallMaxSeconds = c.getDouble("board.hop-fall-max-seconds", 4.0);

        dummyDurationSeconds = c.getInt("minigame.dummy-duration-seconds", 5);
        List<Integer> rewards = c.getIntegerList("minigame.dummy-coin-rewards");
        dummyCoinRewards = rewards.isEmpty() ? List.of(10, 7, 5, 3) : new ArrayList<>(rewards);
        minigameRevealDurationTicks = c.getInt("minigame.reveal-duration-ticks", 60);
        minigameRevealIntervalTicks = c.getInt("minigame.reveal-interval-ticks", 4);

        seamlessWorldChangeEnabled = c.getBoolean("seamless-world-change.enabled", true);

        resourcePackEnabled = c.getBoolean("resource-pack.enabled", true);
        resourcePackMode = c.getString("resource-pack.mode", "local");
        resourcePackSendOn = c.getString("resource-pack.send-on", "party");
        resourcePackRequired = c.getBoolean("resource-pack.required", false);
        resourcePackPrompt = c.getString(
                "resource-pack.prompt",
                "McParty needs this pack for custom dice models."
        );
        resourcePackKickOnDecline = c.getBoolean("resource-pack.kick-on-decline", false);
        resourcePackKickMessage = c.getString(
                "resource-pack.kick-message",
                "You must accept the McParty resource pack."
        );
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
    public float diceHatScale() { return diceHatScale; }
    public double hopHeight() { return hopHeight; }
    public double hopRiseSeconds() { return hopRiseSeconds; }
    public double hopFallMaxSeconds() { return hopFallMaxSeconds; }
    public int dummyDurationSeconds() { return dummyDurationSeconds; }
    public List<Integer> dummyCoinRewards() { return dummyCoinRewards; }
    public int minigameRevealDurationTicks() { return minigameRevealDurationTicks; }
    public int minigameRevealIntervalTicks() { return minigameRevealIntervalTicks; }

    public boolean seamlessWorldChangeEnabled() { return seamlessWorldChangeEnabled; }

    public boolean resourcePackEnabled() { return resourcePackEnabled; }
    public String resourcePackMode() { return resourcePackMode; }
    public String resourcePackSendOn() { return resourcePackSendOn; }
    public boolean resourcePackRequired() { return resourcePackRequired; }
    public String resourcePackPrompt() { return resourcePackPrompt; }
    public boolean resourcePackKickOnDecline() { return resourcePackKickOnDecline; }
    public String resourcePackKickMessage() { return resourcePackKickMessage; }
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
