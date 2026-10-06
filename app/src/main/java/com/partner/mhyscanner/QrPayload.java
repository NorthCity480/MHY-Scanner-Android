package com.partner.mhyscanner;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/** Android port of upstream ScannerBase, using validated URI fields instead of byte offsets. */
public final class QrPayload {
    public enum Game {
        BH3(1, "bh3_cn", "崩坏3"), GENSHIN(4, "hk4e_cn", "原神"), STAR_RAIL(8, "hkrpg_cn", "星穹铁道"), ZZZ(12, "nap_cn", "绝区零");
        public final int appId;
        public final String biz, label;
        Game(int id, String biz, String label) { this.appId = id; this.biz = biz; this.label = label; }
        public String endpoint(String operation) { return "https://api-sdk.mihoyo.com/" + biz + "/combo/panda/qrcode/" + operation; }
    }
    public final Game game;
    public final String ticket;
    public final long expires;
    private QrPayload(Game game, String ticket, long expires) { this.game = game; this.ticket = ticket; this.expires = expires; }
    public boolean isExpired(long unixSeconds) { return expires != 0 && expires <= unixSeconds; }
    public static QrPayload parse(String raw) {
        if (raw == null || raw.length() > 4096) return null;
        try {
            URI uri = new URI(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"user.mihoyo.com".equalsIgnoreCase(uri.getHost())
                || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !"/qr_code_in_game.html".equals(uri.getRawPath()) || uri.getRawFragment() != null) return null;
            Map<String,String> p = new HashMap<>();
            if (uri.getRawQuery() == null) return null;
            for (String item : uri.getRawQuery().split("&")) {
                String[] kv = item.split("=", 2);
                if (kv.length != 2) return null;
                String key = URLDecoder.decode(kv[0], "UTF-8"), value = URLDecoder.decode(kv[1], "UTF-8");
                if (p.put(key, value) != null) return null;
            }
            String ticket = p.get("ticket");
            if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{16,128}")) return null;
            int id = Integer.parseInt(p.get("app_id"));
            long expiry = p.containsKey("expire") ? Long.parseLong(p.get("expire")) : 0;
            if (expiry < 0) return null;
            for (Game game : Game.values()) {
                if (game.appId == id && game.biz.equals(p.get("biz_key"))) return new QrPayload(game, ticket, expiry);
            }
        } catch (Exception ignored) { }
        return null;
    }
}
