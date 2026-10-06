package com.partner.mhyscanner;
import org.junit.Test;
import static org.junit.Assert.*;
public class LiveResolverTest {
    @Test public void directHttpsRequiresNoApi() throws Exception { assertEquals("https://video.example.test/live.flv?token=test",LiveResolver.resolve(" https://video.example.test/live.flv?token=test ",2).url); }
    @Test(expected=IllegalArgumentException.class) public void deniesCleartextDirect() throws Exception { LiveResolver.resolve("http://example.test/live.flv",2); }
    @Test(expected=IllegalArgumentException.class) public void deniesLocalFileDirect() throws Exception { LiveResolver.resolve("file:///sdcard/test.mp4",2); }
    @Test(expected=IllegalArgumentException.class) public void deniesUserInfoDirect() throws Exception { LiveResolver.resolve("https://secret@video.test/live.flv",2); }
    @Test(expected=IllegalArgumentException.class) public void roomHostMustMatch() throws Exception { LiveResolver.resolve("https://live.bilibili.com.evil.test/1",0); }
    @Test(expected=IllegalArgumentException.class) public void roomMustBeNumeric() throws Exception { LiveResolver.resolve("1&bad=2",0); }
    @Test(expected=IllegalArgumentException.class) public void deniesWrongPlatform() throws Exception { LiveResolver.resolve("https://live.douyin.com/1",0); }
}
