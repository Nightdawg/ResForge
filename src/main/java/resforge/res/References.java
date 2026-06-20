package resforge.res;

import resforge.layers.CodeEntryInfo;
import resforge.layers.DepsInfo;
import resforge.layers.Mat2Codec;
import resforge.layers.RLinkInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates the external resources a {@code .res} references, gathered from
 * every layer type that names other resources, into one deduplicated report.
 * It is read-only and reuses the existing layer decoders; it never alters bytes.
 *
 * <p>Sources scanned:
 * <ul>
 *   <li>{@code deps} — the explicit dependency list ({@code name @ ver});</li>
 *   <li>{@code rlink} — link targets and any {@code res()} nested in their specs;</li>
 *   <li>{@code codeentry} — classpath dependencies of server-authored code;</li>
 *   <li>{@code mat2} — external material/texture links: string command values that
 *       look like resource paths (contain {@code '/'}), e.g. {@code mlink} and
 *       external {@code tex}/{@code otex}. Mode/order names ({@code "def"},
 *       {@code "eye"}, …) have no slash and are ignored.</li>
 * </ul>
 *
 * <p>{@code anim} frames reference sibling {@code image} layers by local id (not
 * other resources), so animations contribute nothing here.
 */
public final class References {
    /** One referenced resource and where it came from. */
    public static final class Ref {
        public final String name;     // resource path
        public final int ver;         // -1 when the source carries no version
        public final String source;   // layer kind: deps | rlink | codeentry | mat2
        public final String detail;   // extra context (e.g. the mat2 command key), or null

        Ref(String name, int ver, String source, String detail) {
            this.name = name;
            this.ver = ver;
            this.source = source;
            this.detail = detail;
        }

        public String label() {
            return name + (ver >= 0 ? " @v" + ver : "")
                    + (detail != null ? "  (" + detail + ")" : "");
        }
    }

    /** Display/scan order of the sources. */
    private static final String[] SOURCE_ORDER = {"deps", "rlink", "codeentry", "mat2"};

    private final List<Ref> refs = new ArrayList<>();

    private References() {
    }

    /** Scans a parsed resource for all the external references it makes. */
    public static References scan(ResContainer rc) {
        References r = new References();
        for(Layer l : rc.layers) {
            switch(l.name) {
                case "deps":      r.fromDeps(l);      break;
                case "rlink":     r.fromRlink(l);     break;
                case "codeentry": r.fromCodeEntry(l); break;
                case "mat2":      r.fromMat2(l);      break;
                default: /* not a reference-bearing layer */
            }
        }
        return r;
    }

    private void fromDeps(Layer l) {
        DepsInfo di = DepsInfo.parse(l.data);
        if(di.recognized)
            for(DepsInfo.Dep d : di.deps)
                refs.add(new Ref(d.name, d.ver, "deps", null));
    }

    private void fromRlink(Layer l) {
        RLinkInfo ri = RLinkInfo.parse(l.data);
        for(RLinkInfo.Ref rr : ri.references())
            refs.add(new Ref(rr.name, rr.ver, "rlink", null));
    }

    private void fromCodeEntry(Layer l) {
        CodeEntryInfo ce = CodeEntryInfo.parse(l.data);
        if(ce.recognized)
            for(CodeEntryInfo.Dep d : ce.classpath)
                refs.add(new Ref(d.name, d.ver, "codeentry", null));
    }

    @SuppressWarnings("unchecked")
    private void fromMat2(Layer l) {
        Map<String, Object> m;
        try {
            m = Mat2Codec.decode(l.data);
        } catch(RuntimeException e) {
            return;   // unrecognized mat2: nothing to add
        }
        Object entries = m.get("entries");
        if(!(entries instanceof List))
            return;
        for(Object eo : (List<Object>) entries) {
            if(!(eo instanceof Map))
                continue;
            Map<String, Object> e = (Map<String, Object>) eo;
            String key = String.valueOf(e.get("key"));
            Object values = e.get("values");
            if(!(values instanceof List))
                continue;
            for(Object v : (List<Object>) values)
                if(v instanceof String && ((String) v).indexOf('/') >= 0)
                    refs.add(new Ref((String) v, -1, "mat2", key));
        }
    }

    /** Every reference occurrence, in scan order. */
    public List<Ref> all() {
        return new ArrayList<>(refs);
    }

    /** References grouped by source layer, each list de-duplicated by label. */
    public Map<String, List<Ref>> bySource() {
        Map<String, List<Ref>> grouped = new LinkedHashMap<>();
        for(String src : SOURCE_ORDER) {
            List<Ref> list = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for(Ref r : refs) {
                if(!r.source.equals(src))
                    continue;
                String lbl = r.label();
                if(!seen.contains(lbl)) {
                    seen.add(lbl);
                    list.add(r);
                }
            }
            if(!list.isEmpty())
                grouped.put(src, list);
        }
        return grouped;
    }

    /** The distinct set of referenced resource names (ignoring version/source). */
    public List<String> distinctNames() {
        List<String> names = new ArrayList<>();
        for(Ref r : refs)
            if(!names.contains(r.name))
                names.add(r.name);
        names.sort(String::compareTo);
        return names;
    }

    /** A human-readable, read-only reference report. */
    public String render(String title) {
        List<String> distinct = distinctNames();
        StringBuilder sb = new StringBuilder();
        sb.append("References");
        if(title != null)
            sb.append(" for ").append(title);
        sb.append('\n');
        sb.append(distinct.size()).append(" distinct resource")
          .append(distinct.size() == 1 ? "" : "s").append(" referenced.\n");

        Map<String, List<Ref>> grouped = bySource();
        if(grouped.isEmpty()) {
            sb.append("\n(this resource references no others)\n");
            return sb.toString();
        }
        for(Map.Entry<String, List<Ref>> e : grouped.entrySet()) {
            sb.append("\nfrom ").append(e.getKey())
              .append(" (").append(e.getValue().size()).append("):\n");
            for(Ref r : e.getValue())
                sb.append("    ").append(r.label()).append('\n');
        }
        return sb.toString();
    }
}
