package dev.epicc.command;

import dev.epicc.McPartyPlugin;
import dev.epicc.board.BoardSlot;
import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.board.setup.PathSetupService;
import dev.epicc.config.MessageService;
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

    public PartyAdminCommand(
            McPartyPlugin plugin,
            BoardSlotRegistry slots,
            PathSetupService pathSetup,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.slots = slots;
        this.pathSetup = pathSetup;
        this.messages = messages;
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
                    player.sendMessage(messages.get(
                            "admin.slot-entry",
                            "id", slot.id(),
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
                case "path" -> filter(List.of("create", "undo", "end"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            String g = args[0].toLowerCase(Locale.ROOT);
            String s = args[1].toLowerCase(Locale.ROOT);
            if (g.equals("slot") && s.equals("delete")) {
                List<String> ids = slots.all().stream().map(BoardSlot::id).collect(Collectors.toCollection(ArrayList::new));
                return filter(ids, args[2]);
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
