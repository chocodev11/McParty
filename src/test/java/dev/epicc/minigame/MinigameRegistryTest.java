package dev.epicc.minigame;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class MinigameRegistryTest {

    private static DummyMinigame dummy(String id) {
        return new DummyMinigame(id, id, 5, List.of(10));
    }

    @Test
    void realGamesAreAlwaysPickedOverDummies() {
        MinigameRegistry registry = new MinigameRegistry(dummy("first"));
        registry.register(dummy("second"));
        registry.register(new Minigame() {
            @Override public String id() { return "real"; }
            @Override public MinigameSession createSession() {
                return new MinigameSession() {
                    @Override public void start(MinigameContext context, Consumer<MinigameResult> done) {}
                    @Override public void cancel() {}
                };
            }
        });

        for (int i = 0; i < 50; i++) {
            assertEquals("real", registry.pickRandom().id());
        }
        // Dummies still feed the reveal roulette
        assertEquals(3, registry.displayNames().size());
    }

    @Test
    void dummiesAreTheFallbackWhenNoRealGameIsRegistered() {
        MinigameRegistry registry = new MinigameRegistry(dummy("first"));
        registry.register(dummy("second"));

        assertInstanceOf(DummyMinigame.class, registry.pickRandom());
    }
}
