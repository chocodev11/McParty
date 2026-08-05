package dev.epicc.lobby.parkour;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SqliteParkourLeaderboardStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsTheFastestTimeAndCountsAttempts() throws Exception {
        Path database = temporaryDirectory.resolve("parkour.db");
        UUID playerId = UUID.randomUUID();

        try (SqliteParkourLeaderboardStore store = new SqliteParkourLeaderboardStore(
                database, Logger.getLogger("SqliteParkourLeaderboardStoreTest"))) {
            ParkourSubmission first = store.submit("lobby", playerId, "Steve", 42_850).join();
            ParkourSubmission slower = store.submit("lobby", playerId, "Alex", 50_000).join();
            ParkourSubmission faster = store.submit("lobby", playerId, "Alex", 40_250).join();

            assertTrue(first.personalBest());
            assertFalse(slower.personalBest());
            assertTrue(faster.personalBest());

            List<ParkourLeaderboardEntry> entries = store.top("lobby", 10).join();
            assertEquals(1, entries.size());
            assertEquals(playerId, entries.getFirst().playerId());
            assertEquals("Alex", entries.getFirst().playerName());
            assertEquals(40_250, entries.getFirst().bestTimeMs());
            assertEquals(3, entries.getFirst().attempts());
        }
    }

    @Test
    void keepsCoursesSeparate() throws Exception {
        Path database = temporaryDirectory.resolve("parkour.db");
        UUID playerId = UUID.randomUUID();

        try (SqliteParkourLeaderboardStore store = new SqliteParkourLeaderboardStore(
                database, Logger.getLogger("SqliteParkourLeaderboardStoreTest"))) {
            store.submit("lobby", playerId, "Steve", 42_850).join();
            store.submit("winter", playerId, "Steve", 12_000).join();

            assertEquals(1, store.top("lobby", 10).join().size());
            assertEquals(1, store.top("winter", 10).join().size());
            assertEquals(0, store.top("unknown", 10).join().size());
        }
    }
}
