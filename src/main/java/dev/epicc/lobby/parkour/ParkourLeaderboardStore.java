package dev.epicc.lobby.parkour;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ParkourLeaderboardStore extends AutoCloseable {

    CompletableFuture<ParkourSubmission> submit(
            String courseId,
            UUID playerId,
            String playerName,
            long completionTimeMs
    );

    CompletableFuture<List<ParkourLeaderboardEntry>> top(String courseId, int limit);

    @Override
    void close();
}
