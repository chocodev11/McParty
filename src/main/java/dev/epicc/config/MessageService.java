package dev.epicc.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code messages.yml} and builds Adventure components via MiniMessage.
 * Placeholders: {@code <name>} in YAML, passed as {@code ph("name", value)}.
 * Insert the configured prefix with {@code {prefix}} in the template string.
 */
public final class MessageService {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private FileConfiguration yaml;
    private String prefixRaw = "";

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)
                );
                yaml.setDefaults(defaults);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load messages.yml defaults: " + e.getMessage());
        }
        prefixRaw = yaml.getString("prefix", "<gray>[</gray><gold>McParty</gold><gray>]</gray> ");
        if (prefixRaw == null) {
            prefixRaw = "";
        }
    }

    public Component get(String path) {
        return get(path, TagResolver.empty());
    }

    public Component get(String path, TagResolver... resolvers) {
        String raw = yaml.getString(path);
        if (raw == null || raw.isEmpty()) {
            return Component.text(path);
        }
        if (raw.contains("{prefix}")) {
            raw = raw.replace("{prefix}", prefixRaw);
        }
        if (resolvers.length == 0) {
            return MM.deserialize(raw);
        }
        return MM.deserialize(raw, TagResolver.resolver(resolvers));
    }

    public Component get(String path, String k1, String v1) {
        return get(path, ph(k1, v1));
    }

    public Component get(String path, String k1, String v1, String k2, String v2) {
        return get(path, ph(k1, v1), ph(k2, v2));
    }

    public Component get(String path, String k1, String v1, String k2, String v2, String k3, String v3) {
        return get(path, ph(k1, v1), ph(k2, v2), ph(k3, v3));
    }

    public Component get(
            String path,
            String k1, String v1,
            String k2, String v2,
            String k3, String v3,
            String k4, String v4
    ) {
        return get(path, ph(k1, v1), ph(k2, v2), ph(k3, v3), ph(k4, v4));
    }

    public void send(CommandSender sender, String path) {
        sender.sendMessage(get(path));
    }

    public void send(CommandSender sender, String path, TagResolver... resolvers) {
        sender.sendMessage(get(path, resolvers));
    }

    public void send(CommandSender sender, String path, String k1, String v1) {
        sender.sendMessage(get(path, k1, v1));
    }

    public void send(CommandSender sender, String path, String k1, String v1, String k2, String v2) {
        sender.sendMessage(get(path, k1, v1, k2, v2));
    }

    public void send(
            CommandSender sender,
            String path,
            String k1, String v1,
            String k2, String v2,
            String k3, String v3
    ) {
        sender.sendMessage(get(path, k1, v1, k2, v2, k3, v3));
    }

    public void send(
            CommandSender sender,
            String path,
            String k1, String v1,
            String k2, String v2,
            String k3, String v3,
            String k4, String v4
    ) {
        sender.sendMessage(get(path, k1, v1, k2, v2, k3, v3, k4, v4));
    }

    /** Plain string from YAML (no MiniMessage), for rare non-chat uses. */
    public String raw(String path, String def) {
        String v = yaml.getString(path, def);
        return v == null ? def : v;
    }

    public List<Component> lore(String... paths) {
        List<Component> out = new ArrayList<>(paths.length);
        for (String path : paths) {
            out.add(get(path));
        }
        return out;
    }

    public static TagResolver ph(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    public static TagResolver ph(String key, int value) {
        return Placeholder.unparsed(key, Integer.toString(value));
    }
}
