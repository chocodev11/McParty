package dev.epicc.minigame;

import org.junit.jupiter.api.Test;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class MinigameSessionFactoryTest {
    @Test
    void eachDefinitionCreatesAnIndependentSession() {
        Minigame definition = new Minigame() {
            @Override public String id() { return "test"; }
            @Override public MinigameSession createSession() {
                return new MinigameSession() {
                    @Override public void start(MinigameContext context, Consumer<MinigameResult> done) {}
                    @Override public void cancel() {}
                };
            }
        };
        assertNotSame(definition.createSession(), definition.createSession());
    }
}
