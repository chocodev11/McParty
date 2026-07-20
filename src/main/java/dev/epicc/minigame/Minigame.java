package dev.epicc.minigame;

import java.util.function.Consumer;

public interface Minigame {

    String id();

    void start(MinigameContext context, Consumer<MinigameResult> done);

    void cancel();
}
