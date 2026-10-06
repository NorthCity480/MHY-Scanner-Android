package com.partner.mhyscanner;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import java.nio.ByteBuffer;

/** Consent-based Android screen capture. Does not use Accessibility, root or Shizuku. */
public final class ScreenCaptureService extends Service {
    private static final String CHANNEL="screen_scan";
    private static final int NOTIFICATION=93;
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private HandlerThread thread;
    private Handler worker;
    private ScanCoordinator engine;
    private volatile boolean closing;
    private long lastFrame, captureSession;
    private String lastStatus="";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(closing) return;
            if(!engine.isActive()) { stopSelf(); return; }
            if(!lastStatus.equals(engine.status)) { lastStatus=engine.status; ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification(lastStatus)); }
            main.postDelayed(this,500);
        }
    };
    private final MediaProjection.Callback projectionCallback=new MediaProjection.Callback() {
        @Override public void onStop() { if(!closing) { engine.stop(); engine.log("系统已撤销屏幕捕获权限"); stopSelf(); } }
        @Override public void onCapturedContentResize(int width,int height) { if(!closing && width>0 && height>0 && display!=null) resize(width,height); }
    };
    @Override public void onCreate() {
        super.onCreate(); engine=ScanCoordinator.get(this);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"屏幕扫码监视",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ScreenCaptureService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("拾光扫码 · 屏幕监视")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || "STOP".equals(intent.getAction())) { engine.stop(); stopSelf(); return START_NOT_STICKY; }
        if(projection!=null) return START_NOT_STICKY;
        if(Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,notification(engine.status),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIFICATION,notification(engine.status));
        try {
            Intent data=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("data",Intent.class):intent.getParcelableExtra("data");
            captureSession=intent.getLongExtra("session",-1);
            if(data==null || !engine.isActive() || captureSession!=engine.sessionId()) { stopSelf(); return START_NOT_STICKY; }
            thread=new HandlerThread("screen-latest-frame"); thread.start(); worker=new Handler(thread.getLooper());
            projection=((MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE)).getMediaProjection(intent.getIntExtra("code",Activity.RESULT_CANCELED),data);
            projection.registerCallback(projectionCallback,worker);
            DisplayMetrics metrics=getResources().getDisplayMetrics();
            android.view.WindowManager wm=(android.view.WindowManager)getSystemService(WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(metrics);
            int[] size=scaled(metrics.widthPixels,metrics.heightPixels);
            reader=makeReader(size[0],size[1]);
            display=projection.createVirtualDisplay("Authorized QR Monitor",size[0],size[1],metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,worker);
            main.post(tick);
        } catch(Exception ignored) { engine.stop(); engine.log("屏幕捕获启动失败，请重新授予系统权限"); stopSelf(); }
        return START_NOT_STICKY;
    }
    private int[] scaled(int width,int height) {
        double scale=Math.min(1.0,1600.0/Math.max(width,height));
        return new int[]{Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale))};
    }
    private ImageReader makeReader(int width,int height) {
        ImageReader r=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
        r.setOnImageAvailableListener(source -> {
            try(Image image=source.acquireLatestImage()) {
                if(image==null || closing || !engine.isScanning()) return;
                long now=SystemClock.elapsedRealtime(); if(now-lastFrame<engine.interval) return; lastFrame=now;
                Image.Plane plane=image.getPlanes()[0]; ByteBuffer buf=plane.getBuffer();
                int w=image.getWidth(),h=image.getHeight(), stride=plane.getRowStride(), pixel=plane.getPixelStride();
                if(pixel!=4 || w<=0 || h<=0) return;
                // Copy only real columns: some devices omit final-row padding from the buffer.
                int[] pixels=new int[w*h];
                for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                    int i=y*stride+x*pixel;
                    if(i+3>=buf.limit()) return;
                    pixels[y*w+x]=((buf.get(i+3)&255)<<24)|((buf.get(i)&255)<<16)|((buf.get(i+1)&255)<<8)|(buf.get(i+2)&255);
                }
                engine.submit(Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888),captureSession);
            } catch(Exception ignored) { }
        },worker);
        return r;
    }
    private void resize(int width,int height) {
        try {
            int[] size=scaled(width,height); ImageReader old=reader;
            display.setSurface(null); reader=makeReader(size[0],size[1]);
            display.resize(size[0],size[1],getResources().getDisplayMetrics().densityDpi); display.setSurface(reader.getSurface());
            if(old!=null) old.close();
        } catch(Exception ignored) { engine.stop(); stopSelf(); }
    }
    @Override public void onDestroy() {
        closing=true; main.removeCallbacks(tick);
        if(display!=null) { display.release(); display=null; }
        if(reader!=null) { reader.close(); reader=null; }
        if(projection!=null) { projection.unregisterCallback(projectionCallback); projection.stop(); projection=null; }
        if(thread!=null) thread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
