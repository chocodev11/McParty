package dev.epicc.party;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Preserves insertion order when selecting a replacement waiting-room host. */
public final class PartyHostSelector {
    private PartyHostSelector() {}

    public static UUID firstRemaining(LinkedHashMap<UUID, ?> members) {
        return members.isEmpty() ? null : members.keySet().iterator().next();
    }
}
