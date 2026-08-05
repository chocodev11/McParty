package dev.epicc.hologram;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.TextReplacementConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MiniMessage is parsed once; subsequent renders only replace literal markers. */
public final class CompiledHologramTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_.:-]+)}}");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Component parsed;
    private final List<PlaceholderToken> placeholders;

    private CompiledHologramTemplate(Component parsed, List<PlaceholderToken> placeholders) {
        this.parsed = parsed;
        this.placeholders = placeholders;
    }

    public static CompiledHologramTemplate compile(String raw) {
        String source = raw == null ? "" : raw;
        LinkedHashMap<String, String> markers = new LinkedHashMap<>();
        Matcher matcher = PLACEHOLDER.matcher(source);
        while (matcher.find()) {
            String marker = matcher.group(0);
            String name = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            markers.putIfAbsent(name, marker);
        }
        List<PlaceholderToken> tokens = markers.entrySet().stream()
                .map(entry -> new PlaceholderToken(entry.getKey(), entry.getValue()))
                .toList();
        return new CompiledHologramTemplate(MINI_MESSAGE.deserialize(source), tokens);
    }

    public Component render(HologramViewerContext context, java.util.Map<String, HologramPlaceholder> resolvers) {
        Component rendered = parsed;
        for (PlaceholderToken token : placeholders) {
            HologramPlaceholder resolver = resolvers.get(token.name());
            Component replacement = resolver == null ? Component.empty() : resolver.resolve(context);
            if (replacement == null) {
                replacement = Component.empty();
            }
            rendered = rendered.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(token.marker())
                    .replacement(replacement)
                    .build());
        }
        return rendered;
    }

    public List<String> placeholders() {
        return placeholders.stream().map(PlaceholderToken::name).toList();
    }

    private record PlaceholderToken(String name, String marker) {
    }
}
