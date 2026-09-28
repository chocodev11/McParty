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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

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

    private final WorldLoadQueue<Optional<SlimeWorld>, Optional<World>> loads;
    private final Map<String, UUID> pendingUnloads = new java.util.HashMap<>();
    private BukkitTask loadTask;
    private BukkitTask unloadTask;
    private volatile boolean closed;

    private AdvancedSlimePaperAPI asp;
    private FileLoader loader;
    private File worldsDir;
    private final ConcurrentHashMap<UUID, Set<String>> instanceWorlds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, World>> templateWorlds = new ConcurrentHashMap<>();

    public SlimeWorldService(
            JavaPlugin plugin,
            boolean enabled,
            String worldsDirectory,
            String defaultTemplate,
            String worldPrefix,
            boolean allowMonsters,
            boolean allowAnimals,
            boolean pvp
    ) {
        this.plugin = plugin;
        this.loads = new WorldLoadQueue<>(
                task -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task),
                2, 64, Optional.empty(),
                exception -> plugin.getLogger().log(Level.SEVERE, "Failed to load queued slime world", exception));
        this.enabled = enabled;
        this.defaultTemplate = defaultTemplate;
        this.worldPrefix = worldPrefix;
        this.allowMonsters = allowMonsters;
        this.allowAnimals = allowAnimals;
        this.pvp = pvp;

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
        return !closed && isEnabled();
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

    /** True if this Bukkit world is a live per-party slime clone managed by this service. */
    public boolean isInstanceWorld(World world) {
        if (world == null || instanceWorlds.isEmpty()) {
            return false;
        }
        String name = world.getName();
        return instanceWorlds.values().stream().anyMatch(set -> set.contains(name));
    }

    public Optional<World> getLoadedWorld(UUID instanceId, String templateName) {
        if (instanceId == null || templateName == null) {
            return Optional.empty();
        }
        String template = resolveTemplate(templateName);
        Map<String, World> map = templateWorlds.get(instanceId);
        if (map != null) {
            World existing = map.get(template);
            if (existing != null && Bukkit.getWorld(existing.getName()) != null) {
                return Optional.of(existing);
            }
        }
        return Optional.empty();
    }

    /**
     * Read template (async-safe), clone, then load on the main thread.
     * Returns the live Bukkit world, or empty on failure.
     */
    public Optional<World> loadForInstance(UUID instanceId, String templateName) {
        Optional<World> existing = getLoadedWorld(instanceId, templateName);
        if (existing.isPresent()) {
            return existing;
        }
        Optional<SlimeWorld> clone = prepareClone(instanceId, templateName);
        if (clone.isEmpty()) {
            return Optional.empty();
        }
        return loadClone(instanceId, templateName, clone.get());
    }

    /**
     * Read template asynchronously, then load on the main thread, returning a Future.
     */
    public CompletableFuture<Optional<World>> loadCloneAsync(UUID instanceId, String templateName) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("loadCloneAsync must be requested on the main thread");
        }
        if (!isReady()) return CompletableFuture.completedFuture(Optional.empty());
        Optional<World> existing = getLoadedWorld(instanceId, templateName);
        if (existing.isPresent()) return CompletableFuture.completedFuture(existing);
        CompletableFuture<Optional<World>> future = loads.submit(instanceId,
                () -> prepareClone(instanceId, templateName),
                clone -> clone.flatMap(prepared -> loadClone(instanceId, templateName, prepared)),
                result -> result.ifPresent(world -> unloadWorldForInstance(instanceId, world)));
        if (loadTask == null) {
            loadTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                loads.tick();
                if (loads.isIdle() && loadTask != null) {
                    loadTask.cancel();
                    loadTask = null;
                }
            }, 1L, 1L);
        }
        return future;
    }

    public void cancelLoads(UUID instanceId) {
        loads.cancel(instanceId);
    }

    /**
     * Async-safe: read the named template and clone in memory (does not register the world).
     */
    public Optional<SlimeWorld> prepareClone(UUID instanceId, String templateName) {
        if (!isReady()) {
            return Optional.empty();
        }
        String template = resolveTemplate(templateName);
        String worldName = worldPrefix + instanceId.toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().substring(0, 8) + "-" + template;
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
    public Optional<World> loadClone(UUID instanceId, String templateName, SlimeWorld clone) {
        if (!isReady()) {
            return Optional.empty();
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("loadClone must run on the main thread");
        }
        try {
            SlimeWorldInstance loaded = asp.loadWorld(clone, true);
            World bukkit = loaded.getBukkitWorld();
            instanceWorlds.computeIfAbsent(instanceId, k -> ConcurrentHashMap.newKeySet()).add(bukkit.getName());
            if (templateName != null && !templateName.isBlank()) {
                templateWorlds.computeIfAbsent(instanceId, k -> new ConcurrentHashMap<>())
                        .put(resolveTemplate(templateName), bukkit);
            }
            plugin.getLogger().info("Loaded slime world '" + bukkit.getName() + "' for party " + shortId(instanceId));
            return Optional.of(bukkit);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load prepared slime clone for party " + shortId(instanceId), e);
            return Optional.empty();
        }
    }

    public Optional<World> loadClone(UUID instanceId, SlimeWorld clone) {
        return loadClone(instanceId, "", clone);
    }

    /** Callers must evacuate players before unloading a managed clone. */
    public boolean unloadWorldForInstance(UUID instanceId, World world) {
        if (world == null) {
            return true;
        }
        String worldName = world.getName();
        if (!world.getPlayers().isEmpty() || !unloadWorld(worldName)) {
            if (!closed && instanceId != null) {
                pendingUnloads.put(worldName, instanceId);
                if (unloadTask == null) {
                    unloadTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::retryUnloads, 20L, 20L);
                }
            }
            return false;
        }
        forgetWorld(instanceId, worldName);
        return true;
    }

    private void forgetWorld(UUID instanceId, String worldName) {
        pendingUnloads.remove(worldName);
        if (instanceId != null) {
            Map<String, World> map = templateWorlds.get(instanceId);
            if (map != null) {
                map.values().removeIf(w -> w.getName().equals(worldName));
                if (map.isEmpty()) {
                    templateWorlds.remove(instanceId);
                }
            }
            Set<String> set = instanceWorlds.get(instanceId);
            if (set != null) {
                set.remove(worldName);
                if (set.isEmpty()) {
                    instanceWorlds.remove(instanceId);
                }
            }
        }
    }

    /** Callers must evacuate every party player before this method is called. */
    public void unloadForInstance(UUID instanceId) {
        Set<String> worlds = instanceWorlds.get(instanceId);
        if (worlds == null || worlds.isEmpty()) {
            return;
        }
        for (String worldName : new ArrayList<>(worlds)) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) unloadWorldForInstance(instanceId, world);
            else forgetWorld(instanceId, worldName);
        }
    }

    private void retryUnloads() {
        for (Map.Entry<String, UUID> entry : new ArrayList<>(pendingUnloads.entrySet())) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null) {
                forgetWorld(entry.getValue(), entry.getKey());
            } else if (world.getPlayers().isEmpty()) {
                unloadWorldForInstance(entry.getValue(), world);
            }
        }
        if (pendingUnloads.isEmpty()) {
            unloadTask.cancel();
            unloadTask = null;
        }
    }

    public void shutdown() {
        closed = true;
        loads.close();
        if (loadTask != null) { loadTask.cancel(); loadTask = null; }
        if (unloadTask != null) { unloadTask.cancel(); unloadTask = null; }
        pendingUnloads.clear();
        unloadAll();
    }

    public void unloadAll() {
        for (UUID id : instanceWorlds.keySet().toArray(UUID[]::new)) {
            unloadForInstance(id);
        }
    }

    /**
     * Main thread: create an empty template world, let {@code painter} build it, then save it to
     * {@code <template>.slime} off-thread and unload it. The future completes on the main thread.
     * Clones already running keep their own in-memory copy.
     */
    public CompletableFuture<Void> generateTemplate(String templateName, Consumer<World> painter) {
        if (!isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("ASP is not ready"));
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("generateTemplate must run on the main thread");
        }
        if (Bukkit.getWorld(templateName) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("World '" + templateName + "' is already loaded"));
        }

        SlimeWorldInstance live;
        try {
            live = asp.loadWorld(asp.createEmptyWorld(templateName, false, defaultProperties(), loader), false);
            painter.accept(live.getBukkitWorld());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to build slime template '" + templateName + "'", e);
            unloadWorld(templateName);
            return CompletableFuture.failedFuture(e);
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        // saveWorld hops to the main thread for a loaded world and blocks until done, so call it async
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Exception failure = null;
            try {
                asp.saveWorld(live);
            } catch (IOException | RuntimeException e) {
                failure = e;
                plugin.getLogger().log(Level.SEVERE, "Failed to save slime template '" + templateName + "'", e);
            }
            Exception error = failure;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                unloadWorld(templateName);
                if (error == null) {
                    plugin.getLogger().info("Generated slime template '" + templateName + ".slime'");
                    result.complete(null);
                } else {
                    result.completeExceptionally(error);
                }
            });
        });
        return result;
    }

    /** Removes stale McParty clone worlds left behind by an earlier plugin lifecycle. */
    public int unloadStaleInstanceWorlds(Location fallback) {
        if (!isReady()) {
            return 0;
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("unloadStaleInstanceWorlds must run on the main thread");
        }
        if (fallback == null || fallback.getWorld() == null) {
            plugin.getLogger().warning("Skipping stale slime-world cleanup because the fallback location is unavailable.");
            return 0;
        }

        Pattern cloneName = Pattern.compile(Pattern.quote(worldPrefix) + "[0-9a-f]{8,32}-.+");
        int unloaded = 0;
        for (World world : new ArrayList<>(Bukkit.getWorlds())) {
            if (!cloneName.matcher(world.getName()).matches() || asp.getLoadedWorld(world.getName()) == null) {
                continue;
            }
            for (Player player : new ArrayList<>(world.getPlayers())) {
                if (player.isOnline()) {
                    player.teleport(fallback);
                }
            }
            if (unloadWorld(world.getName())) {
                unloaded++;
            }
        }
        return unloaded;
    }


    private boolean unloadWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return true;
        }

        boolean ok = Bukkit.unloadWorld(world, false);
        if (ok) {
            plugin.getLogger().info("Unloaded slime world '" + worldName + "'");
        } else {
            plugin.getLogger().warning("Could not unload slime world '" + worldName + "'");
        }
        return ok;
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
