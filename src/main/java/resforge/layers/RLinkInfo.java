package resforge.layers;

import resforge.io.MessageReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only decoder for the {@code rlink} layer (from haven.Resource): resource
 * links / redirects. The payload is a {@code uint8} version followed by, until
 * end of message, a sequence of link entries. Each observed entry is:
 *
 * <ul>
 *   <li>{@code uint16 id} — the local id this link is bound to;</li>
 *   <li>{@code uint8 type};</li>
 *   <li>for {@code type == 3}: {@code string res}, {@code uint16 ver} (the
 *       linked resource and its version), then a {@code tto} value list (the
 *       link's specification / arguments) read until a {@code 0} tag or end of
 *       message.</li>
 * </ul>
 *
 * <p>The specification commonly nests further resource references (tto tag
 * {@code 34}); those are collected too. This lets a modder see which other
 * resources a {@code .res} links to and how it parameterises them. The parser
 * is tolerant: an unrecognised entry type stops decoding without throwing, and
 * whatever was decoded so far is still reported. The layer stays raw/lossless.
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
        public final int id;
        public final String res;
        public final int ver;
        public final String spec;           // rendered tto specification, or null
        public final List<Ref> refs;        // resource references nested in the spec

        Link(int id, String res, int ver, String spec, List<Ref> refs) {
            this.id = id;
            this.res = res;
            this.ver = ver;
            this.spec = spec;
            this.refs = refs;
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
            ri.ver = in.uint8();
            while(!in.eom()) {
                int id = in.uint16();
                int t = in.uint8();
                if(t == 3) {
                    String res = in.string();
                    int ver = in.uint16();
                    List<Ref> refs = new ArrayList<>();
                    List<Object> spec = new ArrayList<>();
                    while(!in.eom()) {
                        int tag = in.uint8();
                        if(tag == 0)
                            break;
                        spec.add(readValue(in, tag, refs));
                    }
                    ri.links.add(new Link(id, res, ver, spec.isEmpty() ? null : render(spec), refs));
                } else {
                    throw new IllegalStateException("unknown rlink entry type " + t);
                }
            }
            ri.recognized = true;
            ri.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            /* tolerant: keep whatever links decoded before the unknown entry */
        }
        return ri;
    }

    /** All resources referenced by this layer: each link target plus nested refs. */
    public List<Ref> references() {
        List<Ref> all = new ArrayList<>();
        for(Link l : links) {
            all.add(new Ref(l.res, l.ver));
            all.addAll(l.refs);
        }
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
