package dev.epicc.minigame;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class DummyMinigame implements Minigame {

    private final int durationSeconds;
    private final List<Integer> coinRewards;
    private BukkitTask task;

    public DummyMinigame(int durationSeconds, List<Integer> coinRewards) {
        this.durationSeconds = Math.max(1, durationSeconds);
        this.coinRewards = coinRewards;
    }

    @Override
    public String id() {
        return "dummy";
    }

    @Override
    public void start(MinigameContext context, Consumer<MinigameResult> done) {
        cancel();
        Title title = Title.title(
                Component.text("Minigame!", NamedTextColor.GOLD),
                Component.text("Dummy round…", NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(250))
        );
        for (Player player : context.onlinePlayers()) {
            player.showTitle(title);
            player.sendMessage(Component.text("[McParty] Dummy minigame started!", NamedTextColor.AQUA));
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
                p.sendMessage(Component.text(
                        "[McParty] Place #" + place + " (+" + coins + " coins)",
                        NamedTextColor.GREEN
                ));
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
