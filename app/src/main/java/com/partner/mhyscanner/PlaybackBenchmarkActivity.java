package com.partner.mhyscanner;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Non-exported foreground A/B playback benchmark. Deliberately cannot scan/confirm a game login.
 * No AccountStore, ApiClient instance or ScanCoordinator. Only public room resolution and local QR decoding.
 * Output is timings/counters/codec names, never frames, QR text, stream URIs, cookies or account values.
 */
@OptIn(markerClass=UnstableApi.class)
public final class PlaybackBenchmarkActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private final QrDecoder javaDecoder=new QrDecoder(false),nativeDecoder=new QrDecoder(true);
    private JSONArray fixtureResults=new JSONArray();
    private TextureView texture;
    private TextView label;
    private ExoPlayer player;
    private Surface surface;
    private LiveResolver.Stream stream;
    private final JSONArray results=new JSONArray();
    private int phase=-1;
    private boolean finished,foreground;
    private Metrics current;
    private long started;
    private String error="";
    private final Runnable tick=new Runnable() { public void run() {
        if(!foreground || finished || player==null) return;
        Metrics m=current;
        if(player.getPlaybackState()==Player.STATE_READY) m.lead.add((double)Math.max(0,player.getBufferedPosition()-player.getCurrentPosition()));
        label.setText("仅播放与本地识码，不登录任何游戏\n阶段 "+(phase+1)+"/4 · "+m.mode+"\n解码器："+m.codec+"\n新画面 "+m.updates+" / 已识别帧 "+m.capture.size()+"\n时间 "+((SystemClock.elapsedRealtime()-started)/1000)+" / 25 秒");
        if(SystemClock.elapsedRealtime()-started>=25000) next();
        else main.postDelayed(this,500);
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,80,24,24);
        label=new TextView(this); label.setText("直播性能 A/B · 只访问公开房间，不使用账号\n正在解析直播流…"); root.addView(label);
        texture=new TextureView(this); root.addView(texture,new LinearLayout.LayoutParams(-1,600)); setContentView(root);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h) { attach(); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h) {}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t) { detach(); return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture t) { capture(); }
        });
        String room=getIntent().getStringExtra("room"); if(room==null || !room.matches("[0-9]{1,20}")) room="24346239";
        final String safeRoom=room;
        work.execute(() -> {
            try { fixtureResults=runFixtures(); LiveResolver.Stream resolved=LiveResolver.resolve(safeRoom,0); main.post(() -> { if(!finished && foreground) { stream=resolved; next(); } }); }
            catch(Exception e) { main.post(() -> fail("public_room_resolution_failed")); }
        });
    }
    private void attach() { if(player!=null && texture.isAvailable()) { detach(); surface=new Surface(texture.getSurfaceTexture()); player.setVideoSurface(surface); } }
    private void detach() { if(surface!=null) { if(player!=null) player.clearVideoSurface(); surface.release(); surface=null; } }
    private void release() { detach(); if(player!=null) { player.release(); player=null; } }
    private void next() {
        main.removeCallbacks(tick);
        if(current!=null) { current.thermalEnd=thermal(); results.put(current.json()); }
        release(); phase++;
        if(phase>=4) { complete(); return; }
        // A/B/B/A reduces (but does not eliminate) changing stream/network and warm-up bias.
        boolean optimized=phase==1 || phase==2;
        Metrics m=new Metrics(optimized?"optimized":"baseline"); m.qrEngine=optimized?nativeDecoder.engineName():javaDecoder.engineName(); current=m; started=SystemClock.elapsedRealtime(); m.thermalStart=thermal();
        player=LivePlayback.create(this,stream,optimized);
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime time,String name,long at,long duration) { m.codec=name; m.codecInitMs=duration; }
            @Override public void onRenderedFirstFrame(EventTime time,Object output,long at) { if(m.firstFrameMs<0) m.firstFrameMs=SystemClock.elapsedRealtime()-started; }
            @Override public void onDroppedVideoFrames(EventTime time,int count,long elapsed) { m.dropped+=count; }
            @Override public void onVideoFrameProcessingOffset(EventTime time,long totalUs,int count) { m.processingUs+=totalUs; m.processingFrames+=count; }
        });
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) { if(state==Player.STATE_BUFFERING && m.firstFrameMs>=0) m.rebuffers++; }
            @Override public void onVideoSizeChanged(VideoSize size) {
                m.width=size.width; m.height=size.height;
                if(size.width>0 && size.height>0) texture.setLayoutParams(new LinearLayout.LayoutParams(-1,Math.max(100,(int)(texture.getWidth()*(double)size.height/size.width))));
            }
            @Override public void onPlayerError(PlaybackException e) { fail(e.getErrorCodeName()); }
        });
        attach(); player.prepare(); player.play(); main.post(tick);
    }
    private void capture() {
        if(finished || !foreground || player==null || current==null || player.getPlaybackState()!=Player.STATE_READY) return;
        Metrics m=current; m.updates++;
        if(!busy.compareAndSet(false,true)) { m.busySkipped++; return; }
        int w=Math.min(960,Math.max(1,texture.getWidth())); int h=Math.max(1,(int)(w*(double)texture.getHeight()/Math.max(1,texture.getWidth())));
        Bitmap b=null; long t=SystemClock.elapsedRealtimeNanos();
        try {
            b=texture.getBitmap(w,h); double captureMs=(SystemClock.elapsedRealtimeNanos()-t)/1000000.0;
            if(b==null) { busy.set(false); return; }
            final Bitmap owned=b;
            work.execute(() -> {
                long start=SystemClock.elapsedRealtimeNanos(); int count=0;
                try { count=("optimized".equals(m.mode)?nativeDecoder:javaDecoder).decode(owned,false).size(); }
                catch(RuntimeException ignored) { }
                finally { double decodeMs=(SystemClock.elapsedRealtimeNanos()-start)/1000000.0; owned.recycle();
                    final int detected=count; main.post(() -> { if(current==m && !finished) { m.capture.add(captureMs); m.decode.add(decodeMs); m.qrCount+=detected; } busy.set(false); }); }
            });
        } catch(RuntimeException e) { if(b!=null) b.recycle(); busy.set(false); }
    }
    private int thermal() { return android.os.Build.VERSION.SDK_INT>=29?((PowerManager)getSystemService(POWER_SERVICE)).getCurrentThermalStatus():-1; }
    private void fail(String safeError) { error=safeError; if(current!=null) { results.put(current.json()); current=null; } complete(); }
    private void complete() {
        finished=true; main.removeCallbacks(tick); release();
        try {
            JSONObject report=new JSONObject().put("benchmark_only",true).put("login_requests",0).put("model",android.os.Build.MODEL)
                .put("sdk",android.os.Build.VERSION.SDK_INT).put("phase_ms",25000).put("error",error).put("results",results)
                .put("end_to_end_latency_measured",false).put("fixtures",fixtureResults);
            try(FileOutputStream out=new FileOutputStream(new File(getCacheDir(),"playback-benchmark.json"))) { out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
            label.setText("性能测试结束 · 未使用任何账号，未提交登录\n"+report.toString(2));
        } catch(Exception e) { label.setText("测试结束；本地报告保存失败"); }
    }
    @Override protected void onResume() { super.onResume(); foreground=true; }
    @Override protected void onPause() { foreground=false; if(!finished) { error="foreground_cancelled"; complete(); } super.onPause(); }
    @Override protected void onDestroy() { finished=true; main.removeCallbacksAndMessages(null); release(); work.shutdown(); super.onDestroy(); }
    /** Local synthetic and optionally supplied public, expired upstream fixtures. Never uploads decoded data. */
    private JSONArray runFixtures() throws Exception {
        JSONArray tests=new JSONArray();
        String[] names={"bh3_qrcode_1080.png","hk4e_qrcode_1080.png","hkrpg_qrcode_1080.png","zzz_qrcode_1440.png"};
        for(String name:names) {
            File file=new File(new File(getCacheDir(),"qr-fixtures"),name); if(!file.isFile()) continue;
            Bitmap original=BitmapFactory.decodeFile(file.getAbsolutePath()); if(original==null) continue;
            int w=Math.min(960,original.getWidth()); Bitmap b=Bitmap.createScaledBitmap(original,w,Math.max(1,w*original.getHeight()/original.getWidth()),true);
            List<String> reference=javaDecoder.decode(original,false),candidate=nativeDecoder.decode(b,false);
            tests.put(new JSONObject().put("name",name).put("reference_count",reference.size()).put("reference_width",original.getWidth())
                .put("native_count",candidate.size()).put("native_width",b.getWidth())
                .put("matches_reference",!candidate.isEmpty() && new java.util.HashSet<>(reference).equals(new java.util.HashSet<>(candidate))));
            if(b!=original) original.recycle(); b.recycle();
        }
        Bitmap a=synthetic("local-offline-qr-one"),b=synthetic("local-offline-qr-two");
        Bitmap pair=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(pair); canvas.drawColor(Color.WHITE); canvas.drawBitmap(a,50,120,null); canvas.drawBitmap(b,600,120,null);
        List<String> multi=nativeDecoder.decode(pair,false);
        tests.put(new JSONObject().put("name","synthetic_multiple").put("passed",multi.contains("local-offline-qr-one") && multi.contains("local-offline-qr-two")));
        Matrix rotation=new Matrix(); rotation.postRotate(90); Bitmap rotated=Bitmap.createBitmap(a,0,0,a.getWidth(),a.getHeight(),rotation,true);
        tests.put(new JSONObject().put("name","synthetic_rotated").put("passed",nativeDecoder.decode(rotated,false).contains("local-offline-qr-one")));
        Bitmap inverted=a.copy(Bitmap.Config.ARGB_8888,true); int[] pixels=new int[300*300]; inverted.getPixels(pixels,0,300,0,0,300,300);
        for(int i=0;i<pixels.length;i++) pixels[i]=0xff000000 | (~pixels[i]&0xffffff); inverted.setPixels(pixels,0,300,0,0,300,300);
        tests.put(new JSONObject().put("name","synthetic_inverted").put("passed",nativeDecoder.decode(inverted,false).contains("local-offline-qr-one")));
        Bitmap roi=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888); Canvas rc=new Canvas(roi); rc.drawColor(Color.WHITE); rc.drawBitmap(a,450,250,null); rc.drawBitmap(b,0,0,null);
        List<String> center=nativeDecoder.decode(roi,true);
        tests.put(new JSONObject().put("name","synthetic_center_crop").put("passed",center.contains("local-offline-qr-one") && !center.contains("local-offline-qr-two")));
        a.recycle(); b.recycle(); pair.recycle(); rotated.recycle(); inverted.recycle(); roi.recycle();
        return tests;
    }
    private static Bitmap synthetic(String text) throws Exception {
        BitMatrix code=new QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,300,300); Bitmap b=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888);
        for(int y=0;y<300;y++) for(int x=0;x<300;x++) b.setPixel(x,y,code.get(x,y)?Color.BLACK:Color.WHITE); return b;
    }
    private static final class Metrics {
        final String mode; String codec="",qrEngine=""; int width,height,rebuffers,dropped,qrCount,thermalStart,thermalEnd;
        long firstFrameMs=-1,codecInitMs,updates,busySkipped,processingUs,processingFrames;
        final List<Double> capture=new ArrayList<>(),decode=new ArrayList<>(),lead=new ArrayList<>();
        Metrics(String mode) { this.mode=mode; }
        JSONObject json() {
            JSONObject j=new JSONObject();
            try { j.put("mode",mode).put("codec",codec).put("qr_engine",qrEngine).put("width",width).put("height",height).put("first_frame_ms",firstFrameMs).put("codec_init_ms",codecInitMs)
                .put("new_frames",updates).put("analyzed_frames",capture.size()).put("busy_skipped",busySkipped).put("detected_qr_count",qrCount)
                .put("rebuffers",rebuffers).put("dropped_frames",dropped).put("thermal_start",thermalStart).put("thermal_end",thermalEnd)
                .put("capture_p50_ms",p(capture,.5)).put("capture_p95_ms",p(capture,.95)).put("decode_p50_ms",p(decode,.5)).put("decode_p95_ms",p(decode,.95))
                .put("buffer_lead_p50_ms",p(lead,.5)).put("buffer_lead_p95_ms",p(lead,.95))
                .put("render_processing_offset_mean_ms",processingFrames==0?JSONObject.NULL:(processingUs/1000.0/processingFrames));
            } catch(Exception ignored) { } return j;
        }
        static Object p(List<Double> values,double q) { if(values.isEmpty()) return JSONObject.NULL; List<Double> copy=new ArrayList<>(values); Collections.sort(copy); return copy.get(Math.min(copy.size()-1,(int)Math.ceil(copy.size()*q)-1)); }
    }
}
