package resforge.layers;

import resforge.io.MessageReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only decoder for the {@code tileset2} layer, mirroring
 * {@code haven.Tileset}. The payload is a sequence of parts until end of message,
 * each introduced by a {@code uint8} part tag:
 *
 * <ul>
 *   <li><b>0</b> — {@code string tn} (the tiler name, e.g. {@code "gnd"},
 *       {@code "trn-r"}), then a {@code tto} argument list ({@code ta});</li>
 *   <li><b>1</b> — {@code uint16 flnum}, {@code uint16 flavprob}, then {@code flnum}
 *       × [{@code string res}, {@code uint16 ver}, {@code uint8 weight}]: the
 *       flavor objects scattered on this terrain, each a resource reference;</li>
 *   <li><b>2</b> — {@code int8 n}, then {@code n} × {@code string tag}: the tile
 *       tags.</li>
 * </ul>
 *
 * <p>The decoder surfaces the tiler name, tags, and flavor references (so the
 * dependency / {@code refs} report can include them); the layer itself stays
 * raw/lossless.
 */
public final class TilesetInfo {
    public static final class Flavor {
        public final String res;
        public final int ver;
        public final int weight;

        Flavor(String res, int ver, int weight) {
            this.res = res;
            this.ver = ver;
            this.weight = weight;
        }
    }

    public boolean recognized;
    public boolean reachedEnd;
    public String tilerName;
    public int flavprob;
    public final List<Flavor> flavors = new ArrayList<>();
    public final List<String> tags = new ArrayList<>();

    public static TilesetInfo parse(byte[] payload) {
        TilesetInfo ts = new TilesetInfo();
        try {
            MessageReader in = new MessageReader(payload);
            while(!in.eom()) {
                int p = in.uint8();
                switch(p) {
                    case 0:
                        ts.tilerName = in.string();
                        TtoSkip.skipList(in);            // ta argument list
                        break;
                    case 1: {
                        int flnum = in.uint16();
                        ts.flavprob = in.uint16();
                        for(int i = 0; i < flnum; i++) {
                            String res = in.string();
                            int ver = in.uint16();
                            int w = in.uint8();
                            ts.flavors.add(new Flavor(res, ver, w));
                        }
                        break;
                    }
                    case 2: {
                        int n = in.int8();
                        for(int i = 0; i < n; i++)
                            ts.tags.add(in.string());
                        break;
                    }
                    default:
                        throw new IllegalStateException("unknown tileset2 part " + p);
                }
            }
            ts.recognized = true;
            ts.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            /* tolerant: keep whatever decoded; layer stays raw/lossless */
        }
        return ts;
    }

    /** External resources this tileset references (its flavor objects). */
    public List<RLinkInfo.Ref> references() {
        List<RLinkInfo.Ref> refs = new ArrayList<>();
        for(Flavor f : flavors)
            if(f.res != null && !f.res.isEmpty())
                refs.add(new RLinkInfo.Ref(f.res, f.ver));
        return refs;
    }
}
