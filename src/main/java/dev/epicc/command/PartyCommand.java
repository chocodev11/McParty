package dev.epicc.command;

import dev.epicc.party.PartyInstance;
import dev.epicc.party.PartyManager;
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

public final class PartyCommand implements CommandExecutor, TabCompleter {

    private final PartyManager parties;

    public PartyCommand(PartyManager parties) {
        this.parties = parties;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        Optional<String> err = switch (sub) {
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
                    yield Optional.of("No permission.");
                }
                yield parties.forceEnd(player, args.length > 1 ? args[1] : null);
            }
            case "roll" -> parties.roll(player);
            default -> {
                sendHelp(player);
                yield Optional.empty();
            }
        };
        err.ifPresent(msg -> player.sendMessage(Component.text("[McParty] " + msg, NamedTextColor.RED)));
        return true;
    }

    private void list(Player player) {
        var all = parties.all();
        if (all.isEmpty()) {
            player.sendMessage(Component.text("[McParty] No active parties.", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text("[McParty] Parties:", NamedTextColor.GOLD));
        for (PartyInstance i : all) {
            player.sendMessage(Component.text(
                    " - " + i.shortId() + " | " + i.state() + " | " + i.playerCount() + "p",
                    NamedTextColor.YELLOW
            ));
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("/party create|join [id]|leave|start|list|roll|end", NamedTextColor.AQUA));
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
