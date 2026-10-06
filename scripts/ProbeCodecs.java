import android.media.MediaCodecList;
import android.media.MediaCodecInfo;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public device capabilities only. No account, QR, stream URI or other app access. */
public final class ProbeCodecs {
    public static void main(String[] args) throws Exception {
        JSONObject result=new JSONObject().put("model",Build.MODEL).put("sdk",Build.VERSION.SDK_INT);
        JSONArray codecs=new JSONArray();
        for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if(info.isEncoder()) continue;
            for(String type:info.getSupportedTypes()) {
                if(!type.equals("video/avc") && !type.equals("video/hevc")) continue;
                MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType(type);
                JSONObject c=new JSONObject().put("name",info.getName()).put("mime",type);
                if(Build.VERSION.SDK_INT>=29) c.put("hardware",info.isHardwareAccelerated());
                c.put("low_latency",Build.VERSION.SDK_INT>=30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency));
                codecs.put(c);
            }
        }
        result.put("codecs",codecs); System.out.println(result);
    }
}
