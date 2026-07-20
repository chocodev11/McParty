package dev.epicc.board;

import java.util.concurrent.ThreadLocalRandom;

public final class Dice {

    private final int min;
    private final int max;

    public Dice(int min, int max) {
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
    }

    public int roll() {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
