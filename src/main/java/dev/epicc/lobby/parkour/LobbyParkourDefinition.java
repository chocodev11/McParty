package dev.epicc.lobby.parkour;

import java.util.List;

public record LobbyParkourDefinition(
        String fallbackWorld,
        LobbyParkourPoint start,
        List<LobbyParkourPoint> checkpoints,
        LobbyParkourPoint goal,
        LobbyParkourPoint leaderboard
) {
    public LobbyParkourDefinition {
        checkpoints = List.copyOf(checkpoints);
    }

    public boolean isReady() {
        return start != null && goal != null;
    }
}
