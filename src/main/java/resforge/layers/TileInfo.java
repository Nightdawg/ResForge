package resforge.layers;

import resforge.io.MessageReader;

/**
 * Read-only decoder for a {@code tile} layer (a single terrain tile inside a
 * {@code tileset2} resource), mirroring {@code haven.Tileset.Tile}:
 *
 * <pre>
 *   uint8  t       tile kind: 'g' ground, 'b' border-transition, 'c' centre-transition
 *   uint8  id      transition id (1..15 for b/c; 0 for ground)
 *   uint16 w       weight (relative pick probability within its kind)
 *   &lt;image&gt;        the tile texture (a normal PNG/JPEG), to end of payload
 * </pre>
 *
 * <p>The image runs to the end of the payload (like an {@code image} layer), so a
 * replacement of any size repacks correctly. {@link #found} is true only when the
 * bytes after the 4-byte header start on a real image magic.
 */
public class TileInfo {
    public boolean recognized;     // header parsed
    public boolean found;          // embedded image located
    public char t;                 // 'g' | 'b' | 'c'
    public int id;
    public int weight;
    public int imageOffset = -1;   // index where the image bytes begin (4)
    public String imageFormat;     // "png", "jpg", ... or null

    public static TileInfo parse(byte[] data) {
        TileInfo ti = new TileInfo();
        try {
            MessageReader in = new MessageReader(data);
            ti.t = (char) in.uint8();
            ti.id = in.uint8();
            ti.weight = in.uint16();
            ti.recognized = true;
            int off = in.position();        // == 4
            ti.imageFormat = ImageMagic.formatAt(data, off);
            if(ti.imageFormat != null) {
                ti.imageOffset = off;
                ti.found = true;
            }
        } catch(RuntimeException e) {
            ti.found = false;
        }
        return ti;
    }

    /** A friendly name for the tile kind. */
    public String kindName() {
        switch(t) {
            case 'g': return "ground";
            case 'b': return "border-transition";
            case 'c': return "centre-transition";
            default:  return "tile('" + t + "')";
        }
    }
}
