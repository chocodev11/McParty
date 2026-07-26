package dev.epicc.minigame;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EliminationTrackerTest {

    private static final List<Integer> REWARDS = List.of(10, 7, 5, 3);

    @Test
    void survivorPlacesFirstAndEliminationOrderReverses() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        EliminationTracker tracker = new EliminationTracker(List.of(a, b, c, d), REWARDS);

        tracker.eliminate(b); // out first → last place
        tracker.eliminate(c);
        tracker.eliminate(d); // out last → 2nd place

        Map<UUID, Integer> places = tracker.result().placements();
        assertEquals(1, places.get(a));
        assertEquals(2, places.get(d));
        assertEquals(3, places.get(c));
        assertEquals(4, places.get(b));

        Map<UUID, Integer> coins = tracker.result().coinRewards();
        assertEquals(10, coins.get(a));
        assertEquals(3, coins.get(b));
    }

    @Test
    void multipleSurvivorsKeepPartyOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        EliminationTracker tracker = new EliminationTracker(List.of(a, b, c), REWARDS);

        tracker.eliminate(b);

        Map<UUID, Integer> places = tracker.result().placements();
        assertEquals(1, places.get(a));
        assertEquals(2, places.get(c));
        assertEquals(3, places.get(b));
    }

    @Test
    void eliminateIsOneShot() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        EliminationTracker tracker = new EliminationTracker(List.of(a, b), REWARDS);

        assertTrue(tracker.eliminate(a));
        assertFalse(tracker.eliminate(a), "already eliminated");
        assertFalse(tracker.eliminate(UUID.randomUUID()), "never in the match");
        assertEquals(1, tracker.aliveCount());
    }

    @Test
    void placementsBeyondTheRewardTableStillPayOne() {
        List<UUID> players = List.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID()
        );
        EliminationTracker tracker = new EliminationTracker(players, REWARDS);
        for (int i = 1; i < players.size(); i++) {
            tracker.eliminate(players.get(i));
        }

        // 5 players, 4 reward entries — last place falls back to 1 coin
        assertEquals(1, tracker.result().coinRewards().get(players.get(1)));
    }
}
