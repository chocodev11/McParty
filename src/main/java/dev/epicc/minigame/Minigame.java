package dev.epicc.minigame;

import java.util.Optional;

public interface Minigame {

    String id();

    /** Shown in reveal titles / chat. Defaults to {@link #id()}. */
    default String displayName() {
        return id();
    }

    /** Optional isolated arena required by each session of this definition. */
    default Optional<MinigameArenaSpec> arenaSpec() {
        return Optional.empty();
    }

    /**
     * Creates a fresh mutable session for one minigame run. Registered instances are definitions
     * only and must never be started directly.
     */
    MinigameSession createSession();
}

