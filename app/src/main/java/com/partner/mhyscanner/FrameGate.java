package com.partner.mhyscanner;
import java.util.concurrent.atomic.AtomicBoolean;
/** At most one frame in flight: discard stale frames instead of building a queue. */
public final class FrameGate {
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile long last;
    public boolean enter(long now, long interval) {
        if (now - last < interval || !busy.compareAndSet(false, true)) return false;
        last = now;
        return true;
    }
    public void leave() { busy.set(false); }
}
