package dev.epicc.board;

import java.util.UUID;

/** Mutable reservation for a permanent board world; clone-backed definitions remain reusable. */
public final class BoardSlotLease {
    private UUID holder;

    public boolean acquire(UUID instanceId, boolean exclusive) {
        if (!exclusive) return true;
        if (holder != null) return false;
        holder = instanceId;
        return true;
    }

    public UUID holder() { return holder; }
    public boolean isFree() { return holder == null; }
    public void release(UUID instanceId) { if (instanceId.equals(holder)) holder = null; }
    public void clear() { holder = null; }
}
