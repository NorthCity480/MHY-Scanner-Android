import javax.imageio.ImageIO;
import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.File;
import java.util.*;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.partner.mhyscanner.QrPayload;
/** Developer-only: decode upstream sample images locally; never sends their tickets. */
class DecodeFixtures {
    public static void main(String[] files) throws Exception {
        for (String file : files) {
            Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, true);
            long start = System.nanoTime();
            BinaryBitmap image = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(ImageIO.read(new File(file)))));
            Result[] results = new QRCodeMultiReader().decodeMultiple(image, hints);
            QrPayload matched = null;
            for (Result result : results) {
                QrPayload parsed = QrPayload.parse(result.getText());
                if (parsed != null) matched = parsed;
            }
            if (matched == null) throw new AssertionError("No supported game QR in " + file);
            String name = new File(file).getName();
            QrPayload.Game expected = name.startsWith("bh3") ? QrPayload.Game.BH3 : name.startsWith("hk4e") ? QrPayload.Game.GENSHIN : name.startsWith("hkrpg") ? QrPayload.Game.STAR_RAIL : QrPayload.Game.ZZZ;
            if (matched.game != expected) throw new AssertionError("Wrong game in " + file);
            System.out.println(name + " -> " + matched.game.label + " PASS (" + (System.nanoTime()-start)/1000000 + " ms, no network)");
        }
    }
}
