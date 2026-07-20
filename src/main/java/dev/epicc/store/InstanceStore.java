package dev.epicc.store;

import dev.epicc.party.PartyInstance;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface InstanceStore {

    void put(PartyInstance instance);

    Optional<PartyInstance> get(UUID id);

    void remove(UUID id);

    Collection<PartyInstance> all();

    int size();

    void clear();
}
