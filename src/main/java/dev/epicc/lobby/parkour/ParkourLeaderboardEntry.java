package dev.epicc.lobby.parkour;

import java.time.Instant;
import java.util.UUID;

public record ParkourLeaderboardEntry(
        String courseId,
        UUID playerId,
        String playerName,
        long bestTimeMs,
        int attempts,
        Instant achievedAt
) {
}
