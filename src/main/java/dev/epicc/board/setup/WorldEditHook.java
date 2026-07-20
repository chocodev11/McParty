package dev.epicc.board.setup;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import dev.epicc.containment.SlotBoundary;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;

public final class WorldEditHook {

    public Optional<SlotBoundary> selectionAsBoundary(Player player) {
        try {
            com.sk89q.worldedit.entity.Player wePlayer = BukkitAdapter.adapt(player);
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(wePlayer);
            Region region = session.getSelection(wePlayer.getWorld());
            if (!(region instanceof CuboidRegion cuboid)) {
                return Optional.empty();
            }
            World world = player.getWorld();
            BlockVector3 min = cuboid.getMinimumPoint();
            BlockVector3 max = cuboid.getMaximumPoint();
            return Optional.of(new SlotBoundary(
                    world,
                    min.x(), min.y(), min.z(),
                    max.x(), max.y(), max.z()
            ));
        } catch (IncompleteRegionException e) {
            return Optional.empty();
        }
    }
}
