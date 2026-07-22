package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Placeholder minigame used for board rounds and reveal-animation testing.
 * Multiple instances can be registered with different ids / display names.
 */
public final class DummyMinigame implements Minigame {

    private final MessageService messages;
    private final String id;
    private final String displayName;
    private int durationSeconds;
    private List<Integer> coinRewards;
    private BukkitTask task;

    public DummyMinigame(
            MessageService messages,
            String id,
            String displayName,
            int durationSeconds,
            List<Integer> coinRewards
    ) {
        this.messages = messages;
        this.id = id;
        this.displayName = displayName;
        reconfigure(durationSeconds, coinRewards);
    }

    public void reconfigure(int durationSeconds, List<Integer> coinRewards) {
        this.durationSeconds = Math.max(1, durationSeconds);
        this.coinRewards = coinRewards;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public void start(MinigameContext context, Consumer<MinigameResult> done) {
        cancel();
        for (Player player : context.onlinePlayers()) {
            messages.send(player, "minigame.dummy-started", "name", displayName);
        }

        task = context.plugin().getServer().getScheduler().runTaskLater(context.plugin(), () -> {
            List<Player> players = new ArrayList<>(context.onlinePlayers());
            Collections.shuffle(players, ThreadLocalRandom.current());
            MinigameResult result = new MinigameResult();
            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                int place = i + 1;
                int coins = i < coinRewards.size() ? coinRewards.get(i) : 1;
                result.setPlacement(p.getUniqueId(), place);
                result.setCoins(p.getUniqueId(), coins);
                messages.send(
                        p,
                        "minigame.dummy-place",
                        "place", Integer.toString(place),
                        "coins", Integer.toString(coins)
                );
            }
            done.accept(result);
        }, durationSeconds * 20L);
    }

    @Override
    public void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
