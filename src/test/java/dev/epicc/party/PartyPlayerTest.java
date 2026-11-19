package dev.epicc.party;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PartyPlayerTest {

    @Test
    void spendCoinsNeedsEnoughCoins() {
        PartyPlayer player = new PartyPlayer(UUID.randomUUID(), "player", 30);

        assertFalse(player.spendCoins(31));
        assertEquals(30, player.coins());
        assertFalse(player.spendCoins(-1));
        assertTrue(player.spendCoins(30));
        assertEquals(0, player.coins());
    }
}
