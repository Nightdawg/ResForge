package resforge.layers;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Extract or replace the optional <em>alpha mask</em> (part tag 4) of a
 * {@code tex} layer, leaving the color image and every other part byte-for-byte
 * unchanged. The mask's {@code int32} length is recomputed on replace, so a
 * mask of any size repacks correctly — the same approach the color {@code tex}
 * codec uses for the color image.
 *
 * <p>Replacement media is format-checked (must be a recognised encoded image), so
 * a wrong file type is rejected rather than written.
 */
public final class TexMaskCodec {
    private TexMaskCodec() {}

    /** The mask's raw encoded image bytes, or {@code null} if the layer has none. */
    public static byte[] mask(byte[] data) {
        TexInfo ti = TexInfo.parse(data);
        if(!ti.maskFound)
            return null;
        return Arrays.copyOfRange(data, ti.maskOffset, ti.maskOffset + ti.maskLen);
    }

    /** True if {@code data} is a tex layer carrying an alpha mask. */
    public static boolean hasMask(byte[] data) {
        return TexInfo.parse(data).maskFound;
    }

    /**
     * Returns a new tex payload with the alpha mask replaced by {@code newMask}
     * (its int32 length recomputed). Throws if the layer has no mask or the
     * replacement isn't a recognised image.
     */
    public static byte[] replaceMask(byte[] data, byte[] newMask) {
        TexInfo ti = TexInfo.parse(data);
        if(!ti.maskFound)
            throw new IllegalStateException("this tex layer has no alpha mask to replace");
        if(ImageMagic.formatAt(newMask, 0) == null)
            throw new IllegalArgumentException(
                    "replacement is not a recognised image (PNG/JPEG/GIF/BMP)");
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                data.length - ti.maskLen + newMask.length);
        // Everything up to (and including) the mask part's tag byte — the int32
        // length field begins at maskLenFieldPos.
        out.write(data, 0, ti.maskLenFieldPos);
        int len = newMask.length;
        out.write(len & 0xff);
        out.write((len >> 8) & 0xff);
        out.write((len >> 16) & 0xff);
        out.write((len >> 24) & 0xff);
        out.write(newMask, 0, newMask.length);
        int tail = ti.maskOffset + ti.maskLen;          // any parts after the old mask
        out.write(data, tail, data.length - tail);
        return out.toByteArray();
    }
}
