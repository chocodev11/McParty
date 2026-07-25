package dev.epicc.party;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PartyLifecycleTest {
    @Test
    void onlyLegalTransitionsSucceedAndStaleStartCannotReviveParty() {
        PartyLifecycle lifecycle = new PartyLifecycle();
        long token = lifecycle.beginStarting();
        assertTrue(token > 0);
        assertFalse(lifecycle.beginPlaying(token + 1));
        assertTrue(lifecycle.beginEnding());
        assertFalse(lifecycle.beginPlaying(token));
        assertTrue(lifecycle.beginCleanup());
        assertFalse(lifecycle.beginEnding());
        assertFalse(lifecycle.beginCleanup());
    }

    @Test
    void failedStartReturnsToWaitingOnlyForItsCurrentToken() {
        PartyLifecycle lifecycle = new PartyLifecycle();
        long token = lifecycle.beginStarting();
        assertTrue(lifecycle.failStart(token));
        assertEquals(PartyState.WAITING, lifecycle.state());
        assertFalse(lifecycle.failStart(token));
    }
}
