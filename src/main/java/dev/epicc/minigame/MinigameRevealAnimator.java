package dev.epicc.minigame;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Title roulette before a minigame starts. No teleport — players stay where they are.
 * Customize tick timing via config; swap title content here when you refine the animation.
 */
final class MinigameRevealAnimator {

    private final JavaPlugin plugin;
    private final int durationTicks;
    private final int intervalTicks;

    private BukkitTask task;
    private int elapsed;
    private boolean stopped;

    MinigameRevealAnimator(JavaPlugin plugin, int durationTicks, int intervalTicks) {
        this.plugin = plugin;
        this.durationTicks = Math.max(1, durationTicks);
        this.intervalTicks = Math.max(1, intervalTicks);
    }

    void start(List<Player> players, Minigame picked, List<String> poolNames, Runnable onDone) {
        cancel();
        stopped = false;
        elapsed = 0;
        List<Player> audience = new ArrayList<>(players);
        List<String> names = poolNames.isEmpty()
                ? List.of(picked.displayName())
                : List.copyOf(poolNames);

        // immediate first frame
        showSpinFrame(audience, randomName(names, picked.displayName()));

        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (stopped) {
                return;
            }
            elapsed += intervalTicks;
            if (elapsed >= durationTicks) {
                stopTaskOnly();
                showFinal(audience, picked);
                onDone.run();
                return;
            }
            showSpinFrame(audience, randomName(names, picked.displayName()));
        }, intervalTicks, intervalTicks);
    }

    void cancel() {
        stopped = true;
        stopTaskOnly();
    }

    private void stopTaskOnly() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private static String randomName(List<String> names, String avoidIfPossible) {
        if (names.size() == 1) {
            return names.get(0);
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String pick = names.get(r.nextInt(names.size()));
        if (names.size() > 1 && pick.equals(avoidIfPossible)) {
            pick = names.get(r.nextInt(names.size()));
        }
        return pick;
    }

    private static void showSpinFrame(List<Player> players, String name) {
        Title title = Title.title(
                Component.text("???", NamedTextColor.YELLOW),
                Component.text(name, NamedTextColor.GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ZERO)
        );
        for (Player player : players) {
            if (player.isOnline()) {
                player.showTitle(title);
            }
        }
    }

    private static void showFinal(List<Player> players, Minigame picked) {
        Title title = Title.title(
                Component.text(picked.displayName(), NamedTextColor.GOLD),
                Component.text("Get ready!", NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(300))
        );
        for (Player player : players) {
            if (player.isOnline()) {
                player.showTitle(title);
                player.sendMessage(Component.text(
                        "[McParty] Minigame: " + picked.displayName(),
                        NamedTextColor.LIGHT_PURPLE
                ));
            }
        }
    }
}
