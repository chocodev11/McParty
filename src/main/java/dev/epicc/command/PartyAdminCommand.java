package dev.epicc.command;

import dev.epicc.board.BoardSlot;
import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.board.setup.WorldEditHook;
import dev.epicc.containment.SlotBoundary;
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
    private final WorldEditHook worldEdit;

    public PartyAdminCommand(BoardSlotRegistry slots, WorldEditHook worldEdit) {
        this.slots = slots;
        this.worldEdit = worldEdit;
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
            player.sendMessage(Component.text("/partyadmin slot <create|delete|list|spawn> [id]", NamedTextColor.AQUA));
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (args.length < 3) {
                    msg(player, "Usage: /partyadmin slot create <id>", true);
                    return;
                }
                Optional<SlotBoundary> boundary = worldEdit.selectionAsBoundary(player);
                if (boundary.isEmpty()) {
                    msg(player, "Make a complete WorldEdit cuboid selection first.", true);
                    return;
                }
                if (slots.create(args[2], boundary.get())) {
                    msg(player, "Created slot '" + args[2].toLowerCase(Locale.ROOT) + "'. Add path + spawn.", false);
                } else {
                    msg(player, "Slot already exists.", true);
                }
            }
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
            case "spawn" -> {
                if (args.length < 3) {
                    msg(player, "Usage: /partyadmin slot spawn <id>", true);
                    return;
                }
                BoardSlot slot = slots.get(args[2]).orElse(null);
                if (slot == null) {
                    msg(player, "Slot not found.", true);
                    return;
                }
                if (!slot.boundary().isInside(player.getLocation())) {
                    msg(player, "Stand inside the slot cuboid.", true);
                    return;
                }
                slot.setSpawn(player.getLocation());
                slots.save();
                msg(player, "Spawn set for " + slot.id(), false);
            }
            default -> player.sendMessage(Component.text("/partyadmin slot <create|delete|list|spawn>", NamedTextColor.AQUA));
        }
    }

    private void handlePath(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("/partyadmin path <add|clear|list> <id>", NamedTextColor.AQUA));
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (args.length < 3) {
            msg(player, "Slot id required.", true);
            return;
        }
        BoardSlot slot = slots.get(args[2]).orElse(null);
        if (slot == null) {
            msg(player, "Slot not found.", true);
            return;
        }
        switch (sub) {
            case "add" -> {
                if (!slot.boundary().isInside(player.getLocation())) {
                    msg(player, "Stand inside the slot cuboid.", true);
                    return;
                }
                slot.path().add(player.getLocation());
                slots.save();
                msg(player, "Path point #" + (slot.path().size() - 1) + " added.", false);
            }
            case "clear" -> {
                slot.path().clear();
                slots.save();
                msg(player, "Path cleared.", false);
            }
            case "list" -> msg(player, "Path size: " + slot.path().size(), false);
            default -> player.sendMessage(Component.text("/partyadmin path <add|clear|list> <id>", NamedTextColor.AQUA));
        }
    }

    private void help(Player player) {
        player.sendMessage(Component.text("/partyadmin slot create|delete|list|spawn", NamedTextColor.AQUA));
        player.sendMessage(Component.text("/partyadmin path add|clear|list <id>", NamedTextColor.AQUA));
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
                case "slot" -> filter(List.of("create", "delete", "list", "spawn"), args[1]);
                case "path" -> filter(List.of("add", "clear", "list"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            String g = args[0].toLowerCase(Locale.ROOT);
            String s = args[1].toLowerCase(Locale.ROOT);
            if (g.equals("slot") && (s.equals("delete") || s.equals("spawn"))
                    || g.equals("path")) {
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
