package dev.epicc.minigame;

import java.util.function.Consumer;

/** Controller-owned arena enter/exit operations for one runner invocation. */
public record ArenaTransitions(Consumer<MinigameArena> enter, Runnable exit) {
    public static ArenaTransitions none() { return new ArenaTransitions(arena -> {}, () -> {}); }
}
