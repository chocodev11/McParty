package dev.epicc.minigame;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/** Persistent template-relative course definitions used by the Elytra setup commands. */
public final class ElytraCourseStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, ElytraCourse> courses = new LinkedHashMap<>();

    public ElytraCourseStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "elytra-courses.yml");
    }

    public void load() {
        courses.clear();
        if (!file.exists()) {
            return;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("courses");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            ElytraCourse course = readCourse(id, section);
            if (course != null) {
                courses.put(course.id(), course);
            }
        }
        plugin.getLogger().info("Loaded " + courses.size() + " Elytra course(s)");
    }

    public void save() {
        FileConfiguration config = new YamlConfiguration();
        for (ElytraCourse course : courses.values()) {
            String base = "courses." + course.id();
            MinigameArenaSpec arena = course.arenaSpec();
            config.set(base + ".setup-world", course.setupWorldName());
            config.set(base + ".template", arena.template());
            config.set(base + ".spawn.x", arena.spawnX());
            config.set(base + ".spawn.y", arena.spawnY());
            config.set(base + ".spawn.z", arena.spawnZ());
            config.set(base + ".spawn.yaw", arena.spawnYaw());
            config.set(base + ".spawn.pitch", arena.spawnPitch());
            config.set(base + ".boundary.minX", arena.minX());
            config.set(base + ".boundary.minY", arena.minY());
            config.set(base + ".boundary.minZ", arena.minZ());
            config.set(base + ".boundary.maxX", arena.maxX());
            config.set(base + ".boundary.maxY", arena.maxY());
            config.set(base + ".boundary.maxZ", arena.maxZ());

            List<Map<String, Object>> rings = new ArrayList<>();
            for (ElytraRing ring : course.rings()) {
                rings.add(Map.of(
                        "x", ring.x(), "y", ring.y(), "z", ring.z(), "radius", ring.radius(),
                        "normal-x", ring.normalX(), "normal-y", ring.normalY(), "normal-z", ring.normalZ()
                ));
            }
            config.set(base + ".rings", rings);
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create data folder for Elytra courses");
            }
            config.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save elytra-courses.yml", exception);
        }
    }

    public Optional<ElytraCourse> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(courses.get(id.toLowerCase()));
    }

    public Collection<ElytraCourse> all() {
        return List.copyOf(courses.values());
    }

    public boolean create(String id, String setupWorldName, String template) {
        String key = id == null ? "" : id.trim().toLowerCase();
        if (!key.matches("[a-z0-9_-]+") || courses.containsKey(key)) {
            return false;
        }
        courses.put(key, ElytraCourse.empty(key, setupWorldName, template));
        save();
        return true;
    }

    public boolean delete(String id) {
        ElytraCourse removed = id == null ? null : courses.remove(id.toLowerCase());
        if (removed == null) {
            return false;
        }
        save();
        return true;
    }

    public boolean update(String id, UnaryOperator<ElytraCourse> updater) {
        if (id == null) {
            return false;
        }
        String key = id.toLowerCase();
        ElytraCourse current = courses.get(key);
        if (current == null) {
            return false;
        }
        ElytraCourse updated = updater.apply(current);
        if (updated == null) {
            return false;
        }
        courses.put(key, updated);
        save();
        return true;
    }

    private ElytraCourse readCourse(String id, ConfigurationSection section) {
        String template = section.getString("template", "");
        MinigameArenaSpec arena = new MinigameArenaSpec(
                template,
                section.getDouble("spawn.x", Double.NaN),
                section.getDouble("spawn.y", Double.NaN),
                section.getDouble("spawn.z", Double.NaN),
                (float) section.getDouble("spawn.yaw", 0.0),
                (float) section.getDouble("spawn.pitch", 0.0),
                section.getInt("boundary.minX", Integer.MAX_VALUE),
                section.getInt("boundary.minY", Integer.MAX_VALUE),
                section.getInt("boundary.minZ", Integer.MAX_VALUE),
                section.getInt("boundary.maxX", Integer.MIN_VALUE),
                section.getInt("boundary.maxY", Integer.MIN_VALUE),
                section.getInt("boundary.maxZ", Integer.MIN_VALUE)
        );
        List<ElytraRing> rings = new ArrayList<>();
        List<?> entries = section.getList("rings", List.of());
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            ElytraRing ring = new ElytraRing(
                    number(map.get("x")), number(map.get("y")), number(map.get("z")),
                    number(map.get("radius")),
                    number(map.get("normal-x")), number(map.get("normal-y")), number(map.get("normal-z"))
            );
            if (ring.isValid()) {
                rings.add(ring);
            }
        }
        return new ElytraCourse(id, section.getString("setup-world", ""), arena, rings);
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }
}
