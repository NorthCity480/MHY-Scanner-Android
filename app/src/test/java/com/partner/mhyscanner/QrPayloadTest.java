package com.partner.mhyscanner;
import org.junit.Test;
import static org.junit.Assert.*;

public class QrPayloadTest {
    private final String base="https://user.mihoyo.com/qr_code_in_game.html?app_id=8&app_name=%E6%98%9F%E7%A9%B9%E9%93%81%E9%81%93&biz_key=hkrpg_cn&ticket=ABCDEFGHIJKLMNOPQRSTUVWX&expire=2000000000";
    @Test public void starRailFields() { QrPayload p=QrPayload.parse(base); assertNotNull(p); assertEquals(QrPayload.Game.STAR_RAIL,p.game); assertEquals("ABCDEFGHIJKLMNOPQRSTUVWX",p.ticket); assertEquals(2000000000,p.expires); }
    @Test public void mapsFourGames() { for(QrPayload.Game game:QrPayload.Game.values()) { assertEquals(game,QrPayload.parse(base.replace("app_id=8","app_id="+game.appId).replace("hkrpg_cn",game.biz)).game); } }
    @Test public void queryOrderDoesNotMatter() { assertNotNull(QrPayload.parse("https://user.mihoyo.com/qr_code_in_game.html?ticket=ABCDEFGHIJKLMNOPQRSTUVWX&biz_key=hk4e_cn&app_id=4")); }
    @Test public void expiryIsEnforcedAtBoundary() { QrPayload p=QrPayload.parse(base); assertFalse(p.isExpired(1999999999)); assertTrue(p.isExpired(2000000000)); assertTrue(p.isExpired(2000000001)); }
    @Test public void noExpirySupported() { assertFalse(QrPayload.parse(base.replace("&expire=2000000000","")).isExpired(Long.MAX_VALUE)); }
    @Test public void rejectsPhishingHosts() { assertNull(QrPayload.parse(base.replace("user.mihoyo.com","user.mihoyo.com.evil.test"))); assertNull(QrPayload.parse(base.replace("user.mihoyo.com","evil.test"))); }
    @Test public void rejectsUserInfo() { assertNull(QrPayload.parse(base.replace("user.mihoyo.com","user.mihoyo.com@evil.test"))); assertNull(QrPayload.parse(base.replace("user.mihoyo.com","evil@user.mihoyo.com"))); }
    @Test public void rejectsPlainHttpAndPorts() { assertNull(QrPayload.parse(base.replace("https:","http:"))); assertNull(QrPayload.parse(base.replace("user.mihoyo.com","user.mihoyo.com:8443"))); assertNotNull(QrPayload.parse(base.replace("user.mihoyo.com","user.mihoyo.com:443"))); }
    @Test public void rejectsPathAndFragment() { assertNull(QrPayload.parse(base.replace("qr_code_in_game.html","login.html"))); assertNull(QrPayload.parse(base+"#spoof")); }
    @Test public void rejectsDuplicateFields() { assertNull(QrPayload.parse(base+"&ticket=ZYXWVUTSRQPONMLKJIHGFEDC")); assertNull(QrPayload.parse(base+"&app_id=8")); }
    @Test public void rejectsAppBizMismatch() { assertNull(QrPayload.parse(base.replace("app_id=8","app_id=4"))); assertNull(QrPayload.parse(base.replace("app_id=8","app_id=10000"))); }
    @Test public void rejectsTicketsAndMalformedInput() { assertNull(QrPayload.parse(null)); assertNull(QrPayload.parse("19267272")); assertNull(QrPayload.parse(base.replace("ABCDEFGHIJKLMNOPQRSTUVWX","short"))); assertNull(QrPayload.parse(base.replace("ABCDEFGHIJKLMNOPQRSTUVWX","ABCDEFGHIJKLMNOPQRSTUV%2FX"))); assertNull(QrPayload.parse(base+"&broken=%ZZ")); }
    @Test public void rejectsInvalidExpiry() { assertNull(QrPayload.parse(base.replace("2000000000","-1"))); assertNull(QrPayload.parse(base.replace("2000000000","tomorrow"))); }
    @Test public void endpointsAreFixedNotQrProvided() { assertEquals("https://api-sdk.mihoyo.com/hkrpg_cn/combo/panda/qrcode/confirm",QrPayload.parse(base+"&endpoint=https%3A%2F%2Fevil.test").game.endpoint("confirm")); }
}
