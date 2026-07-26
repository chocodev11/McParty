package dev.epicc.minigame;

/** Timing of the minigame pick reveal (title roulette → expand → tint). */
public record MinigameRevealSettings(
        int durationTicks,
        int intervalMinTicks,
        int intervalMaxTicks,
        int expandIntervalTicks,
        int colorSteps,
        int colorIntervalTicks
) {
    public MinigameRevealSettings {
        intervalMinTicks = Math.max(1, intervalMinTicks);
        intervalMaxTicks = Math.max(intervalMinTicks, intervalMaxTicks);
        expandIntervalTicks = Math.max(1, expandIntervalTicks);
        colorSteps = Math.max(1, colorSteps);
        colorIntervalTicks = Math.max(1, colorIntervalTicks);
    }

    /** Reveal is disabled — start the session immediately. */
    public boolean skip() {
        return durationTicks <= 0;
    }
}
