package dev.epicc.config;

import org.bukkit.Material;
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
    private final Material wallMaterial;
    private final int wallHeight;
    private final int dummyDurationSeconds;
    private final List<Integer> dummyCoinRewards;

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

        Material mat = Material.matchMaterial(c.getString("containment.wall-material", "BARRIER"));
        wallMaterial = mat != null ? mat : Material.BARRIER;
        wallHeight = c.getInt("containment.wall-height", 5);

        dummyDurationSeconds = c.getInt("minigame.dummy-duration-seconds", 5);
        List<Integer> rewards = c.getIntegerList("minigame.dummy-coin-rewards");
        dummyCoinRewards = rewards.isEmpty() ? List.of(10, 7, 5, 3) : new ArrayList<>(rewards);

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
    public Material wallMaterial() { return wallMaterial; }
    public int wallHeight() { return wallHeight; }
    public int dummyDurationSeconds() { return dummyDurationSeconds; }
    public List<Integer> dummyCoinRewards() { return dummyCoinRewards; }

    public boolean slimeEnabled() { return slimeEnabled; }
    public String slimeWorldsDirectory() { return slimeWorldsDirectory; }
    public String slimeTemplateWorld() { return slimeTemplateWorld; }
    public String slimeWorldPrefix() { return slimeWorldPrefix; }
    public boolean slimeAllowMonsters() { return slimeAllowMonsters; }
    public boolean slimeAllowAnimals() { return slimeAllowAnimals; }
    public boolean slimePvp() { return slimePvp; }
}
