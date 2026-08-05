package dev.epicc.hologram;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public final class YamlHologramRepository {

    private final JavaPlugin plugin;
    private final File file;
    private final String resourcePath;

    public YamlHologramRepository(JavaPlugin plugin, String fileName) {
        this.plugin = plugin;
        String path = fileName == null || fileName.isBlank() ? "holograms.yml" : fileName;
        this.resourcePath = path.replace('\\', '/');
        this.file = new File(plugin.getDataFolder(), path);
    }

    public Map<String, HologramDefinition> load() {
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Could not create hologram directory: " + parent);
            }
            try (InputStream resource = plugin.getResource(resourcePath)) {
                if (resource != null) {
                    plugin.saveResource(resourcePath, false);
                } else if (!file.createNewFile()) {
                    plugin.getLogger().warning("Could not create hologram file: " + file);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not initialize hologram file " + file.getName(), e);
            }
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("holograms");
        Map<String, HologramDefinition> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        for (String rawId : section.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_-]{1,64}")) {
                plugin.getLogger().warning("Invalid hologram id '" + rawId + "' — skipping");
                continue;
            }
            try {
                HologramDefinition definition = read(id, section.getConfigurationSection(rawId));
                if (result.containsKey(id)) {
                    plugin.getLogger().warning("Duplicate hologram id ignored: " + id);
                    continue;
                }
                result.put(id, definition);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Invalid hologram '" + rawId + "' — skipping", e);
            }
        }
        return result;
    }

    public void save(Collection<HologramDefinition> definitions) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Could not create hologram directory: " + parent);
        }
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        for (HologramDefinition definition : definitions) {
            write(yaml, definition);
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + file.getName(), e);
        }
    }

    private HologramDefinition read(String id, ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException("missing definition");
        }
        ConfigurationSection location = required(section, "location");
        String world = requiredString(section, "world");
        HologramLocation hologramLocation = new HologramLocation(
                world,
                location.getDouble("x"),
                location.getDouble("y"),
                location.getDouble("z"),
                (float) location.getDouble("yaw", 0.0),
                (float) location.getDouble("pitch", 0.0)
        );

        ConfigurationSection content = section.getConfigurationSection("content");
        List<String> lines = content == null ? section.getStringList("lines") : content.getStringList("lines");
        List<HologramFrame> frames = readFrames(content);
        int refreshTicks = content == null
                ? section.getInt("refresh-ticks", 20)
                : content.getInt("refresh-ticks", 20);

        ConfigurationSection style = section.getConfigurationSection("style");
        HologramStyle defaults = HologramStyle.defaults(32.0f);
        HologramStyle hologramStyle = new HologramStyle(
                string(style, "billboard", defaults.billboard()),
                string(style, "alignment", defaults.alignment()),
                string(style, "color", defaults.color()),
                (float) number(style, "scale", defaults.scale()),
                parseBackground(string(style, "background", "default")),
                isDefaultBackground(string(style, "background", "default")),
                bool(style, "shadowed", defaults.shadowed()),
                bool(style, "see-through", defaults.seeThrough()),
                (byte) intValue(style, "text-opacity", defaults.textOpacity()),
                intValue(style, "line-width", defaults.lineWidth()),
                (float) number(style, "view-range", defaults.viewRange()),
                intValue(style, "brightness.block", defaults.brightnessBlock()),
                intValue(style, "brightness.sky", defaults.brightnessSky())
        );

        ConfigurationSection visibility = section.getConfigurationSection("visibility");
        return new HologramDefinition(
                id,
                hologramLocation,
                lines,
                frames,
                hologramStyle,
                refreshTicks,
                string(visibility, "mode", "all"),
                string(visibility, "permission", ""),
                string(section, "scope", "global")
        );
    }

    private List<HologramFrame> readFrames(ConfigurationSection content) {
        if (content == null) {
            return List.of();
        }
        ConfigurationSection frames = content.getConfigurationSection("frames");
        if (frames == null || !frames.getBoolean("enabled", false)) {
            return List.of();
        }
        List<HologramFrame> result = new ArrayList<>();
        for (Map<?, ?> map : frames.getMapList("entries")) {
            Object duration = map.get("duration-ticks");
            Object rawLines = map.get("lines");
            if (!(duration instanceof Number number) || !(rawLines instanceof List<?> list)) {
                continue;
            }
            List<String> lines = list.stream().map(String::valueOf).toList();
            result.add(new HologramFrame(number.longValue(), lines));
        }
        return List.copyOf(result);
    }

    private void write(FileConfiguration yaml, HologramDefinition definition) {
        String root = "holograms." + definition.id();
        HologramLocation location = definition.location();
        yaml.set(root + ".scope", definition.scope());
        yaml.set(root + ".world", location.world());
        yaml.set(root + ".location.x", location.x());
        yaml.set(root + ".location.y", location.y());
        yaml.set(root + ".location.z", location.z());
        yaml.set(root + ".location.yaw", location.yaw());
        yaml.set(root + ".location.pitch", location.pitch());
        yaml.set(root + ".content.lines", definition.lines());
        yaml.set(root + ".content.refresh-ticks", definition.refreshTicks());
        if (!definition.frames().isEmpty()) {
            yaml.set(root + ".content.frames.enabled", true);
            yaml.set(root + ".content.frames.loop", true);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (HologramFrame frame : definition.frames()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("duration-ticks", frame.durationTicks());
                entry.put("lines", frame.lines());
                entries.add(entry);
            }
            yaml.set(root + ".content.frames.entries", entries);
        }
        HologramStyle style = definition.style();
        yaml.set(root + ".style.billboard", style.billboard());
        yaml.set(root + ".style.alignment", style.alignment());
        yaml.set(root + ".style.color", style.color());
        yaml.set(root + ".style.scale", style.scale());
        yaml.set(root + ".style.background", style.defaultBackground() ? "default" : toHex(style.backgroundArgb()));
        yaml.set(root + ".style.shadowed", style.shadowed());
        yaml.set(root + ".style.see-through", style.seeThrough());
        yaml.set(root + ".style.text-opacity", style.textOpacity());
        yaml.set(root + ".style.line-width", style.lineWidth());
        yaml.set(root + ".style.view-range", style.viewRange());
        if (style.brightnessBlock() >= 0) yaml.set(root + ".style.brightness.block", style.brightnessBlock());
        if (style.brightnessSky() >= 0) yaml.set(root + ".style.brightness.sky", style.brightnessSky());
        yaml.set(root + ".visibility.mode", definition.visibilityMode());
        yaml.set(root + ".visibility.permission", definition.permission());
    }

    private static ConfigurationSection required(ConfigurationSection section, String path) {
        ConfigurationSection value = section.getConfigurationSection(path);
        if (value == null) throw new IllegalArgumentException("missing " + path);
        return value;
    }

    private static String requiredString(ConfigurationSection section, String path) {
        String value = section.getString(path);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + path);
        return value;
    }

    private static String string(ConfigurationSection section, String path, String fallback) {
        return section == null ? fallback : section.getString(path, fallback);
    }

    private static boolean bool(ConfigurationSection section, String path, boolean fallback) {
        return section == null ? fallback : section.getBoolean(path, fallback);
    }

    private static int intValue(ConfigurationSection section, String path, int fallback) {
        return section == null ? fallback : section.getInt(path, fallback);
    }

    private static double number(ConfigurationSection section, String path, double fallback) {
        return section == null ? fallback : section.getDouble(path, fallback);
    }

    private static boolean isDefaultBackground(String value) {
        return value == null || value.equalsIgnoreCase("default");
    }

    private static int parseBackground(String value) {
        if (value == null || value.equalsIgnoreCase("default") || value.equalsIgnoreCase("none")) {
            return 0;
        }
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() == 6) hex = "FF" + hex;
        if (hex.length() != 8) throw new IllegalArgumentException("invalid background color: " + value);
        return (int) Long.parseLong(hex, 16);
    }

    private static String toHex(int argb) {
        return String.format(Locale.ROOT, "#%08X", argb);
    }
}
