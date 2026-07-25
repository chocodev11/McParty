package dev.epicc.party;

import dev.epicc.containment.SlotBoundary;
import org.bukkit.Location;
import org.bukkit.World;

/** Runtime world, spawn and boundary currently permitted for a party. */
public record PartyPlayArea(World world, Location spawn, SlotBoundary boundary) {
    public PartyPlayArea {
        spawn = spawn.clone();
    }

    public Location spawn() { return spawn.clone(); }
}
