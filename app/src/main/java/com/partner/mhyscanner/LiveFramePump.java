package com.partner.mhyscanner;

/** Reserve before copying a newly rendered frame; never queue stale video bitmaps. */
final class LiveFramePump {
    private final FrameGate gate;
    LiveFramePump(FrameGate gate) { this.gate=gate; }
    boolean tryCapture(long frameTimeMs,long intervalMs) {
        return gate.enter(frameTimeMs,intervalMs);
    }
}
