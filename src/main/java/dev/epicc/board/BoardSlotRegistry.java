package dev.epicc.board;

import dev.epicc.containment.SlotBoundary;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class BoardSlotRegistry {

    private final JavaPlugin plugin;
    private final Map<String, BoardSlot> slots = new ConcurrentHashMap<>();
    private final File file;

    public BoardSlotRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "slots.yml");
    }

    public void load() {
        slots.clear();
        if (!file.exists()) {
            return;
        }
        FileConfiguration conf = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = conf.getConfigurationSection("slots");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            String worldName = sec.getString("world");
            World world = worldName != null ? Bukkit.getWorld(worldName) : null;
            if (world == null) {
                plugin.getLogger().warning("Slot '" + id + "' world missing: " + worldName);
                continue;
            }
            // Per-board ASP template; missing key keeps empty (PartyManager falls back to config default).
            String slimeTemplate = sec.getString("slime-template", "");
            if (slimeTemplate == null) {
                slimeTemplate = "";
            } else {
                slimeTemplate = slimeTemplate.trim();
            }
            SlotBoundary boundary = new SlotBoundary(
                    world,
                    sec.getInt("minX"), sec.getInt("minY"), sec.getInt("minZ"),
                    sec.getInt("maxX"), sec.getInt("maxY"), sec.getInt("maxZ")
            );
            BoardPath path = new BoardPath();
            List<?> pathList = sec.getList("path");
            if (pathList != null) {
                for (Object entry : pathList) {
                    if (entry instanceof Map<?, ?> map) {
                        double x = toDouble(map.get("x"));
                        double y = toDouble(map.get("y"));
                        double z = toDouble(map.get("z"));
                        float yaw = (float) toDouble(map.get("yaw"));
                        float pitch = (float) toDouble(map.get("pitch"));
                        path.add(new Location(world, x, y, z, yaw, pitch));
                    }
                }
            }
            Location spawn = null;
            if (sec.contains("spawn.x")) {
                spawn = new Location(
                        world,
                        sec.getDouble("spawn.x"),
                        sec.getDouble("spawn.y"),
                        sec.getDouble("spawn.z"),
                        (float) sec.getDouble("spawn.yaw"),
                        (float) sec.getDouble("spawn.pitch")
                );
            }
            slots.put(
                    id.toLowerCase(),
                    new BoardSlot(id.toLowerCase(), world, slimeTemplate, boundary, path, spawn)
            );
        }
        plugin.getLogger().info("Loaded " + slots.size() + " board slot(s)");
    }

    public void save() {
        FileConfiguration conf = new YamlConfiguration();
        for (BoardSlot slot : slots.values()) {
            String base = "slots." + slot.id();
            conf.set(base + ".world", slot.world().getName());
            conf.set(base + ".slime-template", slot.slimeTemplate());
            SlotBoundary b = slot.boundary();
            conf.set(base + ".minX", b.minX());
            conf.set(base + ".minY", b.minY());
            conf.set(base + ".minZ", b.minZ());
            conf.set(base + ".maxX", b.maxX());
            conf.set(base + ".maxY", b.maxY());
            conf.set(base + ".maxZ", b.maxZ());
            if (slot.spawn() != null) {
                Location s = slot.spawn();
                conf.set(base + ".spawn.x", s.getX());
                conf.set(base + ".spawn.y", s.getY());
                conf.set(base + ".spawn.z", s.getZ());
                conf.set(base + ".spawn.yaw", s.getYaw());
                conf.set(base + ".spawn.pitch", s.getPitch());
            }
            List<Map<String, Object>> pathData = new ArrayList<>();
            for (Location p : slot.path().points()) {
                pathData.add(Map.of(
                        "x", p.getX(),
                        "y", p.getY(),
                        "z", p.getZ(),
                        "yaw", (double) p.getYaw(),
                        "pitch", (double) p.getPitch()
                ));
            }
            conf.set(base + ".path", pathData);
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create data folder");
            }
            conf.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save slots.yml", e);
        }
    }

    public boolean createReady(
            String id,
            World world,
            String slimeTemplate,
            SlotBoundary boundary,
            BoardPath path,
            Location spawn
    ) {
        String key = id.toLowerCase();
        if (slots.containsKey(key)) {
            return false;
        }
        String template = slimeTemplate != null ? slimeTemplate.trim() : "";
        slots.put(key, new BoardSlot(key, world, template, boundary, path, spawn));
        save();
        return true;
    }

    public boolean delete(String id) {
        BoardSlot removed = slots.remove(id.toLowerCase());
        if (removed != null) {
            save();
            return true;
        }
        return false;
    }

    /**
     * Set ASP slime template basename on an existing slot and persist {@code slots.yml}.
     * @return false if the path id is unknown
     */
    public boolean setSlimeTemplate(String id, String slimeTemplate) {
        BoardSlot slot = slots.get(id.toLowerCase());
        if (slot == null) {
            return false;
        }
        slot.setSlimeTemplate(slimeTemplate);
        save();
        return true;
    }

    public Optional<BoardSlot> get(String id) {
        return Optional.ofNullable(slots.get(id.toLowerCase()));
    }

    public Collection<BoardSlot> all() {
        return slots.values();
    }

    public Optional<BoardSlot> claimFree(java.util.UUID instanceId) {
        for (BoardSlot slot : slots.values()) {
            if (slot.isFree() && slot.isReady() && slot.claim(instanceId)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    public void releaseAll() {
        for (BoardSlot slot : slots.values()) {
            slot.release();
        }
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return 0;
    }
}
