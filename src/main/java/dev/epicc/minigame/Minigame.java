package dev.epicc.minigame;

import java.util.Optional;
import java.util.function.Consumer;

public interface Minigame {

    String id();

    /** Shown in reveal titles / chat. Defaults to {@link #id()}. */
    default String displayName() {
        return id();
    }

    /** ASP Slime world template required by this minigame, if any. */
    default Optional<String> slimeTemplate() {
        return Optional.empty();
    }

    void start(MinigameContext context, Consumer<MinigameResult> done);

    void cancel();
}

