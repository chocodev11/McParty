package dev.epicc.board.setup;

import dev.epicc.board.BoardPath;
import dev.epicc.board.BoardSlotRegistry;
import dev.epicc.containment.SlotBoundary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PathSetupService {

    private static final Material CENTER_MATERIAL = Material.GOLD_BLOCK;
    private static final Material RING_MATERIAL = Material.YELLOW_WOOL;
    private static final int Y_BELOW = 1;
    private static final int Y_ABOVE = 5;

    private final JavaPlugin plugin;
    private final BoardSlotRegistry slots;
    private final WorldEditHook worldEdit;
    private final Map<UUID, PathSetupSession> sessions = new ConcurrentHashMap<>();

    public PathSetupService(JavaPlugin plugin, BoardSlotRegistry slots, WorldEditHook worldEdit) {
        this.plugin = plugin;
        this.slots = slots;
        this.worldEdit = worldEdit;
    }

    public boolean isSettingUp(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public Optional<String> start(Player player, String rawName) {
        if (sessions.containsKey(player.getUniqueId())) {
            return Optional.of("Already setting up a path. Use /partyadmin path end or undo.");
        }
        if (rawName == null || rawName.isBlank()) {
            return Optional.of("Path name required.");
        }
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_\\-]+")) {
            return Optional.of("Invalid name. Use letters, numbers, _ or -.");
        }
        if (slots.get(name).isPresent()) {
            return Optional.of("Board '" + name + "' already exists. Delete it first.");
        }
        sessions.put(player.getUniqueId(), new PathSetupSession(player.getUniqueId(), name, player.getWorld()));
        msg(player, "Path setup '" + name + "' started. Set WorldEdit pos1 for each space.", false);
        return Optional.empty();
    }

    public void tryPlaceFromWorldEdit(Player player) {
        if (!isSettingUp(player.getUniqueId())) {
            return;
        }
        worldEdit.primaryPosition(player).ifPresent(pos -> onPrimarySelected(player, pos));
    }

    public void onPrimarySelected(Player player, Location blockLoc) {
        PathSetupSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (blockLoc.getWorld() == null || !blockLoc.getWorld().equals(session.world())) {
            msg(player, "pos1 must be in the same world as path setup.", true);
            return;
        }
        if (session.isDuplicatePrimary(blockLoc)) {
            return;
        }

        int cx = blockLoc.getBlockX();
        int cy = blockLoc.getBlockY();
        int cz = blockLoc.getBlockZ();
        int minX = cx - 1;
        int maxX = cx + 1;
        int minZ = cz - 1;
        int maxZ = cz + 1;

        List<PlacedSpace.BlockSnapshot> snapshots = new ArrayList<>(9);
        World world = session.world();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Block block = world.getBlockAt(x, cy, z);
                snapshots.add(PlacedSpace.BlockSnapshot.capture(block));
                boolean center = x == cx && z == cz;
                block.setType(center ? CENTER_MATERIAL : RING_MATERIAL, false);
            }
        }

        Location pathPoint = new Location(
                world,
                cx + 0.5,
                cy + 1.0,
                cz + 0.5,
                player.getLocation().getYaw(),
                player.getLocation().getPitch()
        );
        Location centerBlock = new Location(world, cx, cy, cz);
        PlacedSpace space = new PlacedSpace(pathPoint, centerBlock, minX, maxX, minZ, maxZ, cy, snapshots);
        session.spaces().add(space);
        session.markPrimary(blockLoc);

        int index = session.spaces().size() - 1;
        msg(player, "Space #" + index + " placed (" + session.spaces().size() + " total).", false);
    }

    public Optional<String> undo(Player player) {
        PathSetupSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return Optional.of("Not setting up a path. Use /partyadmin path create <name>.");
        }
        if (session.spaces().isEmpty()) {
            return Optional.of("Nothing to undo.");
        }
        PlacedSpace last = session.spaces().remove(session.spaces().size() - 1);
        last.restore();
        session.clearLastPrimary();
        msg(player, "Undid last space (" + session.spaces().size() + " left).", false);
        return Optional.empty();
    }

    public Optional<String> end(Player player) {
        PathSetupSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return Optional.of("Not setting up a path. Use /partyadmin path create <name>.");
        }
        if (session.spaces().isEmpty()) {
            return Optional.of("Add at least one space with WorldEdit pos1 before ending.");
        }

        BoardPath path = new BoardPath();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minPadY = Integer.MAX_VALUE;
        int maxPadY = Integer.MIN_VALUE;

        for (PlacedSpace space : session.spaces()) {
            path.add(space.pathPoint());
            minX = Math.min(minX, space.minX());
            maxX = Math.max(maxX, space.maxX());
            minZ = Math.min(minZ, space.minZ());
            maxZ = Math.max(maxZ, space.maxZ());
            minPadY = Math.min(minPadY, space.y());
            maxPadY = Math.max(maxPadY, space.y());
        }

        Location spawn = session.spaces().get(0).pathPoint();
        SlotBoundary boundary = new SlotBoundary(
                session.world(),
                minX,
                minPadY - Y_BELOW,
                minZ,
                maxX,
                maxPadY + Y_ABOVE,
                maxZ
        );

        if (!slots.createReady(session.name(), session.world(), boundary, path, spawn)) {
            return Optional.of("Board '" + session.name() + "' already exists.");
        }

        sessions.remove(player.getUniqueId());
        msg(player, "Path '" + session.name() + "' saved with " + path.size() + " space(s).", false);
        return Optional.empty();
    }

    public void cancel(Player player) {
        cancel(player.getUniqueId());
    }

    public void cancel(UUID playerId) {
        PathSetupSession session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        for (int i = session.spaces().size() - 1; i >= 0; i--) {
            session.spaces().get(i).restore();
        }
        Player online = plugin.getServer().getPlayer(playerId);
        if (online != null && online.isOnline()) {
            msg(online, "Path setup cancelled; pads restored.", true);
        }
    }

    public void cancelAll() {
        for (UUID id : List.copyOf(sessions.keySet())) {
            cancel(id);
        }
    }

    private static void msg(Player player, String text, boolean error) {
        player.sendMessage(Component.text("[McParty] " + text, error ? NamedTextColor.RED : NamedTextColor.GREEN));
    }
}
