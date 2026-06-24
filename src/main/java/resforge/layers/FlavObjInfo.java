package resforge.layers;

import resforge.io.MessageReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only decoder for the {@code flavobj} layer, mirroring
 * {@code haven.Tileset.Flavor.Res}:
 *
 * <pre>
 *   uint8  ver        == 1
 *   string res        the flavor's resource path (a sprite or ambient sound)
 *   uint16 ver        its version
 *   &lt;tto list&gt;        the flavor factory's arguments (e.g. the spawn probability)
 * </pre>
 *
 * <p>A flavor object is a thing scattered on a terrain tile (a tuft of grass, a
 * pebble, an ambient sound source). The layer references another resource, which
 * this decoder surfaces for the dependency / {@code refs} report; the layer stays
 * raw/lossless.
 */
public final class FlavObjInfo {
    public boolean recognized;
    public boolean reachedEnd;
    public int ver;
    public String res;
    public int resVer;

    public static FlavObjInfo parse(byte[] payload) {
        FlavObjInfo fo = new FlavObjInfo();
        try {
            MessageReader in = new MessageReader(payload);
            fo.ver = in.uint8();
            if(fo.ver != 1)
                return fo;
            fo.res = in.string();
            fo.resVer = in.uint16();
            TtoSkip.skipList(in);          // factory args (probability etc.)
            fo.recognized = true;
            fo.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            /* tolerant: layer stays raw/lossless */
        }
        return fo;
    }

    /** The resource this flavor object references (its sprite/sound). */
    public List<RLinkInfo.Ref> references() {
        List<RLinkInfo.Ref> refs = new ArrayList<>();
        if(recognized && res != null && !res.isEmpty())
            refs.add(new RLinkInfo.Ref(res, resVer));
        return refs;
    }
}
