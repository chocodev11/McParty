package dev.epicc.slime;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.exceptions.CorruptedWorldException;
import com.infernalsuite.asp.api.exceptions.NewerFormatException;
import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.infernalsuite.asp.loaders.file.FileLoader;
import dev.epicc.seamless.SeamlessWorldChangeService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Loads / unloads per-party slime worlds via AdvancedSlimePaper.
 * Each board slot names its own template; that file is read read-only, then cloned
 * under a unique name for the party instance.
 */
public final class SlimeWorldService {

    private final JavaPlugin plugin;
    private final boolean enabled;
    /** Used only when a slot has no {@code slime-template} (legacy slots). */
    private final String defaultTemplate;
    private final String worldPrefix;
    private final boolean allowMonsters;
    private final boolean allowAnimals;
    private final boolean pvp;
    private final SeamlessWorldChangeService seamless;

    private AdvancedSlimePaperAPI asp;
    private FileLoader loader;
    private File worldsDir;
    private final ConcurrentHashMap<UUID, String> instanceWorlds = new ConcurrentHashMap<>();

    public SlimeWorldService(
            JavaPlugin plugin,
            boolean enabled,
            String worldsDirectory,
            String defaultTemplate,
            String worldPrefix,
            boolean allowMonsters,
            boolean allowAnimals,
            boolean pvp,
            SeamlessWorldChangeService seamless
    ) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.defaultTemplate = defaultTemplate;
        this.worldPrefix = worldPrefix;
        this.allowMonsters = allowMonsters;
        this.allowAnimals = allowAnimals;
        this.pvp = pvp;
        this.seamless = seamless;

        if (!enabled) {
            plugin.getLogger().info("Slime world management disabled in config.");
            return;
        }

        try {
            this.asp = AdvancedSlimePaperAPI.instance();
            this.worldsDir = new File(plugin.getDataFolder(), worldsDirectory);
            this.loader = new FileLoader(worldsDir);
            plugin.getLogger().info("ASP slime loader ready (dir=" + worldsDir.getAbsolutePath()
                    + ", default-template=" + defaultTemplate + ")");
        } catch (Throwable t) {
            this.asp = null;
            this.loader = null;
            this.worldsDir = null;
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to init AdvancedSlimePaper API. Run on AdvancedSlimePaper and check slime config.", t);
        }
    }

    public boolean isEnabled() {
        return enabled && asp != null && loader != null;
    }

    public boolean isReady() {
        return isEnabled();
    }

    public String defaultTemplate() {
        return defaultTemplate;
    }

    /**
     * Resolves the ASP template name for a slot: slot field if set, else config default.
     */
    public String resolveTemplate(String slotTemplate) {
        if (slotTemplate != null && !slotTemplate.isBlank()) {
            return slotTemplate.trim();
        }
        return defaultTemplate;
    }

    /**
     * Basenames of {@code *.slime} files in the configured worlds directory (for tab-complete).
     */
    public List<String> listTemplates() {
        if (worldsDir == null || !worldsDir.isDirectory()) {
            return List.of();
        }
        File[] files = worldsDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".slime"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        List<String> names = new ArrayList<>(files.length);
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            String n = f.getName();
            names.add(n.substring(0, n.length() - ".slime".length()));
        }
        return names;
    }

    public Optional<String> worldOf(UUID instanceId) {
        return Optional.ofNullable(instanceWorlds.get(instanceId));
    }

    /** True if this Bukkit world is a live per-party slime clone managed by this service. */
    public boolean isInstanceWorld(World world) {
        if (world == null || instanceWorlds.isEmpty()) {
            return false;
        }
        String name = world.getName();
        return instanceWorlds.containsValue(name);
    }

    /**
     * Read template (async-safe), clone, then load on the main thread.
     * Returns the live Bukkit world, or empty on failure.
     */
    public Optional<World> loadForInstance(UUID instanceId, String templateName) {
        if (!isReady()) {
            plugin.getLogger().warning("Slime service not ready — cannot load instance world.");
            return Optional.empty();
        }
        if (instanceWorlds.containsKey(instanceId)) {
            World existing = Bukkit.getWorld(instanceWorlds.get(instanceId));
            if (existing != null) {
                return Optional.of(existing);
            }
            instanceWorlds.remove(instanceId);
        }

        String worldName = worldPrefix + shortId(instanceId);
        if (Bukkit.getWorld(worldName) != null) {
            worldName = worldPrefix + instanceId.toString().replace("-", "").substring(0, 12);
        }

        String template = resolveTemplate(templateName);
        try {
            SlimeWorld templateWorld = asp.readWorld(loader, template, true, defaultProperties());
            SlimeWorld clone = templateWorld.clone(worldName);

            if (!Bukkit.isPrimaryThread()) {
                throw new IllegalStateException(
                        "loadWorld must be called on the main thread; call loadForInstance on main after async prepare, or use prepareClone + loadClone."
                );
            }

            SlimeWorldInstance loaded = asp.loadWorld(clone, true);
            World bukkit = loaded.getBukkitWorld();
            instanceWorlds.put(instanceId, bukkit.getName());
            plugin.getLogger().info("Loaded slime world '" + bukkit.getName()
                    + "' (template=" + template + ") for party " + shortId(instanceId));
            return Optional.of(bukkit);
        } catch (UnknownWorldException e) {
            plugin.getLogger().severe("Template slime world not found: '" + template
                    + ".slime' in the configured worlds directory.");
            return Optional.empty();
        } catch (IOException | CorruptedWorldException | NewerFormatException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load slime world for party " + shortId(instanceId), e);
            return Optional.empty();
        }
    }

    /**
     * Async-safe: read the named template and clone in memory (does not register the world).
     */
    public Optional<SlimeWorld> prepareClone(UUID instanceId, String templateName) {
        if (!isReady()) {
            return Optional.empty();
        }
        String worldName = worldPrefix + shortId(instanceId);
        if (Bukkit.getWorld(worldName) != null || asp.getLoadedWorld(worldName) != null) {
            worldName = worldPrefix + instanceId.toString().replace("-", "").substring(0, 12);
        }
        String template = resolveTemplate(templateName);
        try {
            SlimeWorld templateWorld = asp.readWorld(loader, template, true, defaultProperties());
            return Optional.of(templateWorld.clone(worldName));
        } catch (UnknownWorldException e) {
            plugin.getLogger().severe("Template slime world not found: '" + template + ".slime'");
            return Optional.empty();
        } catch (IOException | CorruptedWorldException | NewerFormatException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to prepare slime clone for party " + shortId(instanceId)
                    + " (template=" + template + ")", e);
            return Optional.empty();
        }
    }

    /**
     * Main-thread only: register a prepared clone with the server.
     */
    public Optional<World> loadClone(UUID instanceId, SlimeWorld clone) {
        if (!isReady()) {
            return Optional.empty();
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("loadClone must run on the main thread");
        }
        try {
            SlimeWorldInstance loaded = asp.loadWorld(clone, true);
            World bukkit = loaded.getBukkitWorld();
            instanceWorlds.put(instanceId, bukkit.getName());
            plugin.getLogger().info("Loaded slime world '" + bukkit.getName() + "' for party " + shortId(instanceId));
            return Optional.of(bukkit);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load prepared slime clone for party " + shortId(instanceId), e);
            return Optional.empty();
        }
    }

    /**
     * Teleport any remaining players out, then unload without saving.
     */
    public void unloadForInstance(UUID instanceId) {
        String worldName = instanceWorlds.remove(instanceId);
        if (worldName == null) {
            return;
        }
        unloadWorld(worldName);
    }

    public void unloadAll() {
        for (UUID id : instanceWorlds.keySet().toArray(UUID[]::new)) {
            unloadForInstance(id);
        }
    }

    private void unloadWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return;
        }

        Location fallback = fallbackLocation(world);
        for (Player player : world.getPlayers()) {
            seamless.teleport(player, fallback);
        }

        boolean ok = Bukkit.unloadWorld(world, false);
        if (ok) {
            plugin.getLogger().info("Unloaded slime world '" + worldName + "'");
        } else {
            plugin.getLogger().warning("Could not unload slime world '" + worldName + "'");
        }
    }

    private Location fallbackLocation(World leaving) {
        for (World w : Bukkit.getWorlds()) {
            if (w != leaving) {
                return w.getSpawnLocation();
            }
        }
        return leaving.getSpawnLocation();
    }

    private SlimePropertyMap defaultProperties() {
        SlimePropertyMap map = new SlimePropertyMap();
        map.setValue(SlimeProperties.ALLOW_MONSTERS, allowMonsters);
        map.setValue(SlimeProperties.ALLOW_ANIMALS, allowAnimals);
        map.setValue(SlimeProperties.PVP, pvp);
        map.setValue(SlimeProperties.DIFFICULTY, "normal");
        return map;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8).toLowerCase(Locale.ROOT);
    }
}
