package com.partner.mhyscanner;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Community login / Panda-to-Passport adapted from MHY_Scanner2 d07fc801 (GPL-3.0).
 * Credential destinations are fixed here, never taken from a QR URL.
 */
public final class ApiClient {
    public static final String ENROLL_BASE="https://passport-api.mihoyo.com/account/ma-cn-passport/app/";
    private final String device;
    public ApiClient(String device){this.device=device;}
    public static final class Enrollment {
        public final String url,ticket;
        private Enrollment(String url,String ticket){this.url=url;this.ticket=ticket;}
        static Enrollment parse(JSONObject data) throws Exception {
            String url=data.getString("url"),ticket=data.getString("ticket");
            PassportTicket parsed=PassportTicket.parse(url);
            if(!ticket.equals(parsed.ticket) || !"1".equals(parsed.tokenTypes)) throw new IllegalStateException("米游社登录二维码格式不匹配");
            return new Enrollment(url,ticket);
        }
    }
    static final class PassportTicket {
        final String ticket,tokenTypes;
        PassportTicket(String ticket,String types){this.ticket=ticket;this.tokenTypes=types;}
        static PassportTicket parse(String url) throws Exception {
            try {
                URI u=new URI(url);
                if(!"https".equals(u.getScheme()) || !"user.mihoyo.com".equals(u.getHost()) || !"/login-platform/mobile.html".equals(u.getRawPath())
                    || u.getUserInfo()!=null || (u.getPort()!=-1 && u.getPort()!=443) || !"/login/qr".equals(u.getFragment()) || u.getRawQuery()==null)
                    throw new IllegalArgumentException();
                Map<String,String> q=new LinkedHashMap<>();
                for(String pair:u.getRawQuery().split("&")) {
                    String[] kv=pair.split("=",2);if(kv.length!=2) throw new IllegalArgumentException();
                    if(q.put(URLDecoder.decode(kv[0],"UTF-8"),URLDecoder.decode(kv[1],"UTF-8"))!=null) throw new IllegalArgumentException();
                }
                String ticket=q.get("tk"),types=q.get("token_types");
                if(ticket==null || !ticket.matches("[A-Za-z0-9_-]{8,128}") || types==null || !types.matches("[0-9]{1,2}(,[0-9]{1,2}){0,7}")) throw new IllegalArgumentException();
                if(q.containsKey("expire") && Long.parseLong(q.get("expire"))<=System.currentTimeMillis()/1000) throw new IllegalArgumentException();
                return new PassportTicket(ticket,types);
            } catch(Exception ignored){throw new IllegalStateException("官方米游社二维码格式不匹配或已过期");}
        }
    }
    public static final class ScannedLogin {
        public final QrPayload qr;
        final PassportTicket passport;
        final AccountStore.Account owner;
        private ScannedLogin(QrPayload qr,PassportTicket passport,AccountStore.Account owner){this.qr=qr;this.passport=passport;this.owner=owner;}
    }
    public static final class ApiFailure extends IllegalStateException {
        public final int code;
        public final boolean canTryCompatibility=false;
        final String detail;
        ApiFailure(int code,String detail){super("米哈游接口返回 "+code+"（"+detail+"）");this.code=code;this.detail=detail;}
    }
    static JSONObject request(String url,JSONObject body,String referer) throws Exception {return request(url,body,referer,Collections.emptyMap());}
    static JSONObject request(String url,JSONObject body,String referer,Map<String,String> headers) throws Exception {
        URL target=new URL(url);if(!"https".equals(target.getProtocol())) throw new IllegalArgumentException("只允许 HTTPS");
        HttpURLConnection c=(HttpURLConnection)target.openConnection();
        try {
            c.setConnectTimeout(5000);c.setReadTimeout(6000);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36");
            if(referer!=null)c.setRequestProperty("Referer",referer);
            for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
            if(body!=null){
                c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                byte[] bytes=body.toString().getBytes("UTF-8");c.setFixedLengthStreamingMode(bytes.length);
                try(java.io.OutputStream out=c.getOutputStream()){out.write(bytes);}
            }
            int code=c.getResponseCode();if(code!=200)throw new IllegalStateException("HTTP "+code);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>2*1024*1024)throw new IllegalStateException("响应过大");}
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {c.disconnect();}
    }
    private JSONObject body(int appId,String ticket) throws Exception {JSONObject b=new JSONObject().put("app_id",appId).put("device",device);if(ticket!=null)b.put("ticket",ticket);return b;}
    private Map<String,String> headers(String appId){Map<String,String> h=new LinkedHashMap<>();h.put("x-rpc-app_id",appId);h.put("x-rpc-device_id",device);return h;}
    private static void cooldown(){
        long deadline=0;try{deadline=Long.parseLong(System.getProperty("mhy.scanner.auth.retry.after.nano","0"));}catch(NumberFormatException ignored){}
        long remaining=deadline-System.nanoTime();
        if(remaining>0)throw new ApiFailure(1001,"官方授权冷却中，约 "+((remaining+999999999L)/1000000000L)+" 秒后允许手动重试；本次未发送请求。此等待时间不代表官方限流已解除");
    }
    private JSONObject call(String url,JSONObject body,Map<String,String> headers,String... secrets) throws Exception {
        cooldown();JSONObject response=checked(request(url,body,null,headers),secrets);
        JSONObject data=response.optJSONObject("data");return data==null?new JSONObject():data;
    }
    private static JSONObject checked(JSONObject response,String... secrets) throws Exception {
        String value=response.optString("retcode","");if(!value.matches("-?[0-9]{1,9}"))throw new IllegalStateException("官方响应格式异常：缺少有效 retcode");
        int code=Integer.parseInt(value);
        if(code!=0){
            String raw=response.optString("message","");if(raw.trim().isEmpty())raw=response.optString("msg","");
            String reason=failureReason(code,raw),explanation=safeServerExplanation(raw,secrets);
            if(code==1001 || reason.contains("频繁"))System.setProperty("mhy.scanner.auth.retry.after.nano",Long.toString(System.nanoTime()+120000000000L));
            throw new ApiFailure(code,reason+(explanation.isEmpty()?"":"；服务器说明："+explanation));
        }
        return response;
    }
    static String safeServerExplanation(String message,String... secrets){
        String safe=message;
        for(String secret:secrets)if(secret!=null&&!secret.isEmpty())safe=safe.replace(secret,"<REDACTED>");
        safe=safe.replaceAll("(?i)https?://\\S+","<REDACTED>")
            .replaceAll("(?i)\\b(stoken|game_token|cookie_token|token|ticket|mid|uid|aid|device(?:_id)?)[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\s,;\\\"'}]+","$1=<REDACTED>")
            .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","<REDACTED>").replaceAll("[A-Za-z0-9_./+%=-]{16,}","<REDACTED>")
            .replaceAll("[0-9]{6,}","<REDACTED>").replaceAll("[\\p{Cntrl}]"," ").trim();
        return safe.length()>180?safe.substring(0,180)+"…":safe;
    }
    private static String failureReason(int code,String message){
        String m=message.toLowerCase(Locale.ROOT);
        if(code==-3501)return "米游社二维码已过期";if(code==-3505)return "米游社登录已取消";
        if(code==1001)return "官方授权请求过于频繁，需要冷却";
        if(m.contains("decode err")||m.contains("unexpected end of json input"))return "官方授权服务上游响应异常，请等待后重试";
        if(m.contains("验证")||m.contains("captcha")||m.contains("risk"))return "需要在官方应用完成验证";
        if(m.contains("频繁")||m.contains("too many")||m.contains("rate limit"))return "请求过于频繁，请稍后手动重试";
        if(m.contains("签名")||m.contains("signature"))return "请求签名或应用上下文不匹配";
        if(m.contains("参数")||m.contains("parameter")||m.contains("param"))return "请求参数或授权上下文异常";
        if(m.contains("登录")||m.contains("token")||m.contains("login")||m.contains("credential"))return "登录凭据无效或授权范围不匹配";
        if(m.contains("繁忙")||m.contains("内部错误")||m.contains("系统错误")||m.contains("系统出错")||m.contains("internal")||m.contains("busy"))return "官方服务暂时不可用";
        return "官方接口拒绝请求，原因未分类";
    }
    public Enrollment fetchEnrollment() throws Exception {return Enrollment.parse(call(ENROLL_BASE+"createQRLogin",new JSONObject(),headers("dw9y09jqjpxc"),device));}
    public JSONObject queryEnrollment(String ticket) throws Exception {
        if(ticket==null||!ticket.matches("[A-Za-z0-9_-]{8,128}"))throw new IllegalArgumentException("米游社登录票据格式不正确");
        return call(ENROLL_BASE+"queryQRLoginStatus",new JSONObject().put("ticket",ticket),headers("dw9y09jqjpxc"),ticket,device);
    }
    static String enrollmentStatus(JSONObject data){String s=data.optString("status");if(!"Created".equals(s)&&!"Scanned".equals(s)&&!"Confirmed".equals(s))throw new IllegalStateException("米游社登录状态不受支持");return s;}
    public AccountStore.Account completeEnrollment(JSONObject data) throws Exception {
        if(!"Confirmed".equals(enrollmentStatus(data)))throw new IllegalStateException("请先在米游社确认登录");
        if(data.optBoolean("need_realperson",false))throw new IllegalStateException("请先在官方应用完成实名验证");
        JSONObject user=data.getJSONObject("user_info");String uid=user.getString("aid"),mid=user.getString("mid"),stoken=null;
        if(!uid.matches("[0-9]{1,20}")||!mid.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalStateException("米游社账号信息不完整");
        JSONArray tokens=data.getJSONArray("tokens");
        for(int i=0;i<tokens.length();i++){JSONObject t=tokens.getJSONObject(i);if(t.optInt("token_type",-1)==1){if(stoken!=null)throw new IllegalStateException("米游社授权令牌不唯一");stoken=t.getString("token");}}
        if(stoken==null||!stoken.matches("[A-Za-z0-9._~+/=-]{8,4096}"))throw new IllegalStateException("米游社未返回有效 SToken");
        // Community enrollment does NOT exchange SToken into game_token. Keep its type explicit.
        return new AccountStore.Account(uid,"",stoken,mid,AccountStore.CredentialKind.MIYOUSHE_STOKEN);
    }
    private static void allowed(BooleanSupplier current){if(!current.getAsBoolean())throw new IllegalStateException("扫码会话已结束");}
    public ScannedLogin scan(QrPayload qr,AccountStore.Account a,BooleanSupplier current) throws Exception {
        allowed(current);if(qr.isExpired(System.currentTimeMillis()/1000))throw new IllegalStateException("二维码已过期");
        JSONObject b=body(qr.game.appId,qr.ticket);
        if(a.kind==AccountStore.CredentialKind.GAME_TOKEN){call(qr.game.endpoint("scan"),b,Collections.emptyMap(),qr.ticket,device);return new ScannedLogin(qr,null,a);}
        if(a.kind!=AccountStore.CredentialKind.MIYOUSHE_STOKEN)throw new IllegalStateException("账号凭据类型不受支持");
        b.put("passport_app_id","bll8iq97cem8").put("ts",System.currentTimeMillis()/1000);
        JSONObject data=call(qr.game.endpoint("scan"),b,headers("bll8iq97cem8"),qr.ticket,device);
        PassportTicket ticket=PassportTicket.parse(data.optString("passport_qr_url"));
        allowed(current);passport(ticket,a,false);return new ScannedLogin(qr,ticket,a);
    }
    public void confirm(ScannedLogin login,AccountStore.Account a) throws Exception {
        if(a!=login.owner)throw new IllegalStateException("账号与扫码会话不匹配");
        if(login.qr.isExpired(System.currentTimeMillis()/1000))throw new IllegalStateException("二维码已过期");
        if(login.passport!=null){
            if(a.kind!=AccountStore.CredentialKind.MIYOUSHE_STOKEN)throw new IllegalStateException("账号授权类型与会话不匹配");
            passport(login.passport,a,true);return;
        }
        confirm(login.qr,a);
    }
    /** Legacy imported game_token only. A community SToken can never enter this payload. */
    public void confirm(QrPayload qr,AccountStore.Account a) throws Exception {
        if(a.kind!=AccountStore.CredentialKind.GAME_TOKEN)throw new IllegalStateException("不能将米游社 SToken 作为游戏令牌提交");
        JSONObject raw=new JSONObject().put("uid",a.uid).put("token",a.token);
        call(qr.game.endpoint("confirm"),body(qr.game.appId,qr.ticket).put("payload",new JSONObject().put("proto","Account").put("raw",raw.toString())),Collections.emptyMap(),qr.ticket,device,a.uid,a.token);
    }
    private void passport(PassportTicket p,AccountStore.Account a,boolean confirm) throws Exception {
        if(a.kind!=AccountStore.CredentialKind.MIYOUSHE_STOKEN||!a.token.matches("[A-Za-z0-9._~+/=-]{8,4096}")||!a.mid.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalStateException("米游社账号凭据格式异常");
        Map<String,String> h=headers("bll8iq97cem8");h.put("Cookie","stoken="+a.token+";mid="+a.mid);
        call(ENROLL_BASE+(confirm?"confirmQRLogin":"scanQRLogin"),new JSONObject().put("ticket",p.ticket).put("token_types",new JSONArray().put(p.tokenTypes)),h,p.ticket,a.token,a.mid,a.uid,device);
    }
}
