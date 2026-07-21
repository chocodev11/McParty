package dev.epicc.board.setup;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.RegionSelector;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;

public final class WorldEditHook {

    public Optional<Location> primaryPosition(Player player) {
        try {
            com.sk89q.worldedit.entity.Player wePlayer = BukkitAdapter.adapt(player);
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(wePlayer);
            RegionSelector selector = session.getRegionSelector(wePlayer.getWorld());
            BlockVector3 pos = selector.getPrimaryPosition();
            World world = player.getWorld();
            return Optional.of(new Location(world, pos.x(), pos.y(), pos.z()));
        } catch (IncompleteRegionException e) {
            return Optional.empty();
        }
    }
}
