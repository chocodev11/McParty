package dev.epicc.minigame;

import java.util.function.Consumer;

public interface Minigame {

    String id();

    /** Shown in reveal titles / chat. Defaults to {@link #id()}. */
    default String displayName() {
        return id();
    }

    void start(MinigameContext context, Consumer<MinigameResult> done);

    void cancel();
}
