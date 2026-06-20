package resforge.layers;

import resforge.io.MessageReader;

/**
 * Parses a {@code tex} (texture) layer header and locates the embedded color
 * image so the layer can be split into a verbatim header part, a replaceable
 * image part, and a verbatim trailing part.
 *
 * Tex layer format (from haven.TexR.Encoded):
 * <pre>
 *   int16  id
 *   uint16 off.x, off.y
 *   uint16 sz.x,  sz.y
 *   parts, repeated until end-of-message:
 *     uint8 tag                      fl = (tag &amp; 0xc0) >> 6 ; t = tag &amp; 0x3f
 *       fl == 0: inline part read straight from the stream
 *         t == 0: color image  -> int32 len, then len encoded-image bytes
 *         t == 4: alpha mask    -> int32 len, then len encoded-image bytes
 *         t == 1|2|3: filter    -> 1 byte
 *         t == 5: linear flag   -> 0 bytes
 *       fl == 1: length-prefixed sub-message, uint8 length
 *       fl == 2: length-prefixed sub-message, uint8 + int32 length
 * </pre>
 *
 * Only the first color image (t==0, fl==0 — the form used by real resources) is
 * exposed; everything else (filters, mask, length-prefixed parts) is preserved
 * verbatim. If the color image cannot be located cleanly, {@link #found} is
 * false and the caller should fall back to a raw passthrough.
 */
public class TexInfo {
    public boolean recognized;     // header parsed
    public boolean found;          // inline color image located
    public int id;
    public int offX, offY, szX, szY;
    public int lenFieldPos = -1;   // index of the color image's int32 length field
    public int imageOffset = -1;   // index where the color image bytes begin
    public int imageLen = -1;      // color image byte length
    public String imageFormat;     // "png", "jpg", ... or null

    public static TexInfo parse(byte[] data) {
        TexInfo t = new TexInfo();
        try {
            MessageReader in = new MessageReader(data);
            t.id = in.int16();
            t.offX = in.uint16();
            t.offY = in.uint16();
            t.szX = in.uint16();
            t.szY = in.uint16();
            t.recognized = true;

            while(!in.eom()) {
                int tag = in.uint8();
                int fl = (tag & 0xc0) >> 6;
                int pt = tag & 0x3f;
                if(fl == 0) {
                    if(pt == 0 || pt == 4) {
                        int lenPos = in.position();
                        int len = in.int32();
                        int imgStart = in.position();
                        if(len < 0 || (long) imgStart + len > data.length)
                            return fail(t);
                        if(pt == 0) {
                            t.lenFieldPos = lenPos;
                            t.imageOffset = imgStart;
                            t.imageLen = len;
                            t.imageFormat = ImageMagic.formatAt(data, imgStart);
                            t.found = (t.imageFormat != null);
                            return t;
                        }
                        in.skip(len);
                    } else if(pt == 1 || pt == 2 || pt == 3) {
                        in.skip(1);
                    } else if(pt == 5) {
                        /* no payload */
                    } else {
                        return fail(t);
                    }
                } else if(fl == 1) {
                    in.skip(in.uint8());
                } else if(fl == 2) {
                    in.skip(1);
                    int n = in.int32();
                    if(n < 0 || (long) in.position() + n > data.length)
                        return fail(t);
                    in.skip(n);
                } else {
                    return fail(t);
                }
            }
        } catch(RuntimeException e) {
            return fail(t);
        }
        return t;
    }

    private static TexInfo fail(TexInfo t) {
        t.found = false;
        t.lenFieldPos = -1;
        t.imageOffset = -1;
        t.imageLen = -1;
        return t;
    }
}
