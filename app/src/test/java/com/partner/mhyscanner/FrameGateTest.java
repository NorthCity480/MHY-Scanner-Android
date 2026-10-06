package com.partner.mhyscanner;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public class FrameGateTest {
    @Test public void noQueuedFrames() { FrameGate gate=new FrameGate(); assertTrue(gate.enter(1000,120)); assertFalse(gate.enter(1300,120)); gate.leave(); assertTrue(gate.enter(1300,120)); }
    @Test public void throttlesAtInterval() { FrameGate gate=new FrameGate(); assertTrue(gate.enter(1000,120)); gate.leave(); assertFalse(gate.enter(1119,120)); assertTrue(gate.enter(1120,120)); }
    @Test public void concurrentReadersOnlyOneWinner() throws Exception {
        FrameGate gate=new FrameGate(); CountDownLatch begin=new CountDownLatch(1),done=new CountDownLatch(20); AtomicInteger wins=new AtomicInteger();
        for(int i=0;i<20;i++) new Thread(() -> { try { begin.await(); if(gate.enter(1000,120)) wins.incrementAndGet(); } catch(Exception ignored) {} finally {done.countDown();} }).start();
        begin.countDown(); assertTrue(done.await(3,TimeUnit.SECONDS)); assertEquals(1,wins.get());
    }
}
