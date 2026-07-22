package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Minigame pick reveal: subtitle roulette that starts fast and slows to a stop,
 * then title expands the chosen name from the middle letters outward.
 */
final class MinigameRevealAnimator {

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final int spinDurationTicks;
    private final int intervalMinTicks;
    private final int intervalMaxTicks;
    private final int expandIntervalTicks;

    private BukkitTask task;
    private boolean stopped;

    private enum Phase { SPIN, EXPAND }

    MinigameRevealAnimator(
            JavaPlugin plugin,
            MessageService messages,
            int spinDurationTicks,
            int intervalMinTicks,
            int intervalMaxTicks,
            int expandIntervalTicks
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.spinDurationTicks = Math.max(1, spinDurationTicks);
        this.intervalMinTicks = Math.max(1, intervalMinTicks);
        this.intervalMaxTicks = Math.max(this.intervalMinTicks, intervalMaxTicks);
        this.expandIntervalTicks = Math.max(1, expandIntervalTicks);
    }

    void start(List<Player> players, Minigame picked, List<String> poolNames, Runnable onDone) {
        cancel();
        stopped = false;
        List<Player> audience = new ArrayList<>(players);
        List<String> names = poolNames.isEmpty()
                ? List.of(picked.displayName())
                : List.copyOf(poolNames);
        String finalName = picked.displayName();
        int expandMax = expandMaxStep(finalName);

        Phase[] phase = {Phase.SPIN};
        int[] spinTick = {0};
        int[] nextSwapAt = {0};
        String[] currentSubtitle = {randomName(names, null)};
        int[] expandStep = {0};
        int[] expandWait = {0};

        showSpinFrame(audience, currentSubtitle[0]);

        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (stopped) {
                return;
            }

            if (phase[0] == Phase.SPIN) {
                spinTick[0]++;
                if (spinTick[0] >= spinDurationTicks) {
                    currentSubtitle[0] = finalName;
                    phase[0] = Phase.EXPAND;
                    expandStep[0] = 0;
                    expandWait[0] = 0;
                    showExpandFrame(audience, finalName, 0);
                    return;
                }
                if (spinTick[0] >= nextSwapAt[0]) {
                    double progress = (double) spinTick[0] / spinDurationTicks;
                    // Quadratic ease-in: swaps start frequent, end sparse
                    double eased = progress * progress;
                    int interval = intervalMinTicks
                            + (int) Math.round((intervalMaxTicks - intervalMinTicks) * eased);
                    interval = Math.max(1, interval);

                    boolean nearEnd = spinTick[0] + interval >= spinDurationTicks || progress >= 0.85;
                    if (nearEnd) {
                        currentSubtitle[0] = finalName;
                    } else {
                        currentSubtitle[0] = randomName(names, currentSubtitle[0]);
                    }
                    showSpinFrame(audience, currentSubtitle[0]);
                    nextSwapAt[0] = spinTick[0] + interval;
                }
                return;
            }

            // EXPAND — middle letter(s) first, then outward
            expandWait[0]++;
            if (expandWait[0] < expandIntervalTicks) {
                return;
            }
            expandWait[0] = 0;
            expandStep[0]++;
            if (expandStep[0] > expandMax) {
                stopTaskOnly();
                showFinal(audience, picked);
                onDone.run();
                return;
            }
            showExpandFrame(audience, finalName, expandStep[0]);
        }, 1L, 1L);
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
        if (names.isEmpty()) {
            return "";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String pick = names.get(r.nextInt(names.size()));
        if (avoidIfPossible != null && pick.equals(avoidIfPossible)) {
            pick = names.get(r.nextInt(names.size()));
        }
        return pick;
    }

    /**
     * Center-out mask: step 0 = middle letter(s); each step unlocks one char left and right.
     * Hidden positions are spaces so the title width stays stable.
     */
    static String expandFrame(String name, int step) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        int n = name.length();
        int left = (n - 1) / 2;
        int right = n / 2;
        int L = Math.max(0, left - step);
        int R = Math.min(n - 1, right + step);
        char[] out = new char[n];
        for (int i = 0; i < n; i++) {
            out[i] = (i >= L && i <= R) ? name.charAt(i) : ' ';
        }
        return new String(out);
    }

    static int expandMaxStep(String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        int n = name.length();
        int left = (n - 1) / 2;
        int right = n / 2;
        return Math.max(left, n - 1 - right);
    }

    private void showSpinFrame(List<Player> players, String subtitle) {
        Title title = Title.title(
                messages.get("minigame.reveal-spin-title"),
                Component.text(subtitle == null ? "" : subtitle),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(500), Duration.ZERO)
        );
        show(players, title);
    }

    private void showExpandFrame(List<Player> players, String fullName, int step) {
        Title title = Title.title(
                Component.text(expandFrame(fullName, step)),
                messages.get("minigame.reveal-ready-subtitle"),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ZERO)
        );
        show(players, title);
    }

    private void showFinal(List<Player> players, Minigame picked) {
        Title title = Title.title(
                Component.text(picked.displayName()),
                messages.get("minigame.reveal-ready-subtitle"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(300))
        );
        for (Player player : players) {
            if (player.isOnline()) {
                player.showTitle(title);
                messages.send(player, "minigame.selected", "name", picked.displayName());
            }
        }
    }

    private static void show(List<Player> players, Title title) {
        for (Player player : players) {
            if (player.isOnline()) {
                player.showTitle(title);
            }
        }
    }
}
