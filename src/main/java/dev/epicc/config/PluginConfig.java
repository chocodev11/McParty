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

        slimeEnabled = c.getBoolean("slime.enabled", true);
        slimeWorldsDirectory = c.getString("slime.worlds-directory", "slime_worlds");
        slimeTemplateWorld = c.getString("slime.template-world", "party_board");
        slimeWorldPrefix = c.getString("slime.world-prefix", "party-");
        slimeAllowMonsters = c.getBoolean("slime.allow-monsters", false);
        slimeAllowAnimals = c.getBoolean("slime.allow-animals", false);
        slimePvp = c.getBoolean("slime.pvp", true);
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

    public boolean slimeEnabled() { return slimeEnabled; }
    public String slimeWorldsDirectory() { return slimeWorldsDirectory; }
    public String slimeTemplateWorld() { return slimeTemplateWorld; }
    public String slimeWorldPrefix() { return slimeWorldPrefix; }
    public boolean slimeAllowMonsters() { return slimeAllowMonsters; }
    public boolean slimeAllowAnimals() { return slimeAllowAnimals; }
    public boolean slimePvp() { return slimePvp; }
}
