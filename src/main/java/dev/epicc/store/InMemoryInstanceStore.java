package dev.epicc.store;

import dev.epicc.party.PartyInstance;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryInstanceStore implements InstanceStore {

    private final Map<UUID, PartyInstance> instances = new ConcurrentHashMap<>();

    @Override
    public void put(PartyInstance instance) {
        instances.put(instance.id(), instance);
    }

    @Override
    public Optional<PartyInstance> get(UUID id) {
        return Optional.ofNullable(instances.get(id));
    }

    @Override
    public void remove(UUID id) {
        instances.remove(id);
    }

    @Override
    public Collection<PartyInstance> all() {
        return instances.values();
    }

    @Override
    public int size() {
        return instances.size();
    }

    @Override
    public void clear() {
        instances.clear();
    }
}
