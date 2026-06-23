package resforge.layers;

import resforge.io.MessageReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only decoder for the {@code rlink} (render-link) layer, faithfully
 * mirroring {@code haven.RenderLink.Res}. Each {@code rlink} layer holds exactly
 * <strong>one</strong> link (multiple links = multiple layers).
 *
 * <p>Header: {@code uint8 lver}. If {@code lver < 3} the version byte is itself
 * the link <em>type</em> and the id is {@code -1}; if {@code lver} is 3 or 4 an
 * {@code int16 id} and {@code uint8 type} follow, and for {@code lver >= 4} a
 * {@code string}-keyed {@code tto} metadata map (terminated by an empty key).
 * Then a type-specific body:
 *
 * <ul>
 *   <li><b>0 MeshMat</b> — {@code string mesh, uint16 ver, int16 meshid,
 *       string mat, uint16 ver, int16 matid} (two resource references);</li>
 *   <li><b>1 AmbientLink</b> — {@code string res, uint16 ver};</li>
 *   <li><b>2 Collect</b> — {@code string res, uint16 ver, int16 meshid,
 *       [int16 meshmask]};</li>
 *   <li><b>3 Parameters</b> — {@code string res, uint16 ver}, then a {@code tto}
 *       argument list (which may nest further {@code res()} references);</li>
 *   <li><b>4 ResSprite</b> — {@code string res, uint16 ver}.</li>
 * </ul>
 *
 * <p>This decoder surfaces which other resources a link points at (so the
 * dependency view and the aggregated {@code refs} report are complete across all
 * link types) — the layer itself stays raw/lossless. An empty resource name means
 * the link refers back to its own resource and contributes no external reference.
 */
public final class RLinkInfo {
    public static final class Ref {
        public final String name;
        public final int ver;       // -1 if unknown

        Ref(String name, int ver) {
            this.name = name;
            this.ver = ver;
        }
    }

    public static final class Link {
        public final int id;                 // -1 for old (lver < 3) links
        public final int type;
        public final String typeName;
        public final String res;             // primary referenced resource ("" = self)
        public final int ver;                // primary version (-1 if none)
        public final String spec;            // human description of extra params, or null
        public final List<Ref> refs;         // every resource this link references
        public final Map<String, Object> info;  // lver>=4 metadata map (may be empty)

        Link(int id, int type, String typeName, String res, int ver, String spec,
             List<Ref> refs, Map<String, Object> info) {
            this.id = id;
            this.type = type;
            this.typeName = typeName;
            this.res = res;
            this.ver = ver;
            this.spec = spec;
            this.refs = refs;
            this.info = info;
        }
    }

    public boolean recognized;
    public boolean reachedEnd;
    public int ver;
    public final List<Link> links = new ArrayList<>();

    public static RLinkInfo parse(byte[] payload) {
        RLinkInfo ri = new RLinkInfo();
        try {
            MessageReader in = new MessageReader(payload);
            int lver = in.uint8();
            ri.ver = lver;

            int id, type;
            List<Ref> refs = new ArrayList<>();
            Map<String, Object> info = new LinkedHashMap<>();
            if(lver < 3) {
                type = lver;
                id = -1;
            } else if(lver <= 4) {
                id = in.int16();
                type = in.uint8();
                if(lver >= 4) {
                    while(true) {
                        String key = in.string();
                        if(key.isEmpty())
                            break;
                        info.put(key, readValue(in, in.uint8(), refs));
                    }
                }
            } else {
                throw new IllegalStateException("unknown rlink version " + lver);
            }

            ri.links.add(parseBody(in, id, type, refs, info));
            ri.recognized = true;
            ri.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            /* tolerant: a malformed/unknown link leaves the layer raw and contributes
               no references, rather than throwing. */
        }
        return ri;
    }

    private static Link parseBody(MessageReader in, int id, int type, List<Ref> refs,
                                  Map<String, Object> info) {
        switch(type) {
            case 0: {   // MeshMat
                String mesh = in.string();
                int mver = in.uint16();
                int meshid = in.int16();
                String mat = in.string();
                int matver = in.uint16();
                int matid = in.int16();
                addRef(refs, mesh, mver);
                addRef(refs, mat, matver);
                String spec = "material " + named(mat, matver) + " #" + matid + " on mesh #" + meshid;
                return new Link(id, type, "mesh+material", mesh, mver, spec, refs, info);
            }
            case 1: {   // AmbientLink
                String nm = in.string();
                int ver = in.uint16();
                addRef(refs, nm, ver);
                return new Link(id, type, "ambient", nm, ver, null, refs, info);
            }
            case 2: {   // Collect
                String nm = in.string();
                int ver = in.uint16();
                int meshid = in.int16();
                int meshmask = in.eom() ? -1 : in.int16();
                addRef(refs, nm, ver);
                String spec = "mesh #" + meshid + (meshmask != -1 ? " mask 0x" + Integer.toHexString(meshmask) : "");
                return new Link(id, type, "collect", nm, ver, spec, refs, info);
            }
            case 3: {   // Parameters
                String nm = in.string();
                int ver = in.uint16();
                addRef(refs, nm, ver);
                List<Object> args = new ArrayList<>();
                while(!in.eom()) {
                    int tag = in.uint8();
                    if(tag == 0)
                        break;
                    args.add(readValue(in, tag, refs));
                }
                return new Link(id, type, "parameters", nm, ver, args.isEmpty() ? null : render(args), refs, info);
            }
            case 4: {   // ResSprite
                String nm = in.string();
                int ver = in.uint16();
                addRef(refs, nm, ver);
                return new Link(id, type, "sprite", nm, ver, null, refs, info);
            }
            default:
                throw new IllegalStateException("unknown rlink type " + type);
        }
    }

    private static void addRef(List<Ref> refs, String name, int ver) {
        if(name != null && !name.isEmpty())
            refs.add(new Ref(name, ver));
    }

    private static String named(String name, int ver) {
        return name.isEmpty() ? "<self>" : name + "@v" + ver;
    }

    /** All resources referenced by this layer's link (union of every ref). */
    public List<Ref> references() {
        List<Ref> all = new ArrayList<>();
        for(Link l : links)
            all.addAll(l.refs);
        return all;
    }

    /* ----- a small tto reader, collecting nested resource references ----- */

    private static List<Object> readList(MessageReader in, List<Ref> refs) {
        List<Object> list = new ArrayList<>();
        int t;
        while((t = in.uint8()) != 0)
            list.add(readValue(in, t, refs));
        return list;
    }

    private static Object readValue(MessageReader in, int t, List<Ref> refs) {
        switch(t) {
            case 1:  return (long) in.int32();
            case 2:  return in.string();
            case 4:  return (long) in.uint8();
            case 5:  return (long) in.uint16();
            case 8:  return readList(in, refs);
            case 9:  return (long) in.int8();
            case 10: return (long) in.int16();
            case 12: return null;
            case 15: return (double) in.float32();
            case 16: return in.float64();
            case 32: return readMap(in, refs);
            case 33: return in.int64();
            case 34: {
                String nm = in.string();
                int ver = in.uint16();
                refs.add(new Ref(nm, ver));
                return "res(" + nm + "@v" + ver + ")";
            }
            case 35: return "resid:" + in.uint16();
            default: throw new IllegalStateException("unhandled tto tag " + t);
        }
    }

    private static Map<String, Object> readMap(MessageReader in, List<Ref> refs) {
        Map<String, Object> m = new LinkedHashMap<>();
        int t;
        while((t = in.uint8()) != 0) {
            Object k = readValue(in, t, refs);
            m.put(String.valueOf(k), readValue(in, in.uint8(), refs));
        }
        return m;
    }

    private static String render(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        for(int i = 0; i < list.size(); i++) {
            if(i > 0)
                sb.append(", ");
            sb.append(renderVal(list.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String renderVal(Object o) {
        if(o == null)
            return "nil";
        if(o instanceof String)
            return "\"" + o + "\"";
        if(o instanceof List)
            return render((List<?>) o);
        if(o instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for(Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if(!first)
                    sb.append(", ");
                first = false;
                sb.append(e.getKey()).append(": ").append(renderVal(e.getValue()));
            }
            return sb.append("}").toString();
        }
        return String.valueOf(o);
    }
}
