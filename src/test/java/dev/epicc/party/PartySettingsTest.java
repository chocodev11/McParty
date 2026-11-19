package dev.epicc.party;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PartySettingsTest {

    @Test
    void zeroMaxRoundsNeverEndsTheGame() {
        PartySettings settings = new PartySettings(1, 8, 10, 1, 6, 0);

        assertFalse(settings.roundLimitReached(100));
    }

    @Test
    void roundLimitIsReachedAfterThatManyMinigames() {
        PartySettings settings = new PartySettings(1, 8, 10, 1, 6, 3);

        assertFalse(settings.roundLimitReached(2));
        assertTrue(settings.roundLimitReached(3));
    }
}
