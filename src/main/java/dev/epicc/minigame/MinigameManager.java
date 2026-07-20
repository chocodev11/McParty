package dev.epicc.minigame;

import dev.epicc.party.PartyInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class MinigameManager {

    private final JavaPlugin plugin;
    private final Minigame dummy;
    private Minigame active;

    public MinigameManager(JavaPlugin plugin, Minigame dummy) {
        this.plugin = plugin;
        this.dummy = dummy;
    }

    public void runDummy(PartyInstance instance, List<Player> players, Consumer<MinigameResult> done) {
        cancelActive();
        active = dummy;
        MinigameContext ctx = new MinigameContext(plugin, instance, players);
        dummy.start(ctx, result -> {
            active = null;
            done.accept(result);
        });
    }

    public void cancelActive() {
        if (active != null) {
            active.cancel();
            active = null;
        }
    }
}
