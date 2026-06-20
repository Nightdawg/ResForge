package resforge.layers;

import resforge.io.MessageReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only inspector for a {@code vbuf2} (vertex buffer) layer — reports the
 * vertex count and the per-attribute formats without modifying anything. This
 * is the "read-only first" step toward 3D editing (see DESIGN-notes §8).
 *
 * Layer format (from haven.VertexBuf.VertexRes):
 * <pre>
 *   uint8 fl            ver = fl &amp; 0xf  (only 0 and 1 are valid; top nibble must be 0)
 *   if ver &gt;= 1: int16 id
 *   uint16 num          number of vertices
 *   attributes, repeated until end-of-message:
 *     string name
 *     if ver &gt;= 1: int32 sublen, then sublen bytes (length-prefixed)
 *     else (ver == 0, the form mkres emits): inline data whose size is
 *       determined by the attribute's element count and on-wire format:
 *         "name"  (bare):       num*eln float32                    (haven loadbuf)
 *         "name2" (formatted):  uint8(1) + string fmt + fmt-data   (haven loadbuf2)
 * </pre>
 *
 * The float attributes (pos/nrm/col/tex/tan/bit/otex, bare and "2"-formatted)
 * are walked exactly; the variable-length bone data (bones/bones2) and any
 * unknown attribute stop the walk (recorded in {@link #stoppedAt}). The vertex
 * count and the attributes walked so far are always reported.
 */
public class Vbuf2Info {
    public boolean recognized;
    public int ver;
    public int id;
    public int num;                              // vertex count
    public final List<String> attribs = new ArrayList<>();
    public String stoppedAt;                     // first attribute the walk could not size, or null
    public boolean fullyWalked;                  // reached end-of-message cleanly

    private static final Map<String, Integer> ELN = Map.ofEntries(
            Map.entry("pos", 3), Map.entry("pos2", 3),
            Map.entry("nrm", 3), Map.entry("nrm2", 3),
            Map.entry("col", 4), Map.entry("col2", 4),
            Map.entry("tex", 2), Map.entry("tex2", 2),
            Map.entry("tan", 3), Map.entry("tan2", 3),
            Map.entry("bit", 3), Map.entry("bit2", 3),
            Map.entry("otex", 2), Map.entry("otex2", 2));

    public static Vbuf2Info parse(byte[] payload) {
        Vbuf2Info vi = new Vbuf2Info();
        try {
            MessageReader in = new MessageReader(payload);
            int fl = in.uint8();
            vi.ver = fl & 0xf;
            if((fl & ~0xf) != 0 || vi.ver >= 2)
                return vi;
            if(vi.ver >= 1)
                vi.id = in.int16();
            vi.num = in.uint16();
            vi.recognized = true;

            while(!in.eom()) {
                String name = in.string();
                if(vi.ver >= 1) {
                    int sublen = in.int32();
                    int at = in.position();
                    vi.attribs.add(name + "(" + peekFmt(payload, at) + ")");
                    in.skip(sublen);
                    continue;
                }
                if(name.equals("bones") || name.equals("bones2")) {
                    String wfmt = walkBones(in, name.equals("bones2"));
                    vi.attribs.add(name + "(" + wfmt + ")");
                    continue;
                }
                Integer eln = ELN.get(name);
                if(eln == null) {
                    vi.stoppedAt = name;
                    return vi;
                }
                long cap = (long) vi.num * eln;
                if(name.endsWith("2")) {
                    in.uint8();                 // data version (== 1)
                    String fmt = in.string();
                    long sz = dataSize(fmt, cap);
                    if(sz < 0) {
                        vi.stoppedAt = name + ":" + fmt;
                        return vi;
                    }
                    in.skip((int) sz);
                    vi.attribs.add(name + "(" + fmt + ")");
                } else {
                    in.skip((int) (cap * 4));
                    vi.attribs.add(name + "(bare)");
                }
            }
            vi.fullyWalked = true;
        } catch(RuntimeException e) {
            vi.stoppedAt = (vi.stoppedAt != null) ? vi.stoppedAt : "<error>";
        }
        return vi;
    }

    /** Consumes inline bone data (haven.PoseMorph): per-bone run-length-coded
     *  per-vertex weight spans. Returns the weight format. */
    private static String walkBones(MessageReader in, boolean v2) {
        String wfmt = "f4";
        if(v2) {
            in.uint8();              // bone-data version (== 1)
            wfmt = in.string();      // weight format
        }
        in.uint8();                  // mba (max bones per vertex)
        int wsz;
        switch(wfmt) {
            case "f4": wsz = 4; break;
            case "un2": wsz = 2; break;
            case "un1": wsz = 1; break;
            default: throw new IllegalStateException("unknown bone-weight format: " + wfmt);
        }
        while(true) {
            String bone = in.string();
            if(bone.isEmpty())
                break;
            while(true) {
                int run = in.uint16();
                in.uint16();          // starting vertex index
                if(run == 0)
                    break;
                in.skip(run * wsz);
            }
        }
        return wfmt;
    }

    private static String peekFmt(byte[] b, int at) {
        try {
            MessageReader in = new MessageReader(b, at, b.length - at);
            in.uint8();
            return in.string();
        } catch(RuntimeException e) {
            return "?";
        }
    }

    /** Byte size of a formatted float attribute's data for {@code cap} total elements. */
    private static long dataSize(String fmt, long cap) {
        switch(fmt) {
            case "f4":     return cap * 4;
            case "f2":     return cap * 2;
            case "f1":     return cap;
            case "sf9995": return (cap / 3) * 4;
            case "sn4": case "un4": return 4 + cap * 4;
            case "sn2": case "un2": return 4 + cap * 2;
            case "sn1": case "un1": return 4 + cap;
            case "rn4":    return 8 + cap * 4;
            case "rn2":    return 8 + cap * 2;
            case "rn1":    return 8 + cap;
            case "uvech":  return cap / 3;
            case "uvec1":  return (cap / 3) * 2;
            case "uvec2":  return (cap / 3) * 4;
            default:       return -1;
        }
    }
}
