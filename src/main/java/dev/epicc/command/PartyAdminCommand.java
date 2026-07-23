package dev.epicc.command;

import dev.epicc.McPartyPlugin;
import dev.epicc.board.BoardSlot;
import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.board.setup.PathSetupService;
import dev.epicc.config.MessageService;
import dev.epicc.slime.SlimeWorldService;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public final class PartyAdminCommand implements CommandExecutor, TabCompleter {

    private final McPartyPlugin plugin;
    private final BoardSlotRegistry slots;
    private final PathSetupService pathSetup;
    private final MessageService messages;
    private final SlimeWorldService slime;

    public PartyAdminCommand(
            McPartyPlugin plugin,
            BoardSlotRegistry slots,
            PathSetupService pathSetup,
            MessageService messages,
            SlimeWorldService slime
    ) {
        this.plugin = plugin;
        this.slots = slots;
        this.pathSetup = pathSetup;
        this.messages = messages;
        this.slime = slime;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            try {
                plugin.reloadPluginConfig();
                messages.send(sender, "admin.reload-ok");
            } catch (Exception e) {
                plugin.getLogger().severe("Config reload failed: " + e.getMessage());
                e.printStackTrace();
                messages.send(sender, "admin.reload-failed");
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.players-only-except-reload");
            return true;
        }
        if (args.length == 0) {
            help(player);
            return true;
        }

        String group = args[0].toLowerCase(Locale.ROOT);
        switch (group) {
            case "slot" -> handleSlot(player, args);
            case "path" -> handlePath(player, args);
            default -> help(player);
        }
        return true;
    }

    private void handleSlot(Player player, String[] args) {
        if (args.length < 2) {
            messages.send(player, "admin.slot-usage");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "delete" -> {
                if (args.length < 3) {
                    messages.send(player, "admin.slot-delete-usage");
                    return;
                }
                if (slots.delete(args[2])) {
                    messages.send(player, "admin.slot-deleted");
                } else {
                    messages.send(player, "admin.slot-not-found");
                }
            }
            case "list" -> {
                if (slots.all().isEmpty()) {
                    messages.send(player, "admin.slot-none");
                    return;
                }
                for (BoardSlot slot : slots.all()) {
                    String template = slot.slimeTemplate();
                    if (template == null || template.isBlank()) {
                        template = slime.defaultTemplate() + "*";
                    }
                    player.sendMessage(messages.get(
                            "admin.slot-entry",
                            "id", slot.id(),
                            "template", template,
                            "ready", Boolean.toString(slot.isReady()),
                            "free", Boolean.toString(slot.isFree()),
                            "path", Integer.toString(slot.path().size())
                    ));
                }
            }
            default -> messages.send(player, "admin.slot-usage-short");
        }
    }

    private void handlePath(Player player, String[] args) {
        if (args.length < 2) {
            messages.send(player, "admin.path-usage");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        Optional<Component> err = switch (sub) {
            case "create" -> {
                if (args.length < 3) {
                    yield Optional.of(messages.get("admin.path-create-usage"));
                }
                yield pathSetup.start(player, args[2]);
            }
            case "undo" -> pathSetup.undo(player);
            case "end" -> pathSetup.end(player);
            case "remove" -> {
                if (args.length < 3) {
                    yield Optional.of(messages.get("admin.path-remove-usage"));
                }
                String id = args[2];
                if (slots.delete(id)) {
                    messages.send(player, "admin.path-removed", "name", id.toLowerCase(Locale.ROOT));
                    yield Optional.empty();
                }
                yield Optional.of(messages.get("admin.path-not-found", "name", id.toLowerCase(Locale.ROOT)));
            }
            case "slime" -> {
                if (args.length < 4) {
                    yield Optional.of(messages.get("admin.path-slime-usage"));
                }
                String pathId = args[2].toLowerCase(Locale.ROOT);
                String template = args[3].trim();
                if (slots.get(pathId).isEmpty()) {
                    yield Optional.of(messages.get("admin.path-not-found", "name", pathId));
                }
                List<String> available = slime.listTemplates();
                if (!available.contains(template) && available.stream().noneMatch(t -> t.equalsIgnoreCase(template))) {
                    yield Optional.of(messages.get("admin.path-slime-missing", "template", template));
                }
                // Use exact folder basename casing if present
                String resolved = available.stream()
                        .filter(t -> t.equalsIgnoreCase(template))
                        .findFirst()
                        .orElse(template);
                if (!slots.setSlimeTemplate(pathId, resolved)) {
                    yield Optional.of(messages.get("admin.path-not-found", "name", pathId));
                }
                messages.send(player, "admin.path-slime-set", "name", pathId, "template", resolved);
                yield Optional.empty();
            }
            default -> {
                messages.send(player, "admin.path-usage");
                yield Optional.empty();
            }
        };
        err.ifPresent(player::sendMessage);
    }

    private void help(Player player) {
        messages.send(player, "admin.help-path");
        messages.send(player, "admin.help-slot");
        messages.send(player, "admin.help-reload");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("slot", "path", "reload"), args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "slot" -> filter(List.of("list", "delete"), args[1]);
                case "path" -> filter(List.of("create", "undo", "end", "remove", "slime"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            String g = args[0].toLowerCase(Locale.ROOT);
            String s = args[1].toLowerCase(Locale.ROOT);
            if ((g.equals("slot") && s.equals("delete"))
                    || (g.equals("path") && (s.equals("remove") || s.equals("slime")))) {
                List<String> ids = slots.all().stream().map(BoardSlot::id).collect(Collectors.toCollection(ArrayList::new));
                return filter(ids, args[2]);
            }
            // path create <name> — free-type name, no suggestions
            return List.of();
        }
        if (args.length == 4) {
            String g = args[0].toLowerCase(Locale.ROOT);
            String s = args[1].toLowerCase(Locale.ROOT);
            if (g.equals("path") && s.equals("slime")) {
                return filter(slime.listTemplates(), args[3]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
