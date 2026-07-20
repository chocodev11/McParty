package dev.epicc.player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerSessionService {

    private final Map<UUID, UUID> playerToInstance = new ConcurrentHashMap<>();

    public void bind(UUID playerId, UUID instanceId) {
        playerToInstance.put(playerId, instanceId);
    }

    public void unbind(UUID playerId) {
        playerToInstance.remove(playerId);
    }

    public Optional<UUID> instanceOf(UUID playerId) {
        return Optional.ofNullable(playerToInstance.get(playerId));
    }

    public boolean isInParty(UUID playerId) {
        return playerToInstance.containsKey(playerId);
    }

    public void clearInstance(UUID instanceId) {
        playerToInstance.entrySet().removeIf(e -> e.getValue().equals(instanceId));
    }

    public void clearAll() {
        playerToInstance.clear();
    }
}
