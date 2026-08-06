package dev.epicc.command;

import dev.epicc.config.MessageService;
import dev.epicc.hologram.HologramService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class HologramCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "list", "create", "remove", "move", "setline", "addline", "removeline", "reload"
    );

    private final MessageService messages;
    private final HologramService holograms;

    public HologramCommand(MessageService messages, HologramService holograms) {
        this.messages = messages;
        this.holograms = holograms;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!sender.hasPermission("mcparty.admin") && !sender.hasPermission("mcparty.admin.hologram")) {
            messages.send(sender, "general.no-permission");
            return true;
        }
        if (args.length == 0) {
            messages.send(sender, "admin.hologram-usage");
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "list" -> list(sender);
            case "remove" -> remove(sender, args);
            case "reload" -> {
                holograms.reloadDefinitions();
                messages.send(sender, "admin.hologram-reloaded");
            }
            case "create", "move", "setline", "addline", "removeline" -> edit(sender, subcommand, args);
            default -> messages.send(sender, "admin.hologram-usage");
        }
        return true;
    }

    private void list(CommandSender sender) {
        List<String> ids = holograms.allIds();
        if (ids.isEmpty()) {
            messages.send(sender, "admin.hologram-none");
            return;
        }
        for (String id : ids) {
            messages.send(sender, "admin.hologram-entry", "id", id,
                    "scope", holograms.scopeOf(id).orElse("global"));
        }
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "admin.hologram-remove-usage");
            return;
        }
        messages.send(sender, holograms.remove(args[1])
                ? "admin.hologram-removed" : "admin.hologram-not-found", "id", args[1]);
    }

    private void edit(CommandSender sender, String subcommand, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.players-only");
            return;
        }
        if (args.length < 2) {
            messages.send(player, "admin.hologram-usage");
            return;
        }

        String id = args[1];
        switch (subcommand) {
            case "create" -> messages.send(player, holograms.create(id, player.getLocation())
                    ? "admin.hologram-created" : "admin.hologram-create-failed", "id", id);
            case "move" -> messages.send(player, holograms.move(id, player.getLocation())
                    ? "admin.hologram-moved" : "admin.hologram-not-found", "id", id);
            case "setline" -> setLine(player, id, args);
            case "addline" -> addLine(player, id, args);
            case "removeline" -> removeLine(player, id, args);
            default -> messages.send(player, "admin.hologram-usage");
        }
    }

    private void setLine(Player player, String id, String[] args) {
        if (args.length < 4 || !args[2].matches("\\d+")) {
            messages.send(player, "admin.hologram-line-usage");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        messages.send(player, holograms.setLine(id, Integer.parseInt(args[2]), text)
                ? "admin.hologram-line-set" : "admin.hologram-line-failed", "id", id);
    }

    private void addLine(Player player, String id, String[] args) {
        if (args.length < 3) {
            messages.send(player, "admin.hologram-line-usage");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        messages.send(player, holograms.addLine(id, text)
                ? "admin.hologram-line-added" : "admin.hologram-line-failed", "id", id);
    }

    private void removeLine(Player player, String id, String[] args) {
        if (args.length < 3 || !args[2].matches("\\d+")) {
            messages.send(player, "admin.hologram-line-usage");
            return;
        }
        messages.send(player, holograms.removeLine(id, Integer.parseInt(args[2]))
                ? "admin.hologram-line-removed" : "admin.hologram-line-failed", "id", id);
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && List.of("remove", "move", "setline", "addline", "removeline").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(holograms.ids(), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)) {
                result.add(option);
            }
        }
        return result;
    }
}
