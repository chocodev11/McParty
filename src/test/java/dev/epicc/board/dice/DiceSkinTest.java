package dev.epicc.board.dice;

import dev.epicc.board.Dice;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DiceSkinTest {

    @Test
    void assetIdsMatchShippedFaces() {
        assertEquals(Optional.of("dice_4"), DiceSkin.STANDARD.assetId(4));
        assertEquals(Optional.empty(), DiceSkin.STANDARD.assetId(7));
        assertEquals(Optional.of("dice_gold_4"), DiceSkin.GOLD.assetId(4));
        assertEquals(Optional.empty(), DiceSkin.GOLD.assetId(3));
        assertEquals(Optional.of("dice_chaos_m3"), DiceSkin.CHAOS.assetId(-3));
        assertEquals(Optional.of("dice_chaos_0"), DiceSkin.CHAOS.assetId(0));
        assertEquals(Optional.empty(), DiceSkin.CHAOS.assetId(-4));
    }

    @Test
    void everyRollHasAShippedFace() {
        for (DiceSkin skin : DiceSkin.values()) {
            Dice dice = skin.dice();
            for (int i = 0; i < 500; i++) {
                int face = dice.roll();
                assertTrue(skin.assetId(face).isPresent(), skin + " rolled " + face);
            }
        }
    }
}
