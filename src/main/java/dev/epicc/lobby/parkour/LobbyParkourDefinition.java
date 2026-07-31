package dev.epicc.lobby.parkour;

import java.util.List;

public record LobbyParkourDefinition(
        LobbyParkourPoint start,
        List<LobbyParkourPoint> checkpoints,
        LobbyParkourPoint goal
) {
    public LobbyParkourDefinition {
        checkpoints = List.copyOf(checkpoints);
    }

    public boolean isReady() {
        return start != null && goal != null;
    }
}
