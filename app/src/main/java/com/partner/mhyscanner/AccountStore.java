package com.partner.mhyscanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AccountStore {
    private static final String KEY = "mhy_accounts_v1";
    private final SharedPreferences prefs;
    public enum CredentialKind { GAME_TOKEN, MIYOUSHE_STOKEN }
    public static final class Account {
        public final String uid, alias, token, mid;
        public final CredentialKind kind;
        Account(String uid,String alias,String token) { this(uid,alias,token,"",CredentialKind.GAME_TOKEN); }
        Account(String uid,String alias,String token,String mid,CredentialKind kind) {
            this.uid=uid; this.alias=alias; this.token=token; this.mid=mid; this.kind=kind;
        }
        static Account fromJson(JSONObject a) throws Exception {
            CredentialKind kind;
            try { kind=CredentialKind.valueOf(a.optString("kind","GAME_TOKEN")); }
            catch(IllegalArgumentException e) { throw new IllegalStateException("账号凭据类型不受支持"); }
            return new Account(a.getString("uid"),a.getString("alias"),a.getString("token"),a.optString("mid",""),kind);
        }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("uid",uid).put("alias",alias).put("token",token).put("mid",mid).put("kind",kind.name());
        }
        public String display() { return alias+" · "+(uid.length()>4?"••••"+uid.substring(uid.length()-4):"••••")+(kind==CredentialKind.MIYOUSHE_STOKEN?" · 米游社":""); }
    }
    public AccountStore(Context context) { prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(KEY)) return (SecretKey) ks.getKey(KEY, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return kg.generateKey();
    }
    public synchronized List<Account> list() throws Exception {
        List<Account> list = new ArrayList<>();
        String value = prefs.getString("accounts", "");
        if (value.isEmpty()) return list;
        String[] parts = value.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("账号保险库格式损坏");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        JSONArray data = new JSONArray(new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), "UTF-8"));
        for (int i=0; i<data.length(); i++) {
            JSONObject a = data.getJSONObject(i);
            list.add(Account.fromJson(a));
        }
        return list;
    }
    private void save(List<Account> list) throws Exception {
        JSONArray data = new JSONArray();
        for (Account a : list) data.put(a.toJson());
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        String value = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(data.toString().getBytes("UTF-8")), Base64.NO_WRAP);
        if (!prefs.edit().putString("accounts", value).commit()) throw new IllegalStateException("保存失败");
    }
    public synchronized void add(String uid, String alias, String token) throws Exception {
        add(new Account(uid.trim(),alias.trim(),token.trim()));
    }
    public synchronized void add(Account account) throws Exception {
        String uid=account.uid,token=account.token,alias=account.alias;
        if(!uid.matches("[0-9]{1,20}") || !token.matches("[A-Za-z0-9._~+/=-]{8,4096}") || alias.length()>40
            || account.kind==null || (account.kind==CredentialKind.MIYOUSHE_STOKEN && !account.mid.matches("[A-Za-z0-9_-]{1,128}")))
            throw new IllegalArgumentException("账号或令牌格式不正确");
        List<Account> list=list();
        if(alias.isEmpty()) for(Account prior:list) if(prior.uid.equals(uid)) { alias=prior.alias; break; }
        list.removeIf(a -> a.uid.equals(uid));
        list.add(new Account(uid,alias.isEmpty()?"账号 "+uid.substring(Math.max(0,uid.length()-4)):alias,token,account.mid,account.kind));
        save(list);
    }
    public synchronized void remove(String uid) throws Exception { List<Account> a=list(); a.removeIf(x -> x.uid.equals(uid)); save(a); }
    public synchronized String deviceId() {
        String id = prefs.getString("device", "");
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); prefs.edit().putString("device", id).apply(); }
        return id;
    }
}
