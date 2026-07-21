package dev.epicc.command;

import dev.epicc.board.BoardSlot;
import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.board.setup.PathSetupService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

    private final BoardSlotRegistry slots;
    private final PathSetupService pathSetup;

    public PartyAdminCommand(BoardSlotRegistry slots, PathSetupService pathSetup) {
        this.slots = slots;
        this.pathSetup = pathSetup;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
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
            player.sendMessage(Component.text("/partyadmin slot <list|delete> [id]", NamedTextColor.AQUA));
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "delete" -> {
                if (args.length < 3) {
                    msg(player, "Usage: /partyadmin slot delete <id>", true);
                    return;
                }
                if (slots.delete(args[2])) {
                    msg(player, "Deleted slot.", false);
                } else {
                    msg(player, "Slot not found.", true);
                }
            }
            case "list" -> {
                if (slots.all().isEmpty()) {
                    msg(player, "No slots.", false);
                    return;
                }
                for (BoardSlot slot : slots.all()) {
                    msg(player, slot.id()
                            + " ready=" + slot.isReady()
                            + " free=" + slot.isFree()
                            + " path=" + slot.path().size(), false);
                }
            }
            default -> player.sendMessage(Component.text("/partyadmin slot <list|delete>", NamedTextColor.AQUA));
        }
    }

    private void handlePath(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("/partyadmin path <create|undo|end> [name]", NamedTextColor.AQUA));
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        Optional<String> err = switch (sub) {
            case "create" -> {
                if (args.length < 3) {
                    yield Optional.of("Usage: /partyadmin path create <name>");
                }
                yield pathSetup.start(player, args[2]);
            }
            case "undo" -> pathSetup.undo(player);
            case "end" -> pathSetup.end(player);
            default -> {
                player.sendMessage(Component.text("/partyadmin path <create|undo|end> [name]", NamedTextColor.AQUA));
                yield Optional.empty();
            }
        };
        err.ifPresent(msg -> msg(player, msg, true));
    }

    private void help(Player player) {
        player.sendMessage(Component.text("/partyadmin path create|undo|end", NamedTextColor.AQUA));
        player.sendMessage(Component.text("/partyadmin slot list|delete", NamedTextColor.AQUA));
    }

    private void msg(Player player, String text, boolean error) {
        player.sendMessage(Component.text("[McParty] " + text, error ? NamedTextColor.RED : NamedTextColor.GREEN));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("slot", "path"), args[0]);
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
