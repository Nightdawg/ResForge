package resforge.layers;

import resforge.io.MessageReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only decoder for the {@code codeentry} layer (from
 * haven.Resource.CodeEntry). It is the manifest that ties a resource's
 * {@code code} layers together: which class is the entry point for each named
 * role, what arguments it gets, and which other resources are on its classpath.
 *
 * <p>The payload is a sequence of tagged sections (parsed until end of message):
 * <ul>
 *   <li>{@code t==1}: pairs of {@code [string entryName, string className]}
 *       until an empty name.</li>
 *   <li>{@code t==2}: classpath entries {@code [string resName, uint16 ver]}
 *       until an empty name.</li>
 *   <li>{@code t==3}: like {@code t==1} but each pair is followed by a
 *       {@code tto} list of arguments.</li>
 *   <li>{@code t==4}: a single {@code tto} list of {@code ["ent", ...]} /
 *       {@code ["use", ...]} records (an alternative encoding).</li>
 * </ul>
 *
 * This tool neither runs nor edits code; it only reports the structure so a
 * modder can see what a resource does. The layer itself stays raw/lossless.
 */
public final class CodeEntryInfo {
    public static final class Entry {
        public final String name;
        public final String className;
        public final String args;   // rendered tto argument list, or null

        Entry(String name, String className, String args) {
            this.name = name;
            this.className = className;
            this.args = args;
        }
    }

    public static final class Dep {
        public final String name;
        public final int ver;

        Dep(String name, int ver) {
            this.name = name;
            this.ver = ver;
        }
    }

    public boolean recognized;
    public boolean reachedEnd;
    public final List<Entry> entries = new ArrayList<>();
    public final List<Dep> classpath = new ArrayList<>();

    public static CodeEntryInfo parse(byte[] payload) {
        CodeEntryInfo ce = new CodeEntryInfo();
        try {
            MessageReader in = new MessageReader(payload);
            while(!in.eom()) {
                int t = in.uint8();
                if(t == 1 || t == 3) {
                    while(true) {
                        String en = in.string();
                        String cn = in.string();
                        if(en.isEmpty())
                            break;
                        String args = (t == 3) ? render(readList(in)) : null;
                        ce.entries.add(new Entry(en, cn, args));
                    }
                } else if(t == 2) {
                    while(true) {
                        String ln = in.string();
                        if(ln.isEmpty())
                            break;
                        ce.classpath.add(new Dep(ln, in.uint16()));
                    }
                } else if(t == 4) {
                    for(Object datum : readList(in)) {
                        if(!(datum instanceof List))
                            continue;
                        List<?> d = (List<?>) datum;
                        if(d.isEmpty())
                            continue;
                        String kind = String.valueOf(d.get(0));
                        if(kind.equals("ent") && d.size() >= 3) {
                            String args = d.size() > 3 ? render(d.subList(3, d.size())) : null;
                            ce.entries.add(new Entry(String.valueOf(d.get(1)), String.valueOf(d.get(2)), args));
                        } else if(kind.equals("use")) {
                            for(int o = 1; o < d.size(); o++)
                                ce.classpath.add(new Dep(String.valueOf(d.get(o)), -1));
                        }
                    }
                } else {
                    throw new IllegalStateException("unknown codeentry section tag " + t);
                }
            }
            ce.recognized = true;
            ce.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            ce.recognized = false;
        }
        return ce;
    }

    /* ----- a small tto reader, just enough to render codeentry arguments ----- */

    private static List<Object> readList(MessageReader in) {
        List<Object> list = new ArrayList<>();
        int t;
        while((t = in.uint8()) != 0)
            list.add(readValue(in, t));
        return list;
    }

    private static Object readValue(MessageReader in, int t) {
        switch(t) {
            case 1:  return (long) in.int32();
            case 2:  return in.string();
            case 4:  return (long) in.uint8();
            case 5:  return (long) in.uint16();
            case 8:  return readList(in);
            case 9:  return (long) in.int8();
            case 10: return (long) in.int16();
            case 12: return null;
            case 15: return (double) in.float32();
            case 16: return in.float64();
            case 32: return readMap(in);
            case 33: return in.int64();
            case 34: { String nm = in.string(); int ver = in.uint16(); return "res(" + nm + "@v" + ver + ")"; }
            case 35: return "resid:" + in.uint16();
            default: throw new IllegalStateException("unhandled tto tag " + t);
        }
    }

    private static Map<String, Object> readMap(MessageReader in) {
        Map<String, Object> m = new LinkedHashMap<>();
        int t;
        while((t = in.uint8()) != 0) {
            Object k = readValue(in, t);
            m.put(String.valueOf(k), readValue(in, in.uint8()));
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
