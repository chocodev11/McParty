package dev.epicc.board;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BoardTurnControllerTest {

    @Test
    void nextIndexStaysOnThePath() {
        assertEquals(7, BoardTurnController.nextIndex(4, 3, 20));
        assertEquals(20, BoardTurnController.nextIndex(18, 6, 20));
        assertEquals(3, BoardTurnController.nextIndex(5, -2, 20));
        assertEquals(0, BoardTurnController.nextIndex(2, -3, 20));
        assertEquals(5, BoardTurnController.nextIndex(5, 0, 20));
    }
}
