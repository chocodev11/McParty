package dev.epicc.command;

import dev.epicc.config.MessageService;
import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyManager;
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

public final class PartyCommand implements CommandExecutor, TabCompleter {

    private final PartyManager parties;
    private final MessageService messages;

    public PartyCommand(PartyManager parties, MessageService messages) {
        this.parties = parties;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.players-only");
            return true;
        }
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        Optional<Component> err = switch (sub) {
            case "create" -> parties.create(player);
            case "join" -> parties.join(player, args.length > 1 ? args[1] : null);
            case "leave" -> parties.leave(player, false);
            case "start" -> parties.start(player);
            case "list" -> {
                list(player);
                yield Optional.empty();
            }
            case "end" -> {
                if (!player.hasPermission("mcparty.admin")) {
                    yield Optional.of(messages.get("general.no-permission"));
                }
                yield parties.forceEnd(player, args.length > 1 ? args[1] : null);
            }
            case "roll" -> parties.roll(player);
            default -> {
                sendHelp(player);
                yield Optional.empty();
            }
        };
        err.ifPresent(player::sendMessage);
        return true;
    }

    private void list(Player player) {
        var all = parties.all();
        if (all.isEmpty()) {
            messages.send(player, "party.list-empty");
            return;
        }
        messages.send(player, "party.list-header");
        for (PartyInstance i : all) {
            player.sendMessage(messages.get(
                    "party.list-entry",
                    "id", i.shortId(),
                    "state", i.state().name(),
                    "count", Integer.toString(i.playerCount())
            ));
        }
    }

    private void sendHelp(Player player) {
        messages.send(player, "party.help");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("create", "join", "leave", "start", "list", "roll", "end"), args[0]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
