package com.partner.mhyscanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ScanCoordinator {
    private static ScanCoordinator instance;
    public static synchronized ScanCoordinator get(Context c) { if (instance==null) instance=new ScanCoordinator(c.getApplicationContext()); return instance; }
    public final AccountStore accounts;
    public final ApiClient api;
    private final android.app.Application application;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService decoder=Executors.newSingleThreadExecutor(), network=Executors.newSingleThreadExecutor();
    private final FrameGate gate=new FrameGate();
    private final LiveFramePump liveFrames=new LiveFramePump(gate);
    private final QrDecoder qrDecoder=new QrDecoder();
    private final CopyOnWriteArrayList<Runnable> observers=new CopyOnWriteArrayList<>();
    private final LinkedHashMap<String,Long> seen=new LinkedHashMap<>();
    private final ArrayList<String> logs=new ArrayList<>();
    private long generation;
    private AccountStore.Account account;
    private QrPayload.Game expected;
    private boolean automatic;
    private volatile boolean active, scanning;
    public volatile int interval=120;
    public volatile boolean centerOnly=false;
    public volatile long frames, decodeMillis, captureMillis;
    public volatile String status="待机 · 添加账号后开始";
    private ApiClient.ScannedLogin pending;
    private boolean confirming;
    private ScanCoordinator(Context c) { application=(android.app.Application)c; accounts=new AccountStore(c); api=new ApiClient(accounts.deviceId()); }
    public void observe(Runnable r) { observers.add(r); }
    public void unobserve(Runnable r) { observers.remove(r); }
    private void changed() { for(Runnable r:observers) main.post(r); }
    public synchronized String logs() { return String.join("\n",logs); }
    public synchronized long sessionId() { return generation; }
    public synchronized boolean hasPending() { return pending!=null; }
    public synchronized String pendingLabel() { return pending==null?"":pending.qr.game.label+" · "+account.display(); }
    public boolean isActive() { return active; }
    public boolean isScanning() { return scanning; }
    public String qrEngineName() { return qrDecoder.engineName(); }
    public synchronized void log(String message) {
        logs.add(new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+"  "+message);
        if(logs.size()>100) logs.remove(0); changed();
    }
    private void state(String value) { status=value; changed(); }
    public synchronized void arm(AccountStore.Account a, boolean auto, QrPayload.Game game) {
        generation++; account=a; automatic=auto; expected=game; pending=null; confirming=false;
        active=true; scanning=true; frames=0; decodeMillis=0; captureMillis=0; seen.clear();
        state("监视中 · "+a.display()+(auto?" · 自动确认":" · 手动确认")); log("已开始授权扫码会话");
    }
    public synchronized void stop() {
        generation++; active=false; scanning=false; pending=null; confirming=false;
        state("已停止"); log("已停止；已发出的服务器确认请求无法撤回");
    }
    /** Reserve before TextureView.getBitmap(), avoiding expensive copies while the decoder is busy. */
    public boolean reserveLiveFrame(long id) {
        synchronized(this) { if(id!=generation || !scanning) return false; }
        if(!liveFrames.tryCapture(SystemClock.elapsedRealtime(),interval)) return false;
        synchronized(this) { if(id==generation && scanning) return true; }
        gate.leave(); return false;
    }
    public void discardReservedLiveFrame() { gate.leave(); }
    /** Owns the bitmap; called only after a successful reserveLiveFrame(). */
    public void submitReservedLiveFrame(Bitmap bitmap,long id) {
        if(bitmap==null) { gate.leave(); return; }
        synchronized(this) { if(id!=generation || !scanning) { bitmap.recycle(); gate.leave(); return; } }
        decodeFrame(bitmap,id);
    }
    /** Owns the bitmap in all cases, including rejected frames. */
    public void submit(Bitmap bitmap, final long id) {
        if(bitmap==null) return;
        synchronized(this) { if(id!=generation || !scanning) { bitmap.recycle(); return; } }
        if(!gate.enter(SystemClock.elapsedRealtime(),interval)) { bitmap.recycle(); return; }
        decodeFrame(bitmap,id);
    }
    private void decodeFrame(Bitmap bitmap, final long id) {
        decoder.execute(() -> {
            try {
                long start=SystemClock.elapsedRealtime();
                List<String> codes=qrDecoder.decode(bitmap,centerOnly);
                decodeMillis=SystemClock.elapsedRealtime()-start; frames++;
                for(String raw:codes) {
                    QrPayload qr=QrPayload.parse(raw);
                    if(qr!=null && !qr.isExpired(System.currentTimeMillis()/1000) && claim(qr,id)) {
                        network.execute(() -> handle(qr,id)); break;
                    }
                }
            } catch(Exception ignored) { /* Bad frames are discarded, never logged as QR contents. */ }
            finally { bitmap.recycle(); gate.leave(); }
        });
    }
    private synchronized boolean claim(QrPayload qr, long id) {
        if(!active || !scanning || id!=generation || (expected!=null && expected!=qr.game)) return false;
        long now=SystemClock.elapsedRealtime(); Long prior=seen.get(qr.ticket);
        if(prior!=null && now-prior<8000) return false;
        if(seen.size()>=64) seen.remove(seen.keySet().iterator().next());
        seen.put(qr.ticket,now); scanning=false; state("找到 "+qr.game.label+" 登录码 · 提交扫码"); return true;
    }
    private synchronized boolean current(long id) { return active && id==generation; }
    private void handle(QrPayload qr, long id) {
        try {
            final AccountStore.Account snapshot;
            synchronized(this) { if(!current(id)) return; snapshot=account; }
            ApiClient.ScannedLogin scanned=api.scan(qr,snapshot,() -> current(id));
            synchronized(this) {
                if(!current(id)) return;
                pending=scanned; state("已扫码 · 等待"+(automatic?"自动":"手动")+"确认"); log("已识别 "+qr.game.label+" 官服登录码");
                if(!automatic) { vibrate(); return; }
                confirming=true;
            }
            finish(scanned,id);
        } catch(Exception e) { failed(id,e,false); }
    }
    public synchronized void confirmPending() {
        if(!active || pending==null || confirming) return;
        confirming=true; final ApiClient.ScannedLogin login=pending; final long id=generation;
        state("正在确认登录"); network.execute(() -> finish(login,id));
    }
    private void finish(ApiClient.ScannedLogin login,long id) {
        try {
            final AccountStore.Account snapshot;
            synchronized(this) { if(!current(id)) return; snapshot=account; }
            if(login.qr.isExpired(System.currentTimeMillis()/1000)) throw new IllegalStateException("二维码已过期，请重新开始");
            if(!current(id)) return;
            api.confirm(login,snapshot);
            synchronized(this) {
                if(!current(id)) return;
                pending=null; confirming=false; active=false; scanning=false;
                state("登录成功 · "+login.qr.game.label); log("官方接口确认成功，已停止监视"); vibrate();
            }
        } catch(Exception e) { failed(id,e,true); }
    }
    private synchronized void failed(long id,Exception e,boolean confirm) {
        if(!current(id)) return;
        confirming=false;
        String message=e instanceof IllegalStateException?e.getMessage():"网络请求失败，请检查连接或使用官方应用验证";
        if(confirm || e instanceof ApiClient.ApiFailure) {
            pending=null; active=false; scanning=false;
            state(confirm?"确认失败 · 需要重新开始":"官方接口拒绝 · 已停止，请手动处理后重新开始");
        } else { scanning=true; state("扫码失败 · 8秒去重后继续监视"); }
        log(message==null?"请求失败":message);
    }
    private void vibrate() {
        Vibrator v=(Vibrator)application.getSystemService(Context.VIBRATOR_SERVICE);
        if(v!=null && v.hasVibrator()) v.vibrate(android.os.VibrationEffect.createOneShot(90,android.os.VibrationEffect.DEFAULT_AMPLITUDE));
    }
}
