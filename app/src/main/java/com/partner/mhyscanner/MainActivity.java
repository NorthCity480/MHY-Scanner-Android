package com.partner.mhyscanner;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
    private static final int BG=0xff10121b, CARD=0xff1c202d, MUTED=0xff9ba4bb, INK=0xfff4f2f8, PINK=0xffeba6bf, GREEN=0xffa5e0cf;
    private static final int SCREEN=42, IMAGE=43;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private ScanCoordinator engine;
    private LinearLayout root, monitor, accountPanel, logPanel, accountList;
    private TextView status,stats,logs,accountCount;
    private Spinner accountChoice,gameChoice,platformChoice,intervalChoice,speedChoice;
    private EditText roomInput;
    private CheckBox autoCheck,centerCheck;
    private Button confirm;
    private TextureView texture;
    private List<AccountStore.Account> accountData=new ArrayList<>();
    private ExoPlayer player;
    private String videoDecoder="";
    private Surface videoSurface;
    private boolean resumed;
    private long streamRequest, streamSession, imageSession;
    private AccountStore.Account screenAccount;
    private QrPayload.Game screenGame;
    private boolean screenAuto;
    private AlertDialog enrollmentDialog;
    private AtomicBoolean enrollmentCancel=new AtomicBoolean(true);
    private final Runnable observer=() -> refreshStatus();
    private final Runnable refreshTick=new Runnable() { public void run() { if(resumed) { refreshStatus(); main.postDelayed(this,1000); } } };
    private final TextureView.SurfaceTextureListener liveTextureListener=new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface,int width,int height) { attachVideoSurface(); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { detachVideoSurface(); return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { captureNewVideoFrame(); }
    };
    private void attachVideoSurface() {
        if(player==null || texture==null || !texture.isAvailable()) return;
        detachVideoSurface(); videoSurface=new Surface(texture.getSurfaceTexture()); player.setVideoSurface(videoSurface);
    }
    private void detachVideoSurface() {
        if(videoSurface==null) return;
        if(player!=null) player.clearVideoSurface();
        videoSurface.release(); videoSurface=null;
    }
    /** Only a freshly rendered frame may trigger a capture; reserve before getBitmap(). */
    private void captureNewVideoFrame() {
        if(player==null || !resumed || !texture.isAvailable() || player.getPlaybackState()!=Player.STATE_READY) return;
        if(!engine.isActive()) { releasePlayer(); return; }
        if(!engine.reserveLiveFrame(streamSession)) return;
        Bitmap bitmap=null;
        try {
            int width=Math.min(engine.interval==0?960:1280,Math.max(1,texture.getWidth()));
            int height=Math.max(1,(int)(width*(double)texture.getHeight()/Math.max(1,texture.getWidth())));
            long started=SystemClock.elapsedRealtimeNanos();
            bitmap=texture.getBitmap(width,height);
            engine.captureMillis=(SystemClock.elapsedRealtimeNanos()-started)/1000000;
            if(bitmap==null) { engine.discardReservedLiveFrame(); return; }
            engine.submitReservedLiveFrame(bitmap,streamSession); bitmap=null;
        } catch(RuntimeException e) {
            if(bitmap!=null) bitmap.recycle();
            engine.discardReservedLiveFrame();
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); engine=ScanCoordinator.get(this); buildUi(); refreshAccounts();
        String requestedRoom=getIntent().getStringExtra("room");
        if(requestedRoom!=null && requestedRoom.matches("[0-9]{1,20}")) { platformChoice.setSelection(0); roomInput.setText(requestedRoom); getPreferences(0).edit().putString("bili_room",requestedRoom).apply(); }
        if(getIntent().getBooleanExtra("low_latency_preset",false)) selectLowLatencyPreset();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},9);
    }
    private int dp(float n) { return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
    private GradientDrawable background(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value,int size,int color) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0,dp(5),0,dp(5)); return v;
    }
    private LinearLayout vertical() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout l=vertical(); l.setPadding(dp(18),dp(13),dp(18),dp(15)); l.setBackground(background(CARD,18));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(12); parent.addView(l,p); return l;
    }
    private Button button(String value,int color,Runnable click) {
        Button b=new Button(this); b.setText(value); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(color==CARD?INK:BG);
        b.setMinHeight(dp(46)); b.setMinimumHeight(dp(46)); b.setBackground(background(color,12)); b.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48)); p.topMargin=dp(9); b.setLayoutParams(p); return b;
    }
    private EditText field(String hint,boolean secret) {
        EditText e=new EditText(this); e.setTextSize(15); e.setTextColor(INK); e.setHintTextColor(MUTED); e.setHint(hint); e.setSingleLine(true);
        e.setPadding(dp(12),dp(10),dp(12),dp(10)); e.setBackground(background(BG,10));
        e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if(secret) e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48)); p.topMargin=dp(8); e.setLayoutParams(p); return e;
    }
    private Spinner spinner(String[] values) {
        Spinner s=new Spinner(this); ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);
        s.setAdapter(adapter); s.setMinimumHeight(dp(44)); return s;
    }
    private void buildUi() {
        root=vertical(); root.setBackgroundColor(BG); root.setPadding(dp(20),dp(18),dp(20),dp(8)); setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets) -> {
            if(Build.VERSION.SDK_INT>=30) { Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()); root.setPadding(dp(20)+bars.left,dp(12)+bars.top,dp(20)+bars.right,dp(8)+bars.bottom); }
            else root.setPadding(dp(20),dp(12)+insets.getSystemWindowInsetTop(),dp(20),dp(8)+insets.getSystemWindowInsetBottom());
            return insets;
        }); root.requestApplyInsets();
        LinearLayout header=row(); TextView logo=text("✦",34,PINK); header.addView(logo,new LinearLayout.LayoutParams(dp(45),-2));
        LinearLayout titles=vertical(); titles.addView(text("拾光扫码",24,INK)); titles.addView(text("MHY SCANNER  /  ANDROID",10,MUTED)); header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=text("本地加密",11,GREEN); header.addView(badge); root.addView(header);
        LinearLayout nav=row();
        for(int i=0;i<3;i++) {
            final int index=i; Button b=button(new String[]{"监视","账号","记录"}[i],CARD,() -> showPanel(index));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(43),1); p.setMargins(i==0?0:dp(6),dp(10),0,dp(14)); nav.addView(b,p);
        } root.addView(nav);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout content=vertical(); scroll.addView(content);
        monitor=vertical(); accountPanel=vertical(); logPanel=vertical(); content.addView(monitor); content.addView(accountPanel); content.addView(logPanel);
        LinearLayout stateCard=card(monitor); stateCard.addView(text("●  会话状态",12,GREEN));
        status=text(engine.status,18,INK); stateCard.addView(status); stats=text("0 帧  ·  解码 — ms",12,MUTED); stateCard.addView(stats);
        confirm=button("确认这个登录",PINK,() -> engine.confirmPending()); stateCard.addView(confirm); confirm.setVisibility(View.GONE);
        stateCard.addView(button("停止全部监视",CARD,() -> stopAll()));
        LinearLayout identity=card(monitor); identity.addView(text("01  /  选择账号",11,MUTED)); accountChoice=spinner(new String[]{"先添加自己的账号"}); identity.addView(accountChoice);
        identity.addView(text("目标游戏 · 仅官服",12,MUTED)); gameChoice=spinner(new String[]{"自动识别四款游戏","崩坏3","原神","星穹铁道","绝区零"}); identity.addView(gameChoice);
        LinearLayout sources=card(monitor); sources.addView(text("02  /  选择识码来源",11,MUTED));
        sources.addView(button("开始屏幕监视",PINK,() -> authorize(0)));
        sources.addView(text("系统会请求录屏授权。可切到游戏或直播 App；停止按钮始终保留在通知栏。",12,MUTED));
        platformChoice=spinner(new String[]{"B站直播","抖音直播 · 实验性","HTTPS 视频流直链"}); sources.addView(platformChoice);
        roomInput=field("房间号 / 直播间链接 / 视频流直链",false); roomInput.setText(getPreferences(0).getString("bili_room","")); sources.addView(roomInput);
        speedChoice=spinner(new String[]{"播放速度 1.0x · 正常","追帧 1.15x","追帧 1.25x · 推荐","追帧 1.5x · 易卡顿"});
        speedChoice.setSelection(getPreferences(0).getInt("speed",0));
        speedChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                getPreferences(0).edit().putInt("speed",position).apply(); applyPlaybackSpeed();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        sources.addView(speedChoice);
        sources.addView(text("倍速会同步提高直播取帧：1.15x / 1.25x / 1.5x 自动使用新帧识码；追到最新位置或出现卡顿时请恢复 1.0x。它不能减少主播或 CDN 的源端延迟。",12,MUTED));
        sources.addView(button("应用本机低延迟采样方案",CARD,() -> { selectLowLatencyPreset(); message("已选即时新帧采样（高功耗）；直播自动优先支持的低延迟硬件解码器。开始仍需会话授权。"); }));
        sources.addView(button("监视直播流",GREEN,() -> authorize(1)));
        sources.addView(button("纯播放性能测试 · 不登录",CARD,() -> benchmark()));
        texture=new TextureView(this); texture.setSurfaceTextureListener(liveTextureListener); LinearLayout.LayoutParams videoParams=new LinearLayout.LayoutParams(-1,dp(205)); videoParams.topMargin=dp(12); sources.addView(texture,videoParams); texture.setVisibility(View.GONE);
        sources.addView(text("直播识码需前台。自动优先低延迟硬件解码，原生多码识别；不支持时保守回退。紧缓冲可能增加弱网卡顿。",12,MUTED));
        sources.addView(button("从图片识码",CARD,() -> authorize(2)));
        LinearLayout options=card(monitor); options.addView(text("03  /  会话选项",11,MUTED));
        intervalChoice=spinner(new String[]{"60 ms · 高功耗","120 ms · 均衡","250 ms · 省电","500 ms · 低频","即时 · 直播新帧触发（高功耗）"});
        intervalChoice.setSelection(getPreferences(0).getInt("interval",4)); options.addView(intervalChoice);
        centerCheck=new CheckBox(this); centerCheck.setText("只识别画面中心 60% 区域"); centerCheck.setTextColor(INK); centerCheck.setChecked(getPreferences(0).getBoolean("center",false)); options.addView(centerCheck);
        autoCheck=new CheckBox(this); autoCheck.setText("本次会话自动确认登录"); autoCheck.setTextColor(PINK); options.addView(autoCheck);
        options.addView(text("默认手动确认。开启自动确认，会把所选账号登录到二维码对应的设备；仅用于本人设备或明确获授权的会话。",12,MUTED));
        buildAccounts();
        LinearLayout logCard=card(logPanel); logCard.addView(text("会话记录",19,INK)); logCard.addView(text("仅存内存，最多 100 条。二维码票据、令牌与完整账号 ID 不进入日志。",12,MUTED));
        logs=text("暂无记录",12,MUTED); logs.setTypeface(Typeface.MONOSPACE); logs.setTextIsSelectable(true); logCard.addView(logs);
        logCard.addView(button("本地解码自检",GREEN,() -> localTest()));
        logCard.addView(button("关于 / 开源许可",CARD,() -> about()));
        TextView footer=text("非官方工具  ·  不绕过验证码或系统录屏授权",10,MUTED); footer.setGravity(Gravity.CENTER); root.addView(footer);
        showPanel(0);
    }
    private void showPanel(int n) { monitor.setVisibility(n==0?View.VISIBLE:View.GONE); accountPanel.setVisibility(n==1?View.VISIBLE:View.GONE); logPanel.setVisibility(n==2?View.VISIBLE:View.GONE); }
    private void buildAccounts() {
        LinearLayout box=card(accountPanel); box.addView(text("账号保险库",21,INK));
        accountCount=text("尚未添加账号",12,MUTED); box.addView(accountCount);
        box.addView(text("凭据只保存在此设备，使用 Android Keystore + AES-GCM 加密；不上传到开发者服务器，不读取其他 App 的登录数据。",13,MUTED));
        box.addView(button("米游社扫码添加账号",PINK,() -> enroll()));
        box.addView(button("手动导入自己的 game_token",CARD,() -> manualAccount()));
        box.addView(text("导入的是米哈游通行证账号 ID，不是游戏角色 UID。令牌过期后重新添加；不支持崩坏3 B服账号。",12,MUTED));
        accountList=vertical(); box.addView(accountList);
    }
    private void refreshAccounts() {
        String prior=""; int position=accountChoice.getSelectedItemPosition(); if(position>=0 && position<accountData.size()) prior=accountData.get(position).uid;
        try {
            accountData=engine.accounts.list(); String[] labels=new String[Math.max(1,accountData.size())];
            if(accountData.isEmpty()) labels[0]="先到「账号」添加自己的账号";
            int selected=0;
            for(int i=0;i<accountData.size();i++) { labels[i]=accountData.get(i).display(); if(accountData.get(i).uid.equals(prior)) selected=i; }
            accountChoice.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels)); accountChoice.setSelection(selected);
            accountCount.setText(accountData.size()+" 个账号 · 令牌不显示、不导出"); accountList.removeAllViews();
            for(AccountStore.Account a:accountData) {
                LinearLayout item=vertical(); item.addView(text(a.display(),15,INK));
                item.addView(button("移除此账号",CARD,() -> new AlertDialog.Builder(this).setTitle("移除 "+a.display()+"？").setMessage("将停止当前会话并删除此设备保存的凭据。").setNegativeButton("取消",null).setPositiveButton("移除",(d,w) -> {
                    try { stopAll(); engine.accounts.remove(a.uid); refreshAccounts(); } catch(Exception e) { message("未能删除账号"); }
                }).show())); accountList.addView(item);
            }
        } catch(Exception e) { accountData.clear(); accountCount.setText("保险库无法解密。请勿继续导入；可在系统设置清除本应用数据后重新授权。"); }
    }
    private QrPayload.Game game() { int index=gameChoice.getSelectedItemPosition(); return index==0?null:QrPayload.Game.values()[index-1]; }
    private void authorize(int source) {
        int index=accountChoice.getSelectedItemPosition();
        if(index<0 || index>=accountData.size()) { message("请先添加自己的账号"); showPanel(1); return; }
        final AccountStore.Account selected=accountData.get(index); final boolean auto=autoCheck.isChecked(); final QrPayload.Game selectedGame=game();
        if(source==1 && roomInput.getText().toString().trim().isEmpty()) { message("请输入直播间或 HTTPS 流地址"); return; }
        new AlertDialog.Builder(this).setTitle(auto?"授权本次自动登录？":"开始本次扫码会话？")
            .setMessage("所选账号："+selected.display()+"\n目标："+(selectedGame==null?"自动识别":selectedGame.label)+"\n\n请确认二维码来源为本人设备或已得到设备主人的明确授权。"+(auto?"\n识别后将自动把此账号登录到二维码对应的设备，无需再次确认。":"\n识别后仍需回到此应用确认登录。"))
            .setNegativeButton("取消",null).setPositiveButton("确认授权",(dialog,which) -> {
                int intervalIndex=intervalChoice.getSelectedItemPosition(); engine.interval=intervalChoiceValue();
                if(source==1 && selectedPlaybackSpeed()>1.0f) engine.interval=0;
                engine.centerOnly=centerCheck.isChecked();
                getPreferences(0).edit().putInt("interval",intervalIndex).putBoolean("center",engine.centerOnly).apply();
                stopAll();
                if(source==0) {
                    screenAccount=selected; screenAuto=auto; screenGame=selectedGame;
                    startActivityForResult(((MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE)).createScreenCaptureIntent(),SCREEN);
                } else {
                    engine.arm(selected,auto,selectedGame);
                    if(source==1) startLive();
                    else { imageSession=engine.sessionId(); Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(pick,IMAGE); }
                }
            }).show();
    }
    private int intervalChoiceValue() {
        int index=intervalChoice==null?4:intervalChoice.getSelectedItemPosition();
        return new int[]{60,120,250,500,0}[Math.max(0,Math.min(4,index))];
    }
    private int selectedScanInterval() {
        // Faster live playback needs a fresh rendered frame for each scan opportunity.
        return selectedPlaybackSpeed()>1.0f?0:intervalChoiceValue();
    }
    private float selectedPlaybackSpeed() {
        int index=speedChoice==null?0:speedChoice.getSelectedItemPosition();
        return new float[]{1.0f,1.15f,1.25f,1.5f}[Math.max(0,Math.min(3,index))];
    }
    private void applyPlaybackSpeed() {
        float speed=selectedPlaybackSpeed();
        if(player!=null && engine!=null && engine.isActive()) engine.interval=selectedScanInterval();
        if(player==null) return;
        player.setPlaybackSpeed(speed);
        if(speed>1.0f && engine!=null && engine.isActive()) engine.log("播放与识码同步追帧 · "+speed+"x；已切换新帧识码，追到最新位置后请恢复 1.0x");
    }
    private void selectLowLatencyPreset() { intervalChoice.setSelection(4); getPreferences(0).edit().putInt("interval",4).apply(); }
    private void benchmark() {
        String room=roomInput.getText().toString().trim();
        if(room.startsWith("https://live.bilibili.com/")) room=room.substring("https://live.bilibili.com/".length()).replaceAll("/$","");
        if(!room.matches("[0-9]{1,20}")) { message("性能测试请填写公开 B站纯数字房间号；不会使用账号或提交登录"); return; }
        final String safeRoom=room;
        new AlertDialog.Builder(this).setTitle("仅播放性能测试？").setMessage("停止当前扫码会话，进行约100秒前台 A/B 测试。只访问公开房间并本地识码，不使用保险库、不发送扫码或确认请求。请保持此页前台；离开即取消。")
            .setNegativeButton("取消",null).setPositiveButton("开始测试",(d,w) -> { stopAll(); startActivity(new Intent(this,PlaybackBenchmarkActivity.class).putExtra("room",safeRoom)); }).show();
    }
    private void stopAll() {
        streamRequest++; releasePlayer(); stopService(new Intent(this,ScreenCaptureService.class)); engine.stop();
    }
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void startLive() {
        final long request=++streamRequest; final long session=engine.sessionId(); final String input=roomInput.getText().toString(); final int platform=platformChoice.getSelectedItemPosition();
        engine.log("正在解析直播流（流地址不写入日志）");
        io.execute(() -> {
            try {
                LiveResolver.Stream stream=LiveResolver.resolve(input,platform);
                main.post(() -> {
                    if(request!=streamRequest || !resumed || !engine.isActive()) return;
                    releasePlayer(); streamSession=session; texture.setVisibility(View.VISIBLE);
                    player=LivePlayback.create(this,stream,true); videoDecoder=""; applyPlaybackSpeed();
                    if(platform==0 && input.trim().matches("[0-9]{1,20}")) getPreferences(0).edit().putString("bili_room",input.trim()).apply();
                    attachVideoSurface();
                    player.addAnalyticsListener(new AnalyticsListener() {
                        @Override public void onVideoDecoderInitialized(EventTime time,String name,long at,long duration) {
                            if(player==null || request!=streamRequest) return;
                            videoDecoder=name.replaceAll("[^A-Za-z0-9_.-]",""); engine.log("直播硬件解码器 · "+videoDecoder);
                        }
                    });
                    player.addListener(new Player.Listener() {
                        @Override public void onPlayerError(PlaybackException e) { engine.log("播放失败 · "+e.getErrorCodeName()+"；可改用屏幕监视"); engine.stop(); releasePlayer(); }
                        @Override public void onPlaybackStateChanged(int state) {
                            if(state==Player.STATE_READY) engine.log("直播流就绪；正在从解码画面识码");
                            if(state==Player.STATE_ENDED) { engine.log("直播流已结束"); engine.stop(); releasePlayer(); }
                        }
                        @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                            if(size.width>0 && size.height>0) { LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)texture.getLayoutParams(); p.height=(int)(Math.max(dp(160),root.getWidth()-dp(76))*((double)size.height/size.width)); p.height=Math.min(dp(480),Math.max(dp(130),p.height)); texture.setLayoutParams(p); }
                        }
                    });
                    player.prepare(); player.play();
                });
            } catch(Exception e) {
                main.post(() -> { if(request!=streamRequest) return; engine.stop(); engine.log(e instanceof IllegalStateException||e instanceof IllegalArgumentException?String.valueOf(e.getMessage()):"直播接口返回格式变化或网络失败；请使用屏幕监视 / HTTPS 直链"); });
            }
        });
    }
    private void releasePlayer() {
        detachVideoSurface(); if(player!=null) { player.release(); player=null; }
        videoDecoder=""; if(texture!=null) texture.setVisibility(View.GONE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==SCREEN) {
            if(result==RESULT_OK && data!=null && screenAccount!=null) {
                engine.arm(screenAccount,screenAuto,screenGame);
                try { startForegroundService(new Intent(this,ScreenCaptureService.class).putExtra("code",result).putExtra("data",data).putExtra("session",engine.sessionId())); }
                catch(Exception e) { engine.stop(); message("启动屏幕监视失败"); }
            } else { engine.log("未授予屏幕捕获权限"); }
            screenAccount=null;
        } else if(request==IMAGE) {
            if(result!=RESULT_OK || data==null || data.getData()==null) { engine.stop(); return; }
            final Uri uri=data.getData(); final long session=imageSession;
            io.execute(() -> {
                try {
                    BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
                    try(InputStream in=getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in,null,bounds); }
                    if(bounds.outWidth<=0 || bounds.outHeight<=0) throw new IllegalArgumentException();
                    BitmapFactory.Options options=new BitmapFactory.Options(); options.inSampleSize=1;
                    while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>2048) options.inSampleSize*=2;
                    Bitmap b; try(InputStream in=getContentResolver().openInputStream(uri)) { b=BitmapFactory.decodeStream(in,null,options); }
                    engine.submit(b,session); engine.log("已提交图片；无匹配二维码时不会发出登录请求");
                } catch(Exception e) { main.post(() -> { engine.stop(); message("无法读取此图片"); }); }
            });
        }
    }
    private void refreshStatus() {
        if(status==null) return;
        if(player!=null && !engine.isActive()) releasePlayer();
        status.setText(engine.status);
        long liveOffset=player==null?androidx.media3.common.C.TIME_UNSET:player.getCurrentLiveOffset();
        long buffered=player==null?0:Math.max(0,player.getBufferedPosition()-player.getCurrentPosition());
        stats.setText(engine.frames+" 帧  ·  抓帧 "+engine.captureMillis+" ms  ·  识码 "+engine.decodeMillis+" ms  ·  "+engine.qrEngineName()+"\n"+(engine.interval==0?"新帧触发":"间隔 "+engine.interval+" ms")
            +(player==null?"":"  ·  本机缓存约 "+buffered+" ms")
            +(liveOffset==androidx.media3.common.C.TIME_UNSET?"":"  ·  媒体直播偏移约 "+liveOffset+" ms")+(videoDecoder.isEmpty()?"":"\n"+videoDecoder));
        boolean pending=engine.hasPending(); confirm.setVisibility(pending?View.VISIBLE:View.GONE);
        if(pending) confirm.setText("确认登录 · "+engine.pendingLabel());
        String value=engine.logs(); logs.setText(value.isEmpty()?"暂无记录":value);
    }
    private void manualAccount() {
        stopAll(); LinearLayout box=vertical(); box.setPadding(dp(20),dp(8),dp(20),dp(12));
        EditText alias=field("备注（可选）",false),uid=field("通行证账号 ID，不是游戏角色 UID",false),token=field("自己的 game_token（不是 stoken）",true);
        uid.setInputType(InputType.TYPE_CLASS_NUMBER); box.addView(alias); box.addView(uid); box.addView(token);
        box.addView(text("不要把令牌发给他人。本应用仅将它加密保存在本机并提交到米哈游官方登录接口。",12,MUTED));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("导入自己的账号").setView(box).setNegativeButton("取消",null).setPositiveButton("加密保存",null).create();
        dialog.setOnDismissListener(d -> { token.setText(""); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE); });
        dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { engine.accounts.add(uid.getText().toString(),alias.getText().toString(),token.getText().toString()); refreshAccounts(); dialog.dismiss(); }
            catch(Exception e) { message(e instanceof IllegalArgumentException?e.getMessage():"保险库保存失败，请检查设备 Keystore"); }
        });
    }
    private void enroll() {
        stopAll(); enrollmentCancel.set(true); final AtomicBoolean cancel=new AtomicBoolean(false); enrollmentCancel=cancel;
        LinearLayout box=vertical(); box.setPadding(dp(20),dp(8),dp(20),dp(12)); TextView instruction=text("正在请求米游社登录二维码…",13,MUTED); box.addView(instruction);
        ImageView image=new ImageView(this); image.setContentDescription("米游社登录二维码"); image.setBackgroundColor(Color.WHITE); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(255)); p.topMargin=dp(12); box.addView(image,p);
        final Bitmap[] qrBitmap=new Bitmap[1];
        final AtomicReference<JSONObject> confirmedData=new AtomicReference<>();
        final AtomicBoolean authorizing=new AtomicBoolean(false);
        Button save=button("保存二维码至相册",GREEN,() -> { if(qrBitmap[0]!=null) saveQr(qrBitmap[0]); }); box.addView(save); save.setEnabled(false);
        Button retry=button("重试游戏授权",PINK,() -> {}); box.addView(retry); retry.setVisibility(View.GONE);
        ScrollView scroll=new ScrollView(this); scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("米游社社区扫码登录").setView(scroll).setNegativeButton("关闭",null).create(); enrollmentDialog=dialog;
        dialog.setOnDismissListener(d -> { cancel.set(true); confirmedData.set(null); if(enrollmentDialog==dialog) enrollmentDialog=null; }); dialog.show();
        retry.setOnClickListener(v -> finishEnrollment(confirmedData,cancel,authorizing,instruction,retry,dialog));
        io.execute(() -> {
            String stage="生成米游社登录二维码";
            try {
                ApiClient.Enrollment enrollment=engine.api.fetchEnrollment();
                if(cancel.get()) return;
                Bitmap qr=qrImage(enrollment.url,720); main.post(() -> {
                    if(cancel.get()) return; qrBitmap[0]=qr; image.setImageBitmap(qr); save.setEnabled(true);
                    instruction.setText("这是米游社社区登录二维码，不是游戏二维码。请使用已登录自己账号的米游社扫一扫并确认。确认后，社区凭据 SToken 和 MID 将使用 Android Keystore 加密保存在本机；不会兑换或冒充 game_token。仅在你另行启动授权游戏扫码会话后，才执行游戏登录操作。可随时在账号页删除本机凭据。");
                });
                stage="查询米游社登录状态";
                long end=SystemClock.elapsedRealtime()+180000;
                while(!cancel.get() && SystemClock.elapsedRealtime()<end) {
                    JSONObject data=engine.api.queryEnrollment(enrollment.ticket); String stat=ApiClient.enrollmentStatus(data);
                    if("Confirmed".equals(stat)) {
                        if(cancel.get()) return;
                        confirmedData.set(data);
                        finishEnrollment(confirmedData,cancel,authorizing,instruction,retry,dialog);
                        return;
                    }
                    if("Scanned".equals(stat)) main.post(() -> { if(!cancel.get()) instruction.setText("米游社已扫码，请在官方 App 确认本次登录。"); });
                    Thread.sleep(1200);
                }
                if(!cancel.get()) main.post(() -> { if(!cancel.get()) instruction.setText("米游社二维码已超时，请关闭后重新添加。"); });
            } catch(Exception e) {
                String reason=e instanceof IllegalStateException?e.getMessage():"网络或响应格式异常，请在官方应用检查后重试";
                final String failure=stage+"失败："+reason;
                if(!cancel.get()) { engine.log(failure); main.post(() -> { if(!isDestroyed() && !cancel.get()) instruction.setText(failure); }); }
            }
        });
    }
    private void finishEnrollment(AtomicReference<JSONObject> confirmedData,AtomicBoolean cancel,AtomicBoolean authorizing,
                                  TextView instruction,Button retry,AlertDialog dialog) {
        final JSONObject data=confirmedData.get();
        if(data==null || cancel.get() || isDestroyed() || !authorizing.compareAndSet(false,true)) return;
        main.post(() -> { if(!cancel.get()) { retry.setEnabled(false); instruction.setText("米游社已确认，正在加密保存社区账号…"); } });
        io.execute(() -> {
            try {
                if(cancel.get()) return;
                AccountStore.Account account=engine.api.completeEnrollment(data);
                if(cancel.get()) return;
                engine.accounts.add(account);
                confirmedData.set(null);
                main.post(() -> { if(!isDestroyed() && !cancel.get()) { refreshAccounts(); dialog.dismiss(); message("米游社社区登录成功，社区凭据已加密保存；尚未登录任何游戏"); } });
            } catch(Exception e) {
                String reason=e instanceof IllegalStateException?e.getMessage():"本机加密保存或响应格式异常";
                final String failure="保存米游社社区账号失败："+reason;
                if(!cancel.get()) {
                    engine.log(failure);
                    main.post(() -> { if(!isDestroyed() && !cancel.get()) {
                        instruction.setText(failure+"\n临时响应仅在本窗口内存中。需要验证时请在官方应用处理；不会切换接口或兑换游戏令牌。");
                        retry.setText("重试本机加密保存"); retry.setVisibility(View.VISIBLE);
                    } });
                }
            } finally {
                authorizing.set(false);
                main.post(() -> { if(!isDestroyed() && !cancel.get()) retry.setEnabled(confirmedData.get()!=null); });
            }
        });
    }
    private Bitmap qrImage(String raw,int size) throws Exception {
        BitMatrix bits=new QRCodeWriter().encode(raw,BarcodeFormat.QR_CODE,size,size); int[] colors=new int[size*size];
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) colors[y*size+x]=bits.get(x,y)?Color.BLACK:Color.WHITE;
        return Bitmap.createBitmap(colors,size,size,Bitmap.Config.ARGB_8888);
    }
    private void saveQr(Bitmap bitmap) {
        if(Build.VERSION.SDK_INT<29) { message("Android 8 / 9 请直接截屏，再到官方 App 扫描相册"); return; }
        Uri uri=null;
        try {
            ContentValues v=new ContentValues(); v.put(MediaStore.Images.Media.DISPLAY_NAME,"mhy-login-"+System.currentTimeMillis()+".png"); v.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/MHYScanner"); v.put(MediaStore.Images.Media.IS_PENDING,1);
            uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v); if(uri==null) throw new IllegalStateException();
            try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)) { if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IllegalStateException(); }
            ContentValues done=new ContentValues(); done.put(MediaStore.Images.Media.IS_PENDING,0); getContentResolver().update(uri,done,null,null);
            message("已保存至 Pictures/MHYScanner；登录后可删除这张二维码图片");
        } catch(Exception e) { if(uri!=null) getContentResolver().delete(uri,null,null); message("保存失败，可截屏使用"); }
    }
    private void localTest() {
        io.execute(() -> {
            try {
                String raw="https://user.mihoyo.com/qr_code_in_game.html?app_id=8&biz_key=hkrpg_cn&ticket=LOCALTESTABCDEFGHIJKLMNOP&expire="+(System.currentTimeMillis()/1000+300);
                Bitmap b=qrImage(raw,640); List<String> decoded=new QrDecoder().decode(b,false); b.recycle();
                boolean okay=decoded.stream().anyMatch(s -> QrPayload.parse(s)!=null);
                main.post(() -> { engine.log(okay?"本地自检通过：生成 → 解码 → 游戏匹配；未发送任何网络请求":"本地解码自检失败"); message(okay?"本地自检通过（不验证线上登录）":"自检失败"); });
            } catch(Exception e) { main.post(() -> message("自检失败")); }
        });
    }
    private void about() {
        String legal;
        try(InputStream in=getAssets().open("LICENSE"); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            byte[] b=new byte[4096]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); legal=out.toString("UTF-8");
        } catch(Exception e) { legal="GPL-3.0 · https://www.gnu.org/licenses/gpl-3.0.html"; }
        ScrollView s=new ScrollView(this); TextView t=text("拾光扫码 0.1.8\n参考 DSVVA/MHY_Scanner 与 Theresa-0328/MHY_Scanner，Android 重写版，2026-10-05。\n\n非官方；无担保。GPL-3.0，允许按许可证修改与再分发。源码须与 APK 一同提供。\n依赖：ZXing / ZXing-C++（Apache-2.0）、AndroidX Media3（Apache-2.0）及传递依赖。详见项目 THIRD_PARTY_NOTICES.md。\n\n"+legal,11,MUTED); t.setPadding(dp(20),dp(12),dp(20),dp(12)); s.addView(t);
        new AlertDialog.Builder(this).setTitle("关于与许可证").setView(s).setNeutralButton("依赖许可",(d,w) -> thirdParty()).setPositiveButton("关闭",null).show();
    }
    private void thirdParty() {
        StringBuilder legal=new StringBuilder();
        for(String file:new String[]{"THIRD_PARTY_NOTICES.txt","Apache-2.0.txt"}) {
            try(InputStream in=getAssets().open(file); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
                byte[] b=new byte[4096]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); legal.append(out.toString("UTF-8")).append("\n\n");
            } catch(Exception ignored) { }
        }
        ScrollView scroll=new ScrollView(this); TextView t=text(legal.toString(),11,MUTED); t.setPadding(dp(20),dp(12),dp(20),dp(12)); scroll.addView(t);
        new AlertDialog.Builder(this).setTitle("依赖许可与归属").setView(scroll).setPositiveButton("关闭",null).show();
    }
    private void message(String message) { if(!isDestroyed()) Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    @Override protected void onResume() { super.onResume(); resumed=true; engine.observe(observer); refreshAccounts(); main.post(refreshTick); }
    @Override protected void onPause() {
        resumed=false; engine.unobserve(observer); main.removeCallbacks(refreshTick);
        if(player!=null) { streamRequest++; releasePlayer(); if(!engine.hasPending()) engine.stop(); engine.log("直播识码已随页面离开暂停；屏幕监视不受影响"); }
        super.onPause();
    }
    @Override protected void onDestroy() { enrollmentCancel.set(true); if(enrollmentDialog!=null) enrollmentDialog.dismiss(); releasePlayer(); io.shutdownNow(); super.onDestroy(); }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); root.requestApplyInsets(); }
}
