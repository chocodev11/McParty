package dev.epicc.command;

import dev.epicc.McPartyPlugin;
import dev.epicc.board.BoardSlot;
import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.board.setup.PathSetupService;
import dev.epicc.config.MessageService;
import dev.epicc.config.PluginConfig;
import dev.epicc.lobby.parkour.LobbyParkourService;
import dev.epicc.minigame.ElytraCourse;
import dev.epicc.minigame.ElytraCourseStore;
import dev.epicc.minigame.Minigame;
import dev.epicc.minigame.MinigameManager;
import dev.epicc.slime.SlimeWorldService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PartyAdminCommand implements CommandExecutor, TabCompleter {

    private final McPartyPlugin plugin;
    private final BoardSlotRegistry slots;
    private final PathSetupService pathSetup;
    private final MessageService messages;
    private final SlimeWorldService slime;
    private final MinigameManager minigames;
    private final PluginConfig config;
    private final LobbyParkourService lobbyParkour;
    private final ElytraCourseStore elytraCourses;
    private final Map<UUID, Location> elytraPos1 = new HashMap<>();
    private final Map<UUID, Location> elytraPos2 = new HashMap<>();
    private final Map<UUID, String> elytraPos1Course = new HashMap<>();
    private final Map<UUID, String> elytraPos2Course = new HashMap<>();

    public PartyAdminCommand(
            McPartyPlugin plugin,
            BoardSlotRegistry slots,
            PathSetupService pathSetup,
            MessageService messages,
            SlimeWorldService slime,
            MinigameManager minigames,
            PluginConfig config,
            LobbyParkourService lobbyParkour,
            ElytraCourseStore elytraCourses
    ) {
        this.plugin = plugin;
        this.slots = slots;
        this.pathSetup = pathSetup;
        this.messages = messages;
        this.slime = slime;
        this.minigames = minigames;
        this.config = config;
        this.lobbyParkour = lobbyParkour;
        this.elytraCourses = elytraCourses;
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

        if (args.length == 0) {
            help(sender);
            return true;
        }

        String group = args[0].toLowerCase(Locale.ROOT);
        if (group.equals("minigame") || group.equals("mg")) {
            handleMinigame(sender, args);
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.players-only-except-reload");
            return true;
        }

        switch (group) {
            case "slot" -> handleSlot(player, args);
            case "path" -> handlePath(player, args);
            case "setlobby" -> setLobby(player);
            case "parkour" -> handleParkour(player, args);
            case "elytra" -> handleElytra(player, args);
            default -> help(player);
        }
        return true;
    }

    private void handleMinigame(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "admin.minigame-usage");
            return;
        }
        String minigameId = args[1];
        Optional<Minigame> minigameOpt = minigames.registry().get(minigameId);
        if (minigameOpt.isEmpty()) {
            messages.send(sender, "admin.minigame-not-found", "id", minigameId);
            return;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayer(args[2]);
            if (target == null || !target.isOnline()) {
                messages.send(sender, "admin.player-not-found", "name", args[2]);
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            messages.send(sender, "admin.minigame-player-required");
            return;
        }

        Minigame mg = minigameOpt.get();
        messages.send(sender, "admin.minigame-testing", "name", mg.displayName(), "player", target.getName());
        minigames.runSpecific(mg, List.of(target), result -> {
            messages.send(sender, "admin.minigame-test-done", "player", target.getName());
        });
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

    private void handleElytra(Player player, String[] args) {
        if (args.length < 2) {
            messages.send(player, "admin.elytra-usage");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (args.length < 4) {
                    messages.send(player, "admin.elytra-create-usage");
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                String template = args[3].trim();
                if (elytraCourses.create(id, player.getWorld().getName(), template)) {
                    messages.send(player, "admin.elytra-created", "id", id, "template", template);
                } else {
                    messages.send(player, "admin.elytra-create-failed", "id", id);
                }
            }
            case "delete" -> {
                if (args.length < 3) {
                    messages.send(player, "admin.elytra-delete-usage");
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                if (elytraCourses.delete(id)) {
                    messages.send(player, "admin.elytra-deleted", "id", id);
                } else {
                    messages.send(player, "admin.elytra-not-found", "id", id);
                }
            }
            case "list" -> {
                if (elytraCourses.all().isEmpty()) {
                    messages.send(player, "admin.elytra-none");
                    return;
                }
                for (ElytraCourse course : elytraCourses.all()) {
                    messages.send(player, "admin.elytra-entry",
                            MessageService.ph("id", course.id()),
                            MessageService.ph("template", course.arenaSpec().template()),
                            MessageService.ph("rings", Integer.toString(course.rings().size())),
                            MessageService.ph("ready", Boolean.toString(course.isReady())),
                            MessageService.ph("active", Boolean.toString(course.id().equals(config.elytraCourseId()))));
                }
            }
            case "activate" -> {
                if (args.length < 3) {
                    messages.send(player, "admin.elytra-activate-usage");
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                Optional<ElytraCourse> course = elytraCourses.get(id);
                if (course.isEmpty()) {
                    messages.send(player, "admin.elytra-not-found", "id", id);
                    return;
                }
                config.setElytraCourseId(id);
                plugin.reloadPluginConfig();
                messages.send(player, "admin.elytra-activated", "id", id);
            }
            case "spawn" -> {
                Optional<ElytraCourse> course = editableCourse(player, args);
                if (course.isEmpty()) {
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                elytraCourses.update(id, current -> current.withSpawn(player.getLocation()));
                messages.send(player, "admin.elytra-spawn-set", "id", id);
            }
            case "pos1", "pos2" -> {
                Optional<ElytraCourse> course = editableCourse(player, args);
                if (course.isEmpty()) {
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                Map<UUID, Location> selection = sub.equals("pos1") ? elytraPos1 : elytraPos2;
                Map<UUID, String> selectionCourse = sub.equals("pos1") ? elytraPos1Course : elytraPos2Course;
                selection.put(player.getUniqueId(), player.getLocation().clone());
                selectionCourse.put(player.getUniqueId(), id);
                messages.send(player, "admin.elytra-position-set", "position", sub, "id", id);
            }
            case "boundary" -> {
                if (args.length < 3) {
                    messages.send(player, "admin.elytra-course-required");
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                Optional<ElytraCourse> course = elytraCourses.get(id);
                Location first = elytraPos1.get(player.getUniqueId());
                Location second = elytraPos2.get(player.getUniqueId());
                if (course.isEmpty()) {
                    messages.send(player, "admin.elytra-not-found", "id", id);
                } else if (first == null || second == null
                        || !id.equals(elytraPos1Course.get(player.getUniqueId()))
                        || !id.equals(elytraPos2Course.get(player.getUniqueId()))) {
                    messages.send(player, "admin.elytra-boundary-required");
                } else if (!sameWorld(course.get(), first) || !sameWorld(course.get(), second)) {
                    messages.send(player, "admin.elytra-wrong-world", "world", course.get().setupWorldName());
                } else {
                    elytraCourses.update(id, current -> current.withBoundary(first, second));
                    messages.send(player, "admin.elytra-boundary-set", "id", id);
                }
            }
            case "ring", "finish" -> {
                Optional<ElytraCourse> course = editableCourse(player, args);
                if (course.isEmpty()) {
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                double radius = config.elytraDefaultRingRadius();
                if (args.length >= 4) {
                    try {
                        radius = Double.parseDouble(args[3]);
                    } catch (NumberFormatException exception) {
                        messages.send(player, "admin.elytra-invalid-radius");
                        return;
                    }
                }
                if (!Double.isFinite(radius) || radius <= 0.0) {
                    messages.send(player, "admin.elytra-invalid-radius");
                    return;
                }
                double ringRadius = radius;
                elytraCourses.update(id, current -> current.withRing(player.getEyeLocation(), ringRadius));
                messages.send(player, sub.equals("finish")
                        ? "admin.elytra-finish-added"
                        : "admin.elytra-ring-added", "id", id,
                        "count", Integer.toString(course.get().rings().size() + 1));
            }
            case "undo" -> {
                if (args.length < 3) {
                    messages.send(player, "admin.elytra-course-required");
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                Optional<ElytraCourse> course = elytraCourses.get(id);
                if (course.isEmpty()) {
                    messages.send(player, "admin.elytra-not-found", "id", id);
                } else if (course.get().rings().isEmpty()) {
                    messages.send(player, "admin.elytra-no-rings");
                } else {
                    elytraCourses.update(id, ElytraCourse::withoutLastRing);
                    messages.send(player, "admin.elytra-ring-removed", "id", id);
                }
            }
            default -> messages.send(player, "admin.elytra-usage");
        }
    }

    private Optional<ElytraCourse> editableCourse(Player player, String[] args) {
        if (args.length < 3) {
            messages.send(player, "admin.elytra-course-required");
            return Optional.empty();
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        Optional<ElytraCourse> course = elytraCourses.get(id);
        if (course.isEmpty()) {
            messages.send(player, "admin.elytra-not-found", "id", id);
            return Optional.empty();
        }
        if (!player.getWorld().getName().equals(course.get().setupWorldName())) {
            messages.send(player, "admin.elytra-wrong-world", "world", course.get().setupWorldName());
            return Optional.empty();
        }
        return course;
    }

    private static boolean sameWorld(ElytraCourse course, Location location) {
        return location.getWorld() != null
                && location.getWorld().getName().equals(course.setupWorldName());
    }

    private void handleParkour(Player player, String[] args) {
        if (args.length < 2) {
            messages.send(player, "admin.parkour-usage");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                if (lobbyParkour.configureStart(player)) {
                    messages.send(player, "admin.parkour-start-set");
                } else {
                    messages.send(player, "admin.parkour-pressure-plate-required");
                }
            }
            case "checkpoint" -> {
                if (lobbyParkour.addCheckpoint(player)) {
                    messages.send(player, "admin.parkour-checkpoint-added", "count",
                            Integer.toString(config.lobbyParkour().checkpoints().size()));
                } else {
                    messages.send(player, "admin.parkour-pressure-plate-required");
                }
            }
            case "goal" -> {
                lobbyParkour.configureGoal(player);
                messages.send(player, "admin.parkour-goal-set");
            }
            case "leaderboard" -> {
                lobbyParkour.configureLeaderboard(player);
                messages.send(player, "admin.parkour-leaderboard-set");
            }
            case "remove-checkpoint" -> {
                if (args.length < 3 || !args[2].matches("\\d+")) {
                    messages.send(player, "admin.parkour-remove-checkpoint-usage");
                    return;
                }
                int index = Integer.parseInt(args[2]) - 1;
                if (config.removeLobbyParkourCheckpoint(index)) {
                    messages.send(player, "admin.parkour-checkpoint-removed", "index", args[2]);
                } else {
                    messages.send(player, "admin.parkour-checkpoint-not-found", "index", args[2]);
                }
            }
            case "clear" -> {
                lobbyParkour.clear(player);
                messages.send(player, "admin.parkour-cleared");
            }
            default -> messages.send(player, "admin.parkour-usage");
        }
    }

    private void setLobby(Player player) {
        config.setLobbySpawn(player.getLocation());
        messages.send(player, "admin.lobby-spawn-set");
    }

    private void help(CommandSender sender) {
        messages.send(sender, "admin.help-path");
        messages.send(sender, "admin.help-slot");
        messages.send(sender, "admin.help-minigame");
        messages.send(sender, "admin.help-setlobby");
        messages.send(sender, "admin.help-parkour");
        messages.send(sender, "admin.help-elytra");
        messages.send(sender, "admin.help-reload");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("slot", "path", "setlobby", "parkour", "elytra", "minigame", "reload"), args[0]);
        }
        if (args.length == 2) {
            String g = args[0].toLowerCase(Locale.ROOT);
            if (g.equals("slot")) {
                return filter(List.of("list", "delete"), args[1]);
            }
            if (g.equals("path")) {
                return filter(List.of("create", "undo", "end", "remove", "slime"), args[1]);
            }
            if (g.equals("parkour")) {
                return filter(List.of("start", "checkpoint", "goal", "leaderboard", "remove-checkpoint", "clear"), args[1]);
            }
            if (g.equals("elytra")) {
                return filter(List.of("create", "delete", "list", "activate", "spawn", "pos1", "pos2",
                        "boundary", "ring", "finish", "undo"), args[1]);
            }
            if (g.equals("minigame") || g.equals("mg")) {
                return filter(minigames.registry().ids(), args[1]);
            }
            return List.of();
        }
        if (args.length == 3) {
            String g = args[0].toLowerCase(Locale.ROOT);
            String s = args[1].toLowerCase(Locale.ROOT);
            if ((g.equals("slot") && s.equals("delete"))
                    || (g.equals("path") && (s.equals("remove") || s.equals("slime")))) {
                List<String> ids = slots.all().stream().map(BoardSlot::id).sorted().collect(Collectors.toCollection(ArrayList::new));
                return filter(ids, args[2]);
            }
            if (g.equals("minigame") || g.equals("mg")) {
                List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                return filter(names, args[2]);
            }
            if (g.equals("elytra") && !s.equals("create")) {
                List<String> ids = elytraCourses.all().stream().map(ElytraCourse::id).toList();
                return filter(ids, args[2]);
            }
            if (g.equals("parkour") && s.equals("remove-checkpoint")) {
                List<String> indices = new ArrayList<>();
                for (int i = 1; i <= config.lobbyParkour().checkpoints().size(); i++) {
                    indices.add(Integer.toString(i));
                }
                return filter(indices, args[2]);
            }
            return List.of();
        }
        if (args.length == 4) {
            String g = args[0].toLowerCase(Locale.ROOT);
            String s = args[1].toLowerCase(Locale.ROOT);
            if (g.equals("path") && s.equals("slime")) {
                return filter(slime.listTemplates(), args[3]);
            }
            if (g.equals("elytra") && s.equals("create")) {
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
