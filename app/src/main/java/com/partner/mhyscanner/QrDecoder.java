package com.partner.mhyscanner;
import android.graphics.Bitmap;
import android.graphics.Rect;
import zxingcpp.BarcodeReader;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import java.util.*;

public final class QrDecoder {
    private final QRCodeMultiReader reader = new QRCodeMultiReader();
    private final Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
    private volatile BarcodeReader nativeReader;
    public QrDecoder() { this(true); }
    public QrDecoder(boolean preferNative) {
        hints.put(DecodeHintType.TRY_HARDER, true); hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        if(preferNative) try {
            BarcodeReader.Options options=new BarcodeReader.Options();
            options.setFormats(Collections.singleton(BarcodeReader.Format.QR_CODE));
            options.setTryHarder(true); options.setTryRotate(true); options.setTryInvert(true); options.setTryDownscale(true);
            nativeReader=new BarcodeReader(options);
        } catch(LinkageError | RuntimeException unavailable) { nativeReader=null; }
    }
    public String engineName() { return nativeReader==null?"ZXing Java":"ZXing-C++"; }
    public List<String> decode(Bitmap image, boolean centerOnly) {
        if(nativeReader!=null) try {
            int w=image.getWidth(),h=image.getHeight(),x=centerOnly?w/5:0,y=centerOnly?h/5:0;
            List<String> codes=new ArrayList<>();
            for(BarcodeReader.Result r:nativeReader.read(image,new Rect(x,y,w-x,h-y),0))
                if(r.getText()!=null && r.getError()==null) codes.add(r.getText());
            return codes;
        } catch(LinkageError | RuntimeException unavailable) { nativeReader=null; }
        return decodeJava(image,centerOnly);
    }
    private List<String> decodeJava(Bitmap image, boolean centerOnly) {
        int w=image.getWidth(),h=image.getHeight();
        int x=centerOnly?w/5:0,y=centerOnly?h/5:0;
        int cw=centerOnly?w-2*x:w,ch=centerOnly?h-2*y:h;
        int[] pixels=new int[cw*ch]; image.getPixels(pixels,0,cw,x,y,cw,ch);
        LuminanceSource source=new RGBLuminanceSource(cw,ch,pixels);
        List<String> result=new ArrayList<>();
        try {
            for (Result r:reader.decodeMultiple(new BinaryBitmap(new HybridBinarizer(source)),hints)) result.add(r.getText());
        } catch (NotFoundException ignored) {
            try { Result r=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)),hints); result.add(r.getText()); }
            catch (NotFoundException ignoredAgain) { }
        } finally { reader.reset(); }
        return result;
    }
}
