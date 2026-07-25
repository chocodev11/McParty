package dev.epicc.party;

/** Pure, guarded lifecycle state used to reject stale asynchronous callbacks. */
public final class PartyLifecycle {

    private PartyState state = PartyState.WAITING;
    private long operationToken;
    private boolean ending;
    private boolean cleanedUp;

    public PartyState state() { return state; }
    public long operationToken() { return operationToken; }

    public long beginStarting() {
        if (state != PartyState.WAITING || ending || cleanedUp) return -1L;
        state = PartyState.STARTING;
        return ++operationToken;
    }

    public boolean isStarting(long token) {
        return state == PartyState.STARTING && operationToken == token && !ending && !cleanedUp;
    }

    public boolean failStart(long token) {
        if (!isStarting(token)) return false;
        state = PartyState.WAITING;
        ++operationToken;
        return true;
    }

    public boolean beginPlaying(long token) {
        if (!isStarting(token)) return false;
        state = PartyState.PLAYING;
        return true;
    }

    public boolean beginEnding() {
        if (ending || cleanedUp) return false;
        ending = true;
        state = PartyState.ENDING;
        ++operationToken;
        return true;
    }

    public boolean beginCleanup() {
        if (cleanedUp) return false;
        cleanedUp = true;
        state = PartyState.CLEANUP;
        ++operationToken;
        return true;
    }
}
