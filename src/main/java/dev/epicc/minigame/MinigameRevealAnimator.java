package dev.epicc.minigame;

import dev.epicc.config.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Minigame pick reveal (name on subtitle; final selection fades in and out):
 * 1) Spin: title "???", subtitle cycles names (slow → fast → stop)
 * 2) Expand: title "Get ready!", subtitle grows middle → sides
 * 3) Tint: full subtitle white → yellow
 */
final class MinigameRevealAnimator {

    private static final Duration FADE_IN = Duration.ZERO;
    private static final Duration FINAL_FADE_IN = Duration.ZERO;
    private static final Duration FRAME_STAY = Duration.ofMillis(800);
    private static final Duration STAY_HOLD = Duration.ofSeconds(2);
    private static final Duration FADE_OUT_HOLD = Duration.ofMillis(400);

    /** One tick per frame with no transition while the content changes. */
    private static final Title.Times FRAME = Title.Times.times(FADE_IN, FRAME_STAY, Duration.ZERO);
    /** Final selected game hold with no fade-in and a short fade-out. */
    private static final Title.Times HOLD = Title.Times.times(FINAL_FADE_IN, STAY_HOLD, FADE_OUT_HOLD);
    private static final long FINAL_TITLE_DURATION_TICKS = ticks(STAY_HOLD.plus(FADE_OUT_HOLD));

    private static final TextColor WHITE = TextColor.color(255, 255, 255);
    private static final TextColor YELLOW = TextColor.color(255, 255, 85);

    private static final int SPIN_DURATION_TICKS = 100;
    private static final int INTERVAL_MIN_TICKS = 1;
    private static final int INTERVAL_MAX_TICKS = 8;
    private static final int EXPAND_INTERVAL_TICKS = 1;
    private static final int COLOR_STEPS = 8;
    private static final int COLOR_INTERVAL_TICKS = 1;

    private final JavaPlugin plugin;
    private final MessageService messages;

    private BukkitTask task;
    private boolean stopped;

    private enum Phase { SPIN, EXPAND, COLOR }

    MinigameRevealAnimator(JavaPlugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
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
        int[] colorStep = {0};
        int[] colorWait = {0};

        showSpinFrame(audience, currentSubtitle[0]);

        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (stopped) {
                return;
            }

            if (phase[0] == Phase.SPIN) {
                spinTick[0]++;
                if (spinTick[0] >= SPIN_DURATION_TICKS) {
                    phase[0] = Phase.EXPAND;
                    expandStep[0] = 0;
                    expandWait[0] = 0;
                    showExpandFrame(audience, finalName, 0, WHITE);
                    return;
                }
                if (spinTick[0] >= nextSwapAt[0]) {
                    double progress = (double) spinTick[0] / SPIN_DURATION_TICKS;
                    double eased = (1.0 - progress) * (1.0 - progress);
                    int interval = INTERVAL_MIN_TICKS
                            + (int) Math.round((INTERVAL_MAX_TICKS - INTERVAL_MIN_TICKS) * eased);
                    interval = Math.max(1, interval);


                    boolean nearEnd = spinTick[0] + interval >= SPIN_DURATION_TICKS;
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

            if (phase[0] == Phase.EXPAND) {
                expandWait[0]++;
                if (expandWait[0] < EXPAND_INTERVAL_TICKS) {
                    return;
                }
                expandWait[0] = 0;
                expandStep[0]++;
                if (expandStep[0] > expandMax) {
                    phase[0] = Phase.COLOR;
                    colorStep[0] = 0;
                    colorWait[0] = 0;
                    showNameFrame(audience, finalName, WHITE);
                    return;
                }
                showExpandFrame(audience, finalName, expandStep[0], WHITE);
                return;
            }

            // COLOR — full name white → yellow on subtitle (few steps, longer pause)
            colorWait[0]++;
            if (colorWait[0] < COLOR_INTERVAL_TICKS) {
                return;
            }
            colorWait[0] = 0;
            colorStep[0]++;
            if (colorStep[0] > COLOR_STEPS) {
                stopTaskOnly();
                showFinal(audience, picked);
                task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    task = null;
                    if (!stopped) {
                        onDone.run();
                    }
                }, FINAL_TITLE_DURATION_TICKS);
                return;
            }
            float t = (float) colorStep[0] / COLOR_STEPS;
            TextColor color = lerp(WHITE, YELLOW, t);
            showNameFrame(audience, finalName, color);
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
     * Hidden positions are spaces so the subtitle width stays stable.
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

    private static TextColor lerp(TextColor from, TextColor to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round(from.red() + (to.red() - from.red()) * t);
        int g = Math.round(from.green() + (to.green() - from.green()) * t);
        int b = Math.round(from.blue() + (to.blue() - from.blue()) * t);
        return TextColor.color(r, g, b);
    }

    private static long ticks(Duration duration) {
        return Math.max(1L, (duration.toMillis() + 49L) / 50L);
    }

    private void showSpinFrame(List<Player> players, String subtitle) {
        show(players, Title.title(
                messages.get("minigame.reveal-spin-title"),
                Component.text(subtitle == null ? "" : subtitle, WHITE),
                FRAME
        ));
    }

    private void showExpandFrame(List<Player> players, String fullName, int step, TextColor color) {
        showNameFrame(players, expandFrame(fullName, step), color);
    }

    private void showNameFrame(List<Player> players, String subtitle, TextColor color) {
        show(players, Title.title(
                messages.get("minigame.reveal-ready-title"),
                Component.text(subtitle == null ? "" : subtitle, color),
                FRAME
        ));
    }

    private void showFinal(List<Player> players, Minigame picked) {
        Title title = Title.title(
                messages.get("minigame.reveal-ready-title"),
                Component.text(picked.displayName(), YELLOW),
                HOLD
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
