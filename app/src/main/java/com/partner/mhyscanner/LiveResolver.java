package com.partner.mhyscanner;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;

/** Public live APIs only; no upstream embedded cookies, signing bypasses or credential collection. */
public final class LiveResolver {
    public static final class Stream {
        public final String url, referer, format;
        Stream(String url,String referer) { this(url,referer,inferFormat(url)); }
        Stream(String url,String referer,String format) { this.url=url; this.referer=referer; this.format=format; }
        private static String inferFormat(String url) {
            try { String path=new URI(url).getPath().toLowerCase(java.util.Locale.ROOT);
                if(path.endsWith(".m3u8")) return "hls";
                if(path.endsWith(".flv")) return "flv";
            } catch(Exception ignored) { }
            return "unknown";
        }
    }
    public static Stream resolve(String input, int platform) throws Exception { return resolve(input,platform,false); }
    public static Stream resolve(String input, int platform,boolean preferHls) throws Exception {
        input=input.trim();
        if(platform==2) {
            URI u=new URI(input);
            if(!"https".equalsIgnoreCase(u.getScheme()) || u.getHost()==null || u.getRawUserInfo()!=null) throw new IllegalArgumentException("请输入 HTTPS 视频流直链，不是直播网页链接");
            return new Stream(input,"");
        }
        String room=input;
        if(input.startsWith("https://")) {
            URI u=new URI(input);
            String host=platform==0?"live.bilibili.com":"live.douyin.com";
            if(!host.equals(u.getHost()) || u.getUserInfo()!=null) throw new IllegalArgumentException("直播间域名不匹配");
            room=u.getPath().replaceAll("^/|/$","");
        }
        if(!room.matches("[0-9]{1,20}")) throw new IllegalArgumentException("请输入纯数字房间号或标准直播间链接");
        return platform==0?bilibili(room,preferHls):douyin(room);
    }
    private static Stream bilibili(String room,boolean preferHls) throws Exception {
        String ref="https://live.bilibili.com/";
        JSONObject init=ApiClient.request("https://api.live.bilibili.com/room/v1/Room/room_init?id="+room,null,ref);
        if(init.optInt("code",-1)!=0) throw new IllegalStateException("B站房间不存在或接口拒绝访问");
        JSONObject data=init.getJSONObject("data");
        if(data.optInt("live_status")!=1) throw new IllegalStateException("直播间尚未开播");
        String real=data.get("room_id").toString();
        JSONObject response=ApiClient.request("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id="+real+"&protocol=0,1&format=0,1,2&codec=0&qn=10000&platform=web",null,ref);
        if(response.optInt("code",-1)!=0) throw new IllegalStateException("B站流地址接口拒绝访问");
        JSONArray streams=response.getJSONObject("data").getJSONObject("playurl_info").getJSONObject("playurl").getJSONArray("stream");
        // Prefer FLV; use HLS if unavailable. Playback delay remains platform/device-dependent.
        for(String preferred:preferHls?new String[]{"fmp4","ts","flv"}:new String[]{"flv","fmp4","ts"}) for(int i=0;i<streams.length();i++) {
            JSONArray formats=streams.getJSONObject(i).getJSONArray("format");
            for(int j=0;j<formats.length();j++) {
                JSONObject format=formats.getJSONObject(j);
                if(!preferred.equals(format.optString("format_name"))) continue;
                JSONArray codecs=format.getJSONArray("codec");
                for(int k=0;k<codecs.length();k++) {
                    JSONObject codec=codecs.getJSONObject(k); JSONArray info=codec.getJSONArray("url_info");
                    for(int n=0;n<info.length();n++) {
                        JSONObject item=info.getJSONObject(n);
                        String url=item.getString("host")+codec.getString("base_url")+item.optString("extra");
                        String secure=secureCdn(url,"bilivideo.com","biliapi.net");
                        if(secure!=null) return new Stream(secure,ref,"flv".equals(preferred)?"flv":"hls");
                    }
                }
            }
        }
        throw new IllegalStateException("未获得受支持的 HTTPS 直播流；可改用屏幕监视或直链");
    }
    private static Stream douyin(String room) throws Exception {
        String ref="https://live.douyin.com/";
        JSONObject response=ApiClient.request("https://live.douyin.com/webcast/room/web/enter/?aid=6383&app_name=douyin_web&live_id=1&device_platform=web&web_rid="+room,null,ref);
        if(response.optInt("status_code",-1)!=0) throw new IllegalStateException("抖音接口需要官方验证；请改用屏幕监视或 HTTPS 直链");
        JSONObject data=response.getJSONObject("data").getJSONArray("data").getJSONObject(0);
        if(data.optInt("status")!=2) throw new IllegalStateException("抖音直播间尚未开播");
        JSONObject url=data.getJSONObject("stream_url");
        JSONObject flv=url.optJSONObject("flv_pull_url");
        if(flv!=null) {
            for(String quality:new String[]{"ORIGIN","FULL_HD1","HD1","SD1"}) {
                String secured=secureCdn(flv.optString(quality),"douyincdn.com","douyinvod.com");
                if(secured!=null) return new Stream(secured,ref,"flv");
            }
        }
        JSONObject core=url.optJSONObject("live_core_sdk_data");
        if(core!=null) {
            JSONObject streamData=new JSONObject(core.getJSONObject("pull_data").getString("stream_data"));
            String secured=secureCdn(streamData.getJSONObject("data").getJSONObject("origin").getJSONObject("main").getString("flv"),"douyincdn.com","douyinvod.com");
            if(secured!=null) return new Stream(secured,ref,"flv");
        }
        throw new IllegalStateException("抖音流解析不可用；可使用屏幕监视，不绕过平台验证");
    }
    private static String secureCdn(String value,String... suffixes) {
        try {
            URI u=new URI(value); if(u.getHost()==null || u.getUserInfo()!=null) return null;
            boolean known=false;
            for(String suffix:suffixes) if(u.getHost().equals(suffix) || u.getHost().endsWith("."+suffix)) known=true;
            if(!known) return null;
            if("https".equals(u.getScheme())) return value;
            if("http".equals(u.getScheme())) return "https"+value.substring(4);
        } catch(Exception ignored) { }
        return null;
    }
}
