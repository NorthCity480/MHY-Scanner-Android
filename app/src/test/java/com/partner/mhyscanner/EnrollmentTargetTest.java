package com.partner.mhyscanner;

import org.junit.Before;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Synthetic local request-chain fixtures. No owner credentials or live game confirmations. */
public class EnrollmentTargetTest {
    private static final String TICKET="synthetic-community-ticket",STOKEN="v2_synthetic-community-token";
    private static final String PASSPORT="synthetic-game-passport-ticket";
    private static final List<Captured> calls=new ArrayList<>();
    private static String override,pandaUrl;
    private static Runnable afterPanda;
    private static final class Captured {
        final URL url;final Map<String,String> headers=new HashMap<>();final ByteArrayOutputStream body=new ByteArrayOutputStream();
        Captured(URL url){this.url=url;}
        JSONObject json() throws Exception{return new JSONObject(body.toString("UTF-8"));}
    }
    static {
        URL.setURLStreamHandlerFactory(p -> "https".equals(p)?new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url){
                Captured call=new Captured(url);calls.add(call);
                return new HttpURLConnection(url){
                    @Override public void disconnect(){}
                    @Override public boolean usingProxy(){return false;}
                    @Override public void connect(){}
                    @Override public void setRequestProperty(String k,String v){call.headers.put(k,v);}
                    @Override public OutputStream getOutputStream(){return call.body;}
                    @Override public int getResponseCode(){return 200;}
                    @Override public InputStream getInputStream(){
                        String s=override;
                        if(s==null){
                            if(url.getPath().endsWith("createQRLogin"))s="{\"retcode\":0,\"data\":{\"url\":\""+communityUrl()+"\",\"ticket\":\""+TICKET+"\"}}";
                            else if(url.getPath().endsWith("queryQRLoginStatus"))s="{\"retcode\":0,\"data\":{\"status\":\"Created\",\"tokens\":[]}}";
                            else if(url.getPath().endsWith("/scan")){
                                s="{\"retcode\":0,\"data\":{\"passport_qr_url\":\""+pandaUrl+"\"}}";
                                if(afterPanda!=null)afterPanda.run();
                            } else s="{\"retcode\":0,\"data\":null}";
                        }
                        return new ByteArrayInputStream(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                };
            }
        }:null);
    }
    private static String communityUrl(){return "https://user.mihoyo.com/login-platform/mobile.html?tk="+TICKET+"&token_types=1#/login/qr";}
    private ApiClient api(){return new ApiClient("synthetic-device");}
    private JSONObject confirmed() throws Exception {
        return new JSONObject().put("status","Confirmed").put("need_realperson",false)
            .put("user_info",new JSONObject().put("aid","100000001").put("mid","synthetic-mid"))
            .put("tokens",new JSONArray().put(new JSONObject().put("token_type",2).put("token","synthetic-ltoken-never-use"))
                .put(new JSONObject().put("token_type",1).put("token",STOKEN)));
    }
    private AccountStore.Account community() throws Exception{return api().completeEnrollment(confirmed());}
    private QrPayload game(){return QrPayload.parse("https://user.mihoyo.com/qr_code_in_game.html?app_id=8&biz_key=hkrpg_cn&ticket=SYNTHETICGAMETICKET1234567&expire="+(System.currentTimeMillis()/1000+300));}
    @Before public void reset(){calls.clear();override=null;afterPanda=null;pandaUrl="https://user.mihoyo.com/login-platform/mobile.html?tk="+PASSPORT+"&token_types=1#/login/qr";System.clearProperty("mhy.scanner.auth.retry.after.nano");}
    @Test public void modernPcEnrollmentUsesPublishedPassportHost() throws Exception {api().fetchEnrollment();assertEquals("passport-api.mihoyo.com",calls.get(0).url.getHost());assertEquals("/account/ma-cn-passport/app/createQRLogin",calls.get(0).url.getPath());}
    @Test public void modernPcEnrollmentUsesItsCommunityAppId() throws Exception {api().fetchEnrollment();assertEquals("dw9y09jqjpxc",calls.get(0).headers.get("x-rpc-app_id"));assertEquals("synthetic-device",calls.get(0).headers.get("x-rpc-device_id"));assertEquals(0,calls.get(0).json().length());}
    @Test public void modernPcCommunityGrantIsNotConvertedToGameToken() throws Exception {AccountStore.Account a=community();assertEquals(STOKEN,a.token);assertEquals("synthetic-mid",a.mid);assertEquals(AccountStore.CredentialKind.MIYOUSHE_STOKEN,a.kind);assertTrue(calls.isEmpty());}
    @Test public void queryUsesReturnedTicketAndCommunityContract() throws Exception {ApiClient.Enrollment e=api().fetchEnrollment();assertEquals(TICKET,e.ticket);api().queryEnrollment(e.ticket);Captured c=calls.get(1);assertTrue(c.url.getPath().endsWith("queryQRLoginStatus"));assertEquals(TICKET,c.json().getString("ticket"));assertEquals(1,c.json().length());assertEquals("dw9y09jqjpxc",c.headers.get("x-rpc-app_id"));}
    @Test public void readsCommunityStatusNotGameStat() throws Exception {assertEquals("Created",ApiClient.enrollmentStatus(api().queryEnrollment(TICKET)));assertEquals("Confirmed",ApiClient.enrollmentStatus(confirmed()));}
    @Test(expected=IllegalStateException.class) public void rejectsSdkStatusShape() throws Exception {ApiClient.enrollmentStatus(new JSONObject().put("stat","Confirmed"));}
    private void rejectGrant(JSONObject d) throws Exception {try {api().completeEnrollment(d);fail("Must reject invalid grant");}catch(IllegalStateException expected){assertTrue(calls.isEmpty());}}
    @Test public void unconfirmedCannotBecomeStoredCredentials() throws Exception {rejectGrant(confirmed().put("status","Scanned"));}
    @Test public void realPersonVerificationNotBypassed() throws Exception {rejectGrant(confirmed().put("need_realperson",true));}
    @Test public void missingStokenCannotSubstituteLtoken() throws Exception {JSONObject d=confirmed();d.getJSONArray("tokens").remove(1);rejectGrant(d);}
    @Test public void duplicateStokenRejected() throws Exception {JSONObject d=confirmed();d.getJSONArray("tokens").put(new JSONObject().put("token_type",1).put("token",STOKEN));rejectGrant(d);}
    @Test public void tokenInjectionRejected() throws Exception {JSONObject d=confirmed();d.getJSONArray("tokens").getJSONObject(1).put("token",STOKEN+";other=x");rejectGrant(d);}
    @Test public void midInjectionRejected() throws Exception {JSONObject d=confirmed();d.getJSONObject("user_info").put("mid","mid\r\nHeader: x");rejectGrant(d);}
    @Test public void invalidPassportIdRejected() throws Exception {JSONObject d=confirmed();d.getJSONObject("user_info").put("aid","not-a-passport-id");rejectGrant(d);}
    private void badQr(String url) throws Exception {try {ApiClient.Enrollment.parse(new JSONObject().put("url",url).put("ticket",TICKET));fail("Must reject invalid QR");}catch(IllegalStateException expected){}}
    @Test public void gameQrNeverUsedForCommunityLogin() throws Exception {badQr("https://user.mihoyo.com/qr_code_in_game.html?app_id=2&ticket="+TICKET);}
    @Test public void foreignQrHostRejected() throws Exception {badQr(communityUrl().replace("user.mihoyo.com","user.mihoyo.com.evil.test"));}
    @Test public void httpAndUserInfoRejected() throws Exception {badQr(communityUrl().replace("https:","http:"));badQr(communityUrl().replace("user.mihoyo.com","owner@user.mihoyo.com"));}
    @Test public void duplicateTicketAndDifferentServerTicketRejected() throws Exception {badQr(communityUrl().replace("&token_types","&tk=other-ticket&token_types"));badQr(communityUrl().replace(TICKET,"different-ticket"));}
    @Test public void enrollmentTokenTypesMustBeStokenOnly() throws Exception {badQr(communityUrl().replace("token_types=1","token_types=2"));}
    @Test public void oldVaultRecordsRemainGameToken() throws Exception {AccountStore.Account a=AccountStore.Account.fromJson(new JSONObject().put("uid","100000001").put("alias","old").put("token","synthetic-game-token"));assertEquals(AccountStore.CredentialKind.GAME_TOKEN,a.kind);assertEquals("",a.mid);}
    @Test public void communityVaultSerializationPreservesKindAndMid() throws Exception {AccountStore.Account a=AccountStore.Account.fromJson(community().toJson());assertEquals(AccountStore.CredentialKind.MIYOUSHE_STOKEN,a.kind);assertEquals(STOKEN,a.token);assertEquals("synthetic-mid",a.mid);assertFalse(a.display().contains(STOKEN));assertFalse(a.display().contains(a.mid));}
    @Test(expected=IllegalStateException.class) public void unknownStoredCredentialKindRejected() throws Exception {AccountStore.Account.fromJson(community().toJson().put("kind","UNKNOWN"));}
    @Test public void communityStokenCannotEnterLegacyGamePayload() throws Exception {try {api().confirm(game(),community());fail("Must prevent credential substitution");}catch(IllegalStateException expected){assertTrue(calls.isEmpty());}}
    @Test public void pandaToPassportScanThenManualConfirm() throws Exception {
        ApiClient client=api();AccountStore.Account a=community();ApiClient.ScannedLogin s=client.scan(game(),a,()->true);
        assertEquals(2,calls.size());Captured panda=calls.get(0),scan=calls.get(1);
        assertEquals("api-sdk.mihoyo.com",panda.url.getHost());assertEquals("bll8iq97cem8",panda.json().getString("passport_app_id"));assertEquals(8,panda.json().getInt("app_id"));assertFalse(panda.headers.containsKey("Cookie"));
        assertEquals("passport-api.mihoyo.com",scan.url.getHost());assertTrue(scan.url.getPath().endsWith("/scanQRLogin"));assertEquals(PASSPORT,scan.json().getString("ticket"));
        assertEquals("1",scan.json().getJSONArray("token_types").getString(0));assertEquals("stoken="+STOKEN+";mid=synthetic-mid",scan.headers.get("Cookie"));
        assertEquals("bll8iq97cem8",scan.headers.get("x-rpc-app_id"));assertNull(scan.url.getQuery());
        client.confirm(s,a);assertEquals(3,calls.size());assertTrue(calls.get(2).url.getPath().endsWith("/confirmQRLogin"));
        for(Captured c:calls)assertFalse(c.url.getPath().contains("getGameToken"));
    }
    @Test public void legacyImportedGameTokenKeepsOriginalSdkFlow() throws Exception {ApiClient c=api();AccountStore.Account a=new AccountStore.Account("100000001","old","synthetic-game-token");ApiClient.ScannedLogin s=c.scan(game(),a,()->true);assertEquals(1,calls.size());c.confirm(s,a);assertEquals(2,calls.size());assertTrue(calls.get(1).url.getPath().endsWith("/confirm"));assertEquals(a.token,new JSONObject(calls.get(1).json().getJSONObject("payload").getString("raw")).getString("token"));}
    @Test public void stopBeforeScanSendsNothing() throws Exception {try {api().scan(game(),community(),()->false);fail();}catch(IllegalStateException expected){assertTrue(calls.isEmpty());}}
    @Test public void stopBetweenPandaAndPassportSendsNoCredentialRequest() throws Exception {java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean(true);afterPanda=()->running.set(false);try {api().scan(game(),community(),running::get);fail();}catch(IllegalStateException expected){assertEquals(1,calls.size());}}
    @Test public void substitutedPassportHostReceivesNoCredentials() throws Exception {pandaUrl=pandaUrl.replace("user.mihoyo.com","evil.test");try {api().scan(game(),community(),()->true);fail();}catch(IllegalStateException expected){assertEquals(1,calls.size());}}
    @Test public void missingPassportUrlDoesNotFallBackToStokenAsGameToken() throws Exception {pandaUrl="";try {api().scan(game(),community(),()->true);fail();}catch(IllegalStateException expected){assertEquals(1,calls.size());}}
    @Test public void accountSwapAfterScanIsRejected() throws Exception {ApiClient c=api();AccountStore.Account a=community();ApiClient.ScannedLogin s=c.scan(game(),a,()->true);try {c.confirm(s,community());fail();}catch(IllegalStateException expected){assertEquals(2,calls.size());}}
    @Test public void invalidQueryTicketRejectedWithoutNetwork() throws Exception {try {api().queryEnrollment(TICKET+"\r\n");fail();}catch(IllegalArgumentException expected){assertTrue(calls.isEmpty());}}
    private ApiClient.ApiFailure rejection(int code,String text) throws Exception {override=new JSONObject().put("retcode",code).put("message",text).toString();try {api().queryEnrollment(TICKET);fail();return null;}catch(ApiClient.ApiFailure e){return e;}}
    @Test public void rateLimitBlocksRepeatedClicksWithoutNetworking() throws Exception {assertEquals(1001,rejection(1001,"操作过于频繁").code);try {api().queryEnrollment(TICKET);fail();}catch(ApiClient.ApiFailure e){assertTrue(e.getMessage().contains("未发送请求"));}assertEquals(1,calls.size());}
    @Test public void rateLimitCodeClassifiedWithoutMessage() throws Exception {assertTrue(rejection(1001,"").getMessage().contains("频繁"));}
    @Test public void upstreamVerificationSignatureFailuresNeverOfferSwitching() throws Exception {assertFalse(rejection(-1,"decode err response body error: unexpected end of JSON input").canTryCompatibility);assertFalse(rejection(-1,"需要验证").canTryCompatibility);assertFalse(rejection(-1,"signature mismatch").canTryCompatibility);}
    @Test public void expiredLocalDeadlineAllowsExplicitRequest() throws Exception {System.setProperty("mhy.scanner.auth.retry.after.nano",Long.toString(System.nanoTime()-1));api().queryEnrollment(TICKET);assertEquals(1,calls.size());}
    @Test public void missingRetcodeNotInventedAsMinusOne() throws Exception {override="{\"message\":\"unexpected\"}";try {api().queryEnrollment(TICKET);fail();}catch(IllegalStateException e){assertTrue(e.getMessage().contains("响应格式"));assertFalse(e.getMessage().contains("返回 -1"));}}
    @Test public void msgFieldIsPreserved() throws Exception {override="{\"retcode\":-1,\"msg\":\"Unsupported login context\"}";try {api().queryEnrollment(TICKET);fail();}catch(ApiClient.ApiFailure e){assertTrue(e.getMessage().contains("Unsupported login context"));}}
    @Test public void serverCannotEchoTicketOrDevice() throws Exception {String text=rejection(-1,"ticket="+TICKET+" device=synthetic-device").getMessage();assertFalse(text.contains(TICKET));assertFalse(text.contains("synthetic-device"));}
    @Test public void sanitizerRedactsBeforeTruncation() {String secret="v2_"+String.join("",Collections.nCopies(4000,"A"));String text=ApiClient.safeServerExplanation("系统错误 "+secret,secret);assertFalse(text.contains("AAAAAAAA"));assertTrue(text.length()<=181);}
    @Test public void sanitizerRemovesIdentifiersAndContactDetails() {String text=ApiClient.safeServerExplanation("ticket=short123 uid=123456789 email=test@example.test https://example.test/private");for(String s:new String[]{"short123","123456789","test@example.test","https://example.test"})assertFalse(text.contains(s));}
}
