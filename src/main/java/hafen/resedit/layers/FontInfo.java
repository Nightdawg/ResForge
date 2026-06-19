package hafen.resedit.layers;

import hafen.resedit.io.MessageReader;

/**
 * Parses a {@code font} layer header and locates the embedded font program so
 * the layer can be split into a verbatim header part and a replaceable font
 * file (TrueType/OpenType).
 *
 * Font layer format (from haven.Resource.Font):
 * <pre>
 *   uint8 ver   (== 1)
 *   uint8 type  (== 0, TrueType)
 *   &lt;font program bytes, read to end of payload&gt;
 * </pre>
 *
 * The font runs to the end of the payload (like {@code image}/{@code audio2}),
 * so it is a plain header/tail cut. The embedded program is validated by its
 * sfnt signature.
 */
public class FontInfo {
    public boolean recognized;
    public int ver, type;
    public int fontOffset = -1;   // byte index where the font program begins
    public String format;         // "ttf", "otf", "ttc", or null

    public static FontInfo parse(byte[] payload) {
        FontInfo fi = new FontInfo();
        try {
            MessageReader in = new MessageReader(payload);
            fi.ver = in.uint8();
            if(fi.ver == 1) {
                fi.type = in.uint8();
                if(fi.type == 0) {
                    fi.recognized = true;
                    fi.fontOffset = in.position();
                }
            }
        } catch(RuntimeException e) {
            fi.recognized = false;
        }
        fi.format = fontFormatAt(payload, fi.fontOffset);
        if(fi.format == null)
            fi.fontOffset = -1;
        return fi;
    }

    private static String fontFormatAt(byte[] b, int i) {
        if(i < 0 || i + 4 > b.length)
            return null;
        int b0 = b[i] & 0xff, b1 = b[i + 1] & 0xff, b2 = b[i + 2] & 0xff, b3 = b[i + 3] & 0xff;
        if(b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00)
            return "ttf";                         // sfnt 1.0 (TrueType)
        if(b0 == 'O' && b1 == 'T' && b2 == 'T' && b3 == 'O')
            return "otf";                         // OpenType with CFF
        if(b0 == 't' && b1 == 'r' && b2 == 'u' && b3 == 'e')
            return "ttf";                         // legacy TrueType
        if(b0 == 't' && b1 == 't' && b2 == 'c' && b3 == 'f')
            return "ttc";                         // TrueType collection
        return null;
    }
}
