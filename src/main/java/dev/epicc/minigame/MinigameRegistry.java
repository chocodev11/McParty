package dev.epicc.minigame;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class MinigameRegistry {

    private final Map<String, Minigame> byId = new LinkedHashMap<>();
    private final Minigame fallback;

    public MinigameRegistry(Minigame fallback) {
        this.fallback = fallback;
        register(fallback);
    }

    public void register(Minigame minigame) {
        byId.put(minigame.id().toLowerCase(), minigame);
    }

    public void unregister(String id) {
        if (id != null) byId.remove(id.toLowerCase());
    }

    public Optional<Minigame> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.toLowerCase()));
    }

    public Collection<Minigame> all() {
        return List.copyOf(byId.values());
    }

    public List<String> ids() {
        return new ArrayList<>(byId.keySet());
    }

    public List<String> displayNames() {
        List<String> names = new ArrayList<>(byId.size());
        for (Minigame m : byId.values()) {
            names.add(m.displayName());
        }
        return names;
    }

    public Minigame pickRandom() {
        if (byId.isEmpty()) {
            return fallback;
        }
        List<Minigame> list = new ArrayList<>(byId.values());
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }
}
