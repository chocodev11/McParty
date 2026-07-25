package dev.epicc.board;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class BoardSlotLeaseTest {
    @Test
    void cloneModeIsReusableWhileFallbackModeIsExclusive() {
        BoardSlotLease lease = new BoardSlotLease();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(lease.acquire(first, false));
        assertTrue(lease.acquire(second, false));
        assertTrue(lease.acquire(first, true));
        assertFalse(lease.acquire(second, true));
        lease.release(first);
        assertTrue(lease.acquire(second, true));
    }
}
