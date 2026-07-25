package dev.epicc.minigame;

import java.util.function.Consumer;

/** Mutable state and cleanup for one party's minigame run. */
public interface MinigameSession {

    void start(MinigameContext context, Consumer<MinigameResult> done);

    void cancel();
}
