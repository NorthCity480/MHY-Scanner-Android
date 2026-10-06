package com.partner.mhyscanner;

import org.junit.Test;
import static org.junit.Assert.*;

/** Frame timestamps are supplied by the decoded video callback, not a polling clock. */
public class LiveFramePumpTest {
    @Test public void immediateModeAcceptsTheFirstNewFrameWithoutWaitingForTheNextTimerTick() {
        FrameGate gate=new FrameGate(); LiveFramePump pump=new LiveFramePump(gate);
        // A new decoded frame arrives 1ms after the previous periodic 120ms tick.
        assertTrue(pump.tryCapture(1,0));gate.leave();
        assertTrue(pump.tryCapture(2,0));gate.leave();
    }
    @Test public void neverCopiesASecondBitmapWhileDecoderIsBusy() {
        FrameGate gate=new FrameGate();LiveFramePump pump=new LiveFramePump(gate);
        assertTrue(pump.tryCapture(1000,0));
        assertFalse(pump.tryCapture(1001,0));
        gate.leave();assertTrue(pump.tryCapture(1002,0));gate.leave();
    }
    @Test public void savedBalancedIntervalStillThrottlesDecodedFrames() {
        FrameGate gate=new FrameGate();LiveFramePump pump=new LiveFramePump(gate);
        assertTrue(pump.tryCapture(1000,120));gate.leave();
        assertFalse(pump.tryCapture(1001,120));
        assertTrue(pump.tryCapture(1120,120));gate.leave();
    }
}
