package dev.epicc.resourcepack;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads font-image aliases and generates their bitmap font resource. */
public final class FontImageService {

    private static final int PRIVATE_USE_START = 0xE000;
    private static final int PRIVATE_USE_END = 0xF8FF;
    private static final int MAX_SCALE = 256;
    private static final Key FONT_KEY = Key.key("mcparty", "images");
    private static final Pattern PLACEHOLDER_ALIAS = Pattern.compile("%img_([a-z0-9_-]+)%");
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_-]{1,64}");

    private final JavaPlugin plugin;
    private final Map<String, FontImageDefinition> images = new LinkedHashMap<>();
    private final Set<String> warnedAliases = new LinkedHashSet<>();

    public FontImageService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration yaml = loadConfiguration();
        Map<String, FontImageDefinition> loaded = new LinkedHashMap<>();
        Set<Integer> usedCodepoints = new LinkedHashSet<>();
        warnedAliases.clear();

        ConfigurationSection section = yaml.getConfigurationSection("images");
        if (section == null) {
            images.clear();
            return;
        }

        for (String rawId : section.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!VALID_ID.matcher(id).matches()) {
                warn("Ignoring font image with invalid id '" + rawId + "'");
                continue;
            }
            if (loaded.containsKey(id)) {
                warn("Ignoring duplicate font image id '" + id + "'");
                continue;
            }

            ConfigurationSection image = section.getConfigurationSection(rawId);
            if (image == null) {
                warn("Ignoring font image '" + id + "': expected a configuration section");
                continue;
            }

            String texture = image.getString("texture", "");
            if (!isSafeTexturePath(texture)) {
                warn("Ignoring font image '" + id + "': texture path must be relative and cannot contain '..'");
                continue;
            }

            int scale = image.getInt("scale", 8);
            int yPosition = image.getInt("y-position", scale);
            int xOffset = image.getInt("x-offset", 0);
            if (scale < 1 || scale > MAX_SCALE) {
                warn("Ignoring font image '" + id + "': scale must be between 1 and " + MAX_SCALE);
                continue;
            }
            if (yPosition < -MAX_SCALE || yPosition > MAX_SCALE || yPosition > scale) {
                warn("Ignoring font image '" + id + "': y-position must be between -"
                        + MAX_SCALE + " and scale");
                continue;
            }
            if (xOffset < -MAX_SCALE || xOffset > MAX_SCALE) {
                warn("Ignoring font image '" + id + "': x-offset must be between -"
                        + MAX_SCALE + " and " + MAX_SCALE);
                continue;
            }

            String rawCodepoint = image.getString("codepoint");
            if (rawCodepoint == null || rawCodepoint.isBlank()) {
                continue;
            }
            Integer codepoint = parseCodepoint(rawCodepoint);
            if (codepoint == null || !isPrivateUse(codepoint)) {
                warn("Ignoring font image '" + id + "': codepoint must be a BMP private-use value between E000 and F8FF");
                continue;
            }
            if (!usedCodepoints.add(codepoint)) {
                warn("Ignoring font image '" + id + "': duplicate codepoint " + formatCodepoint(codepoint));
                continue;
            }
            loaded.put(id, new FontImageDefinition(id, texture, codepoint, scale, yPosition, xOffset, 0));
        }

        int nextCodepoint = PRIVATE_USE_START;
        for (String rawId : section.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!VALID_ID.matcher(id).matches() || loaded.containsKey(id)) {
                continue;
            }
            ConfigurationSection image = section.getConfigurationSection(rawId);
            if (image == null) {
                continue;
            }
            String rawCodepoint = image.getString("codepoint");
            if (rawCodepoint != null && !rawCodepoint.isBlank()) {
                continue;
            }
            String texture = image.getString("texture", "");
            int scale = image.getInt("scale", 8);
            int yPosition = image.getInt("y-position", scale);
            int xOffset = image.getInt("x-offset", 0);
            if (!isSafeTexturePath(texture) || scale < 1 || scale > MAX_SCALE
                    || yPosition < -MAX_SCALE || yPosition > MAX_SCALE || yPosition > scale
                    || xOffset < -MAX_SCALE || xOffset > MAX_SCALE) {
                continue;
            }

            while (nextCodepoint <= PRIVATE_USE_END && usedCodepoints.contains(nextCodepoint)) {
                nextCodepoint++;
            }
            if (nextCodepoint > PRIVATE_USE_END) {
                warn("Ignoring font image '" + id + "': no private-use codepoints remain");
                continue;
            }
            int codepoint = nextCodepoint++;
            usedCodepoints.add(codepoint);
            loaded.put(id, new FontImageDefinition(id, texture, codepoint, scale, yPosition, xOffset, 0));
        }

        for (Map.Entry<String, FontImageDefinition> entry : loaded.entrySet()) {
            FontImageDefinition image = entry.getValue();
            if (!image.hasXOffset()) {
                continue;
            }
            while (nextCodepoint <= PRIVATE_USE_END && usedCodepoints.contains(nextCodepoint)) {
                nextCodepoint++;
            }
            if (nextCodepoint > PRIVATE_USE_END) {
                warn("Ignoring x-offset for font image '" + image.id() + "': no private-use codepoints remain");
                continue;
            }
            int offsetCodepoint = nextCodepoint++;
            usedCodepoints.add(offsetCodepoint);
            entry.setValue(image.withXOffsetCodepoint(offsetCodepoint));
        }

        images.clear();
        images.putAll(loaded);
    }

    public String expandAliases(String raw) {
        if (raw == null || raw.isEmpty() || images.isEmpty()) {
            return raw;
        }
        return replaceKnownAliases(raw, PLACEHOLDER_ALIAS);
    }

    /** Render a configured image directly for dynamic values such as a dice face. */
    public Component image(String id) {
        String normalized = id == null ? "" : id.toLowerCase(Locale.ROOT);
        FontImageDefinition image = images.get(normalized);
        if (image == null) {
            warnUnknownAlias(normalized);
            return Component.text("%img_" + id + "%");
        }
        return Component.text(renderedGlyph(image)).font(FONT_KEY);
    }

    /** Generate the dedicated font file for the bundled local resource pack. */
    public String resourcePackFontJson(Set<String> packEntries) {
        StringBuilder json = new StringBuilder("{\n  \"providers\": [\n");
        int written = 0;
        for (FontImageDefinition image : images.values()) {
            if (!image.hasXOffset()) {
                continue;
            }
            if (written++ > 0) {
                json.append(",\n");
            }
            json.append("    {\n")
                    .append("      \"type\": \"space\",\n")
                    .append("      \"advances\": {\"")
                    .append(unicodeEscape(image.xOffsetCodepoint()))
                    .append("\": ")
                    .append(image.xOffset())
                    .append("}\n")
                    .append("    }");
        }
        for (FontImageDefinition image : images.values()) {
            String texturePath = "assets/mcparty/textures/" + image.texture().replace('\\', '/');
            if (!packEntries.contains(texturePath)) {
                warn("Font image '" + image.id() + "' texture is missing from the bundled pack: " + image.texture());
                continue;
            }
            if (written++ > 0) {
                json.append(",\n");
            }
            json.append("    {\n")
                    .append("      \"type\": \"bitmap\",\n")
                    .append("      \"file\": \"mcparty:")
                    .append(jsonEscape(image.texture()))
                    .append("\",\n")
                    .append("      \"ascent\": ")
                    .append(image.yPosition())
                    .append(",\n")
                    .append("      \"height\": ")
                    .append(image.scale())
                    .append(",\n")
                    .append("      \"chars\": [\"")
                    .append(unicodeEscape(image.codepoint()))
                    .append("\"]\n")
                    .append("    }");
        }
        json.append("\n  ]\n}\n");
        return json.toString();
    }

    public void warnExternalPackRequirement() {
        if (!images.isEmpty()) {
            warn("External resource pack must contain assets/mcparty/font/images.json and matching font-image codepoints");
        }
    }

    private FileConfiguration loadConfiguration() {
        FileConfiguration yaml;
        Path file = plugin.getDataFolder().toPath().resolve("font-images.yml");
        if (!Files.isRegularFile(file)) {
            plugin.saveResource("font-images.yml", false);
        }
        yaml = YamlConfiguration.loadConfiguration(file.toFile());
        try (InputStream in = plugin.getResource("font-images.yml")) {
            if (in != null) {
                FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)
                );
                yaml.setDefaults(defaults);
            }
        } catch (IOException exception) {
            warn("Could not load font-images.yml defaults: " + exception.getMessage());
        }
        yaml.options().copyDefaults(true);
        return yaml;
    }

    private String replaceKnownAliases(String raw, Pattern pattern) {
        Matcher matcher = pattern.matcher(raw);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String id = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!images.containsKey(id)) {
                warnUnknownAlias(id);
                continue;
            }
            FontImageDefinition image = images.get(id);
            String replacement = "<font:mcparty:images>" + renderedGlyph(image) + "</font>";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String renderedGlyph(FontImageDefinition image) {
        return (image.hasXOffset() ? image.xOffsetGlyph() : "") + image.glyph();
    }

    private static Integer parseCodepoint(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            if (normalized.startsWith("U+")) {
                return Integer.parseInt(normalized.substring(2), 16);
            }
            if (normalized.startsWith("0X")) {
                return Integer.parseInt(normalized.substring(2), 16);
            }
            boolean decimal = normalized.chars().allMatch(Character::isDigit);
            return Integer.parseInt(normalized, decimal ? 10 : 16);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean isPrivateUse(int codepoint) {
        return codepoint >= PRIVATE_USE_START && codepoint <= PRIVATE_USE_END;
    }

    private static String formatCodepoint(int codepoint) {
        return String.format(Locale.ROOT, "U+%04X", codepoint);
    }

    private static boolean isSafeTexturePath(String texture) {
        if (texture == null || texture.isBlank()) {
            return false;
        }
        String normalized = texture.replace('\\', '/');
        try {
            Path path = Path.of(normalized).normalize();
            return !path.isAbsolute()
                    && !normalized.startsWith("/")
                    && !normalized.contains(":")
                    && !path.startsWith("..")
                    && !normalized.contains("//");
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String unicodeEscape(int codepoint) {
        return String.format(Locale.ROOT, "\\u%04X", codepoint);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void warn(String message) {
        plugin.getLogger().warning(message);
    }

    private void warnUnknownAlias(String id) {
        if (warnedAliases.add(id)) {
            warn("Unknown font image alias '" + id + "'");
        }
    }
}
