package com.partner.mhyscanner;

import android.content.Context;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared live-only player configuration. No account store or login API access. */
@UnstableApi
public final class LivePlayback {
    private LivePlayback() {}
    static boolean supportsLowLatency(MediaCodecInfo info) {
        return Build.VERSION.SDK_INT>=30 && info.hardwareAccelerated && info.capabilities!=null
            && info.capabilities.isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
    }
    private static final MediaCodecSelector LIVE_CODECS=(mime,secure,tunneling) -> {
        List<MediaCodecInfo> infos=new ArrayList<>(MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunneling));
        // Stable ordering: retain Media3's ordering within each capability group and normal fallbacks.
        if(!secure && !tunneling) Collections.sort(infos,(a,b) -> Boolean.compare(supportsLowLatency(b),supportsLowLatency(a)));
        return infos;
    };
    private static final class LiveRenderers extends DefaultRenderersFactory {
        LiveRenderers(Context context) { super(context); setMediaCodecSelector(LIVE_CODECS); setEnableDecoderFallback(true); }
        @Override protected void buildVideoRenderers(Context context,int extensionMode,MediaCodecSelector selector,boolean fallback,
                Handler handler,VideoRendererEventListener listener,long joiningMs,ArrayList<Renderer> out) {
            out.add(new MediaCodecVideoRenderer(context,getCodecAdapterFactory(),selector,joiningMs,fallback,handler,listener,50) {
                @Override protected MediaCodecAdapter.Configuration getMediaCodecConfiguration(MediaCodecInfo info,Format format,MediaCrypto crypto,float rate) {
                    MediaCodecAdapter.Configuration config=super.getMediaCodecConfiguration(info,format,crypto,rate);
                    if(supportsLowLatency(info)) config.mediaFormat.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
                    return config;
                }
            });
        }
    }
    public static ExoPlayer create(Context context,LiveResolver.Stream stream,boolean optimized) {
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setConnectTimeoutMs(5000).setReadTimeoutMs(6000)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) Chrome/125.0 Mobile Safari/537.36");
        if(!stream.referer.isEmpty()) http.setDefaultRequestProperties(Collections.singletonMap("Referer",stream.referer));
        // Keep proven buffer thresholds. Check loader backpressure more frequently instead of overfetching 1 MiB.
        DefaultLoadControl.Builder load=new DefaultLoadControl.Builder();
        if(optimized && "flv".equals(stream.format)) load.setBufferDurationsMs(150,350,100,150);
        else load.setBufferDurationsMs(150,650,100,150);
        DefaultLoadControl buffer=load.setBackBuffer(0,false).setPrioritizeTimeOverSizeThresholds(true).build();
        ExoPlayer.Builder builder=new ExoPlayer.Builder(context).setMediaSourceFactory(new DefaultMediaSourceFactory(context).setDataSourceFactory(http)).setLoadControl(buffer);
        if(optimized) builder.setRenderersFactory(new LiveRenderers(context));
        ExoPlayer player=builder.build();
        player.setVolume(0f);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,true).build());
        MediaItem.Builder item=new MediaItem.Builder().setUri(stream.url);
        if("hls".equals(stream.format)) item.setMimeType(MimeTypes.APPLICATION_M3U8).setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(2000).setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.08f).build());
        if(optimized && "flv".equals(stream.format)) player.setMediaSource(new ProgressiveMediaSource.Factory(http)
            .setContinueLoadingCheckIntervalBytes(64*1024).createMediaSource(item.build()));
        else player.setMediaItem(item.build());
        return player;
    }
}
