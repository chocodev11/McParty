package dev.epicc.party;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class PartyHostSelectorTest {
    @Test
    void preservesOldestRemainingMember() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
        members.put(first, "first");
        members.put(second, "second");
        assertEquals(first, PartyHostSelector.firstRemaining(members));
    }
}
