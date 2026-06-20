package resforge.layers;

import resforge.io.MessageReader;
import resforge.io.MessageWriter;

import java.util.Arrays;

/**
 * Reads and re-encodes the fixed header fields of a {@code tex} layer so the GUI
 * can edit a texture's id, atlas offset, or declared size without disturbing the
 * embedded image and the trailing parts (filters / alpha mask).
 *
 * <p>Header (from haven.TexR.Encoded):
 * <pre>
 *   int16  id
 *   uint16 off.x, off.y
 *   uint16 sz.x,  sz.y
 *   &lt;parts: the encoded image and any filters/mask, kept verbatim&gt;
 * </pre>
 *
 * Only the 10-byte fixed prefix is rewritten; everything after it (the parts
 * stream) is preserved verbatim, so an unchanged header round-trips byte-exactly.
 * Editing is offered only when that self-check passes ({@link #editable}).
 */
public final class TexHeaderCodec {
    public int id, offX, offY, szX, szY;
    public boolean editable;

    private byte[] rest = new byte[0];   // verbatim bytes after the fixed header

    public static TexHeaderCodec parse(byte[] payload) {
        TexHeaderCodec h = new TexHeaderCodec();
        try {
            if(payload.length < 10)
                return h;
            MessageReader in = new MessageReader(payload);
            h.id = in.int16();
            h.offX = in.uint16();
            h.offY = in.uint16();
            h.szX = in.uint16();
            h.szY = in.uint16();
            h.rest = Arrays.copyOfRange(payload, in.position(), payload.length);
            h.editable = Arrays.equals(payload, h.encode());
        } catch(RuntimeException e) {
            h.editable = false;
        }
        return h;
    }

    /** Re-encodes the fixed header (current field values) + the verbatim parts. */
    public byte[] encode() {
        MessageWriter out = new MessageWriter();
        out.int16(id);
        out.uint16(offX);
        out.uint16(offY);
        out.uint16(szX);
        out.uint16(szY);
        out.bytes(rest);
        return out.toByteArray();
    }

    /** Applies new field values and returns the new payload, validating ranges. */
    public byte[] encodeWith(int id, int offX, int offY, int szX, int szY) {
        requireI16("id", id);
        requireU16("off.x", offX);
        requireU16("off.y", offY);
        requireU16("sz.x", szX);
        requireU16("sz.y", szY);
        this.id = id;
        this.offX = offX;
        this.offY = offY;
        this.szX = szX;
        this.szY = szY;
        return encode();
    }

    private static void requireI16(String name, int v) {
        if(v < -32768 || v > 32767)
            throw new IllegalArgumentException(name + " must be in [-32768, 32767]");
    }

    private static void requireU16(String name, int v) {
        if(v < 0 || v > 65535)
            throw new IllegalArgumentException(name + " must be in [0, 65535]");
    }
}
