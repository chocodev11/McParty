package dev.epicc.minigame;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class MinigameResult {

    /** placement 1..n */
    private final Map<UUID, Integer> placements = new LinkedHashMap<>();
    private final Map<UUID, Integer> coinRewards = new LinkedHashMap<>();

    public void setPlacement(UUID playerId, int place) {
        placements.put(playerId, place);
    }

    public void setCoins(UUID playerId, int coins) {
        coinRewards.put(playerId, coins);
    }

    public Map<UUID, Integer> placements() {
        return placements;
    }

    public Map<UUID, Integer> coinRewards() {
        return coinRewards;
    }
}
