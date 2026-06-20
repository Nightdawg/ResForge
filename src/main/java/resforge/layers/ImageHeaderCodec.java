package resforge.layers;

import resforge.io.MessageReader;
import resforge.io.MessageWriter;

import java.util.Arrays;

/**
 * Reads and re-encodes the editable fields of an old-style {@code image} layer
 * header so the GUI can change an image's id, z / sub-z, draw offset, or the
 * {@code nooff} flag without disturbing the embedded picture.
 *
 * <p>Old-style header (from haven.Resource.Image, {@code ver < 128}):
 * <pre>
 *   uint8  ver            == z &amp; 0xff  (the low byte of z; must be &lt; 128)
 *   int8   z-high         z = (int8)*256 + ver
 *   int16  subz
 *   uint8  fl             bit1 obsolete, bit2 = nooff, bit3 = has-info
 *   int16  id
 *   coord  off            (int16 x, int16 y)
 *   if (fl &amp; 4): info entries…   (kept verbatim)
 *   &lt;encoded image bytes&gt;        (kept verbatim)
 * </pre>
 *
 * Editing is only offered when the header round-trips byte-exactly
 * ({@link #editable}); new-style (typed/tto) headers and anything that does not
 * reproduce are left alone (the GUI shows the fields read-only). Because the
 * info-block and image bytes are preserved verbatim and only the fixed prefix is
 * rewritten, an unchanged header re-encodes identically.
 */
public final class ImageHeaderCodec {
    public int z, subz, id, offX, offY;
    public boolean nooff;
    public int flags;            // the full fl byte; only the nooff bit is toggled on edit
    public boolean editable;     // old-style header that re-encodes byte-for-byte

    private byte[] infoBlock = new byte[0];   // verbatim info entries (fl & 4), if any
    private byte[] image = new byte[0];       // verbatim encoded image bytes

    public static ImageHeaderCodec parse(byte[] payload) {
        ImageHeaderCodec h = new ImageHeaderCodec();
        try {
            MessageReader in = new MessageReader(payload);
            int ver = in.uint8();
            if(ver >= 128)
                return h;                       // new-style typed header: not editable here
            h.z = (in.int8() * 256) + ver;
            h.subz = in.int16();
            h.flags = in.uint8();
            h.nooff = (h.flags & 2) != 0;
            h.id = in.int16();
            h.offX = in.int16();
            h.offY = in.int16();
            int infoStart = in.position();
            if((h.flags & 4) != 0) {
                while(true) {
                    String key = in.string();
                    if(key.isEmpty())
                        break;
                    int len = in.uint8();
                    if((len & 0x80) != 0)
                        len = in.int32();
                    in.skip(len);
                }
            }
            int imageStart = in.position();
            h.infoBlock = Arrays.copyOfRange(payload, infoStart, imageStart);
            h.image = Arrays.copyOfRange(payload, imageStart, payload.length);
            // Lossless guard: only editable if a straight re-encode reproduces the input.
            h.editable = Arrays.equals(payload, h.encode());
        } catch(RuntimeException e) {
            h.editable = false;
        }
        return h;
    }

    /** Re-encodes the header (current field values) + verbatim info-block + image. */
    public byte[] encode() {
        MessageWriter out = new MessageWriter();
        out.uint8(z & 0xff);                    // ver = low byte of z
        out.int8((z >> 8));                     // z-high (writer keeps the low 8 bits)
        out.int16(subz);
        out.uint8(flags);
        out.int16(id);
        out.int16(offX);
        out.int16(offY);
        out.bytes(infoBlock);
        out.bytes(image);
        return out.toByteArray();
    }

    /**
     * Applies the given field values and returns the new layer payload. Throws
     * {@link IllegalArgumentException} if a value cannot be represented in the
     * old-style header (notably {@code z}'s low byte must stay below 128, since
     * it doubles as the {@code ver} gate, and the int16 fields must fit).
     */
    public byte[] encodeWith(int z, int subz, int id, int offX, int offY, boolean nooff) {
        if((z & 0xff) >= 128)
            throw new IllegalArgumentException("z low byte must be < 128 (it is the header version gate)");
        requireI16("subz", subz);
        requireI16("id", id);
        requireI16("off.x", offX);
        requireI16("off.y", offY);
        this.z = z;
        this.subz = subz;
        this.id = id;
        this.offX = offX;
        this.offY = offY;
        this.flags = nooff ? (flags | 2) : (flags & ~2);
        return encode();
    }

    private static void requireI16(String name, int v) {
        if(v < -32768 || v > 32767)
            throw new IllegalArgumentException(name + " must be in [-32768, 32767]");
    }

    /**
     * Builds a fresh minimal old-style image layer payload wrapping the given
     * encoded image bytes (PNG/JPEG/…): z=0, sub-z=0, no flags, no info block,
     * the supplied id and offset. Used when adding a new image frame.
     */
    public static byte[] build(int id, int offX, int offY, boolean nooff, byte[] imageBytes) {
        ImageHeaderCodec h = new ImageHeaderCodec();
        h.image = imageBytes.clone();
        return h.encodeWith(0, 0, id, offX, offY, nooff);
    }
}
