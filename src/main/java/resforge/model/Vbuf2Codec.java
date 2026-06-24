package resforge.model;

import resforge.io.MessageReader;
import resforge.io.MessageWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Structure-preserving codec for a {@code vbuf2} layer. It captures each
 * attribute's exact data bytes so that an <em>unchanged</em> buffer re-encodes
 * <strong>byte-identically</strong> (verified against real files), while still
 * allowing a single attribute (e.g. positions) to be re-quantised after an edit.
 *
 * Editing re-compresses changed values into the attribute's original on-wire
 * format — the same precision the game renders from — so an edit is faithful for
 * any practical purpose, while every untouched attribute stays exact.
 */
public class Vbuf2Codec {
    public int ver;
    public int id;
    public int num;
    public final List<Attr> attrs = new ArrayList<>();

    public static class Attr {
        public final String name;
        public byte[] data;          // exact attribute data bytes (no name / length prefix)

        Attr(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }

        public boolean bare() {
            return !name.endsWith("2");
        }
    }

    private static final Map<String, Integer> ELN = Map.ofEntries(
            Map.entry("pos", 3), Map.entry("pos2", 3),
            Map.entry("nrm", 3), Map.entry("nrm2", 3),
            Map.entry("col", 4), Map.entry("col2", 4),
            Map.entry("tex", 2), Map.entry("tex2", 2),
            Map.entry("tan", 3), Map.entry("tan2", 3),
            Map.entry("bit", 3), Map.entry("bit2", 3),
            Map.entry("otex", 2), Map.entry("otex2", 2));

    public static Vbuf2Codec parse(byte[] payload) {
        Vbuf2Codec d = new Vbuf2Codec();
        MessageReader in = new MessageReader(payload);
        int fl = in.uint8();
        d.ver = fl & 0xf;
        if((fl & ~0xf) != 0 || d.ver >= 2)
            throw new IllegalArgumentException("unsupported vbuf flags/version: " + fl);
        if(d.ver >= 1)
            d.id = in.int16();
        d.num = in.uint16();
        while(!in.eom()) {
            String name = in.string();
            int dstart, dlen;
            if(d.ver >= 1) {
                dlen = in.int32();
                dstart = in.position();
                in.skip(dlen);
            } else {
                dstart = in.position();
                consumeVer0(in, name, d.num);
                dlen = in.position() - dstart;
            }
            d.attrs.add(new Attr(name, Arrays.copyOfRange(payload, dstart, dstart + dlen)));
        }
        return d;
    }

    public byte[] encode() {
        MessageWriter w = new MessageWriter();
        w.uint8(ver);                       // fl: top nibble was required to be 0
        if(ver >= 1)
            w.int16(id);
        w.uint16(num);
        for(Attr a : attrs) {
            w.string(a.name);
            if(ver >= 1)
                w.int32(a.data.length);
            w.bytes(a.data);
        }
        return w.toByteArray();
    }

    public Attr position() {
        for(Attr a : attrs) {
            if(a.name.equals("pos") || a.name.equals("pos2"))
                return a;
        }
        return null;
    }

    public Attr attr(String base) {
        for(Attr a : attrs)
            if(a.name.equals(base) || a.name.equals(base + "2"))
                return a;
        return null;
    }

    private int elnOf(Attr a) {
        return ELN.get(a.name);
    }

    /** The on-wire format string of a formatted attribute ("f4","sn2","uvec1",…),
     *  or "bare" for a ver-0 raw-float32 attribute. */
    private static String formatOf(Attr a) {
        if(a.bare())
            return "bare";
        MessageReader in = new MessageReader(a.data);
        in.uint8();                     // data version (== 1)
        return in.string();
    }

    /** Decodes any supported attribute to num*eln floats. */
    public float[] decodeAttr(String base) {
        Attr a = attr(base);
        if(a == null)
            return null;
        int eln = elnOf(a);
        float[] dst = new float[num * eln];
        if(a.bare()) {
            MessageReader in = new MessageReader(a.data);
            for(int i = 0; i < dst.length; i++)
                dst[i] = in.float32();
        } else {
            MessageReader in = new MessageReader(a.data);
            in.uint8();
            readAny(in, in.string(), dst);
        }
        return dst;
    }

    /** Re-encodes an attribute from num*eln floats, preserving its on-wire format. */
    public void setAttr(String base, float[] vals) {
        Attr a = attr(base);
        if(a == null)
            throw new IllegalStateException("no attribute: " + base);
        int eln = elnOf(a);
        if(vals.length != num * eln)
            throw new IllegalStateException("attribute '" + base + "' expects " + (num * eln)
                    + " floats (" + num + " vertices x " + eln + "), got " + vals.length);
        requireFinite(base, vals);
        if(a.bare()) {
            MessageWriter w = new MessageWriter();
            for(float v : vals)
                w.float32(v);
            a.data = w.toByteArray();
        } else {
            String fmt = formatOf(a);
            MessageWriter w = new MessageWriter();
            w.uint8(1).string(fmt);
            writeAny(w, fmt, vals);
            a.data = w.toByteArray();
        }
    }

    /** Decodes the position attribute to num*3 floats. */
    public float[] decodePositions() {
        Attr a = position();
        if(a == null)
            throw new IllegalStateException("no position attribute");
        MessageReader in = new MessageReader(a.data);
        float[] dst = new float[num * 3];
        if(a.bare()) {
            for(int i = 0; i < dst.length; i++)
                dst[i] = in.float32();
        } else {
            in.uint8();                     // data version (== 1)
            readFmt(in, in.string(), dst);
        }
        return dst;
    }

    /** Re-encodes the position attribute from num*3 floats, preserving its format. */
    public void setPositions(float[] vals) {        Attr a = position();
        if(a == null)
            throw new IllegalStateException("no position attribute");
        requireFinite("pos", vals);
        if(a.bare()) {
            MessageWriter w = new MessageWriter();
            for(float v : vals)
                w.float32(v);
            a.data = w.toByteArray();
        } else {
            MessageReader peek = new MessageReader(a.data);
            peek.uint8();
            String fmt = peek.string();
            MessageWriter w = new MessageWriter();
            w.uint8(1).string(fmt);
            writeFmt(w, fmt, vals);
            a.data = w.toByteArray();
        }
    }

    /* ------------------------------------------------------ bones2 skinning weights */

    /** The bone-weight on-wire format of the {@code bones2} attribute ("f4"/"un2"/"un1"), or null. */
    public String bones2Format() {
        Attr a = attr("bones");
        if(a == null || !a.name.equals("bones2"))
            return null;
        MessageReader in = new MessageReader(a.data);
        in.uint8();                                  // data version (== 1)
        return in.string();
    }

    /**
     * Re-encodes the skinning attribute from per-vertex influences, mirroring the
     * on-wire layout {@code haven.PoseMorph.read} parses. Handles both the modern
     * {@code bones2} format (a version byte, the weight format string, a max-influences
     * byte, …) and the legacy {@code bones} format (just a max-influences byte, with
     * {@code f4} weights). Either way the body is, per bone, a name followed by
     * {@code (run, startVertex, weights…)} spans, terminated by an empty name. Bones
     * are emitted in {@code boneNames} order, skipping any with no influence; the
     * client maps influences back to skeleton bones by name, so order is free.
     *
     * @param boneNames the influence-index → bone-name table
     * @param vJoints    num*4 influence indices into {@code boneNames} (or -1 padding)
     * @param vWeights   num*4 weights (0 padding); only positive weights are written
     */
    public void setBones2(String[] boneNames, int[] vJoints, float[] vWeights) {
        Attr a = attr("bones");
        if(a == null || (!a.name.equals("bones2") && !a.name.equals("bones")))
            throw new IllegalStateException("no bones/bones2 attribute to re-encode");
        boolean v2 = a.name.equals("bones2");
        String wfmt = v2 ? bones2Format() : "f4";

        int mba = 1;
        for(int v = 0; v < num; v++) {
            int c = 0;
            for(int k = 0; k < 4; k++)
                if(vJoints[v * 4 + k] >= 0 && vWeights[v * 4 + k] > 0)
                    c++;
            mba = Math.max(mba, c);
        }

        // per bone: parallel lists of (vertex, weight) in ascending vertex order
        List<List<int[]>> verts = new ArrayList<>();      // vertex index
        List<List<Float>> weights = new ArrayList<>();
        for(int b = 0; b < boneNames.length; b++) {
            verts.add(new ArrayList<>());
            weights.add(new ArrayList<>());
        }
        for(int v = 0; v < num; v++)
            for(int k = 0; k < 4; k++) {
                int b = vJoints[v * 4 + k];
                float wt = vWeights[v * 4 + k];
                if(b >= 0 && b < boneNames.length && wt > 0) {
                    verts.get(b).add(new int[]{v});
                    weights.get(b).add(wt);
                }
            }

        MessageWriter w = new MessageWriter();
        if(v2) w.uint8(1).string(wfmt).uint8(mba);
        else   w.uint8(mba);
        for(int b = 0; b < boneNames.length; b++) {
            List<int[]> vs = verts.get(b);
            if(vs.isEmpty())
                continue;
            w.string(boneNames[b]);
            List<Float> ws = weights.get(b);
            int i = 0;
            while(i < vs.size()) {
                int start = vs.get(i)[0];
                int j = i + 1;
                while(j < vs.size() && vs.get(j)[0] == vs.get(j - 1)[0] + 1)
                    j++;
                w.uint16(j - i).uint16(start);
                for(int k = i; k < j; k++)
                    writeWeight(w, wfmt, ws.get(k));
                i = j;
            }
            w.uint16(0).uint16(0);                       // end of this bone's spans
        }
        w.string("");                                    // end of bone list
        a.data = w.toByteArray();
    }

    private static void writeWeight(MessageWriter w, String wfmt, float wt) {
        switch(wfmt) {
            case "f4": w.float32(wt); break;
            case "un2": w.uint16(Math.round(Math.max(0, Math.min(1, wt)) * 65535)); break;
            case "un1": w.uint8(Math.round(Math.max(0, Math.min(1, wt)) * 255)); break;
            default: throw new IllegalStateException("unknown bone-weight format: " + wfmt);
        }
    }

    private static void consumeVer0(MessageReader in, String name, int num) {
        if(name.equals("bones") || name.equals("bones2")) {
            skipBones(in, name.equals("bones2"));
            return;
        }
        Integer eln = ELN.get(name);
        if(eln == null)
            throw new IllegalArgumentException("unknown vertex attribute: " + name);
        long cap = (long) num * eln;
        if(name.endsWith("2")) {
            in.uint8();
            in.skip((int) dataSize(in.string(), cap));
        } else {
            in.skip((int) (cap * 4));
        }
    }

    private static void skipBones(MessageReader in, boolean v2) {
        String wfmt = "f4";
        if(v2) { in.uint8(); wfmt = in.string(); }
        in.uint8();
        int wsz = wfmt.equals("f4") ? 4 : wfmt.equals("un2") ? 2 : wfmt.equals("un1") ? 1 : -1;
        if(wsz < 0)
            throw new IllegalArgumentException("unknown bone-weight format: " + wfmt);
        while(true) {
            if(in.string().isEmpty())
                break;
            while(true) {
                int run = in.uint16();
                in.uint16();
                if(run == 0)
                    break;
                in.skip(run * wsz);
            }
        }
    }

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
            default:       throw new IllegalArgumentException("unknown vertex format: " + fmt);
        }
    }

    /* ---- position de/re-quantisation (signed formats + f4, which positions use) ---- */

    private static void readFmt(MessageReader in, String fmt, float[] dst) {
        int cap = dst.length;
        switch(fmt) {
            case "f4":
                for(int i = 0; i < cap; i++) dst[i] = in.float32();
                break;
            case "sn4": { float f = in.float32() / 2147483647.0f;
                for(int i = 0; i < cap; i++) dst[i] = in.int32() * f; break; }
            case "sn2": { float f = in.float32() / 32767.0f;
                for(int i = 0; i < cap; i++) dst[i] = in.int16() * f; break; }
            case "sn1": { float f = in.float32() / 127.0f;
                for(int i = 0; i < cap; i++) dst[i] = in.int8() * f; break; }
            default:
                throw new IllegalArgumentException("position format not supported for editing: " + fmt);
        }
    }

    /** Rejects NaN/Infinity before re-quantising. A single non-finite value would
     *  otherwise poison the per-attribute max factor ({@code Math.max(x, NaN)} is NaN),
     *  so the whole attribute is written as a NaN scale with zeroed components — every
     *  vertex decodes back to NaN, silently destroying the mesh with no error. Real
     *  geometry is always finite, so this only ever catches malformed/corrupt input. */
    private static void requireFinite(String attr, float[] vals) {
        for(int i = 0; i < vals.length; i++)
            if(!Float.isFinite(vals[i]))
                throw new IllegalArgumentException("cannot encode non-finite value (" + vals[i]
                        + ") in vertex attribute '" + attr + "' (NaN/Infinity)");
    }

    private static void writeFmt(MessageWriter w, String fmt, float[] vals) {
        switch(fmt) {
            case "f4":
                for(float v : vals) w.float32(v);
                break;
            case "sn4": quantSigned(w, vals, 2147483647L); break;
            case "sn2": quantSigned(w, vals, 32767L); break;
            case "sn1": quantSigned(w, vals, 127L); break;
            default:
                throw new IllegalArgumentException("position format not supported for editing: " + fmt);
        }
    }

    private static void quantSigned(MessageWriter w, float[] vals, long max) {
        float maxv = 0;
        for(float v : vals)
            maxv = Math.max(maxv, Math.abs(v));
        w.float32(maxv);
        for(float v : vals) {
            int q;
            if(maxv == 0) {
                q = 0;
            } else {
                double x = Math.max(-1.0, Math.min(1.0, v / maxv));
                q = (int) Math.round(x * max);
            }
            if(max <= 127)
                w.int8(q);
            else if(max <= 32767)
                w.int16(q);
            else
                w.int32(q);
        }
    }

    /* ---- general attribute de/re-quantisation (all formats real attributes use) ---- */

    private static void readAny(MessageReader in, String fmt, float[] dst) {
        int cap = dst.length;
        float[] vb = new float[3];
        switch(fmt) {
            case "f4": for(int i = 0; i < cap; i++) dst[i] = in.float32(); break;
            case "f2": for(int i = 0; i < cap; i++) dst[i] = in.float16(); break;
            case "sn4": { float f = in.float32() / 2147483647.0f;
                for(int i = 0; i < cap; i++) dst[i] = in.int32() * f; break; }
            case "sn2": { float f = in.float32() / 32767.0f;
                for(int i = 0; i < cap; i++) dst[i] = in.int16() * f; break; }
            case "sn1": { float f = in.float32() / 127.0f;
                for(int i = 0; i < cap; i++) dst[i] = in.int8() * f; break; }
            case "un4": { float f = in.float32() / 4294967295.0f;
                for(int i = 0; i < cap; i++) dst[i] = in.uint32() * f; break; }
            case "un2": { float f = in.float32() / 65535.0f;
                for(int i = 0; i < cap; i++) dst[i] = in.uint16() * f; break; }
            case "un1": { float f = in.float32() / 255.0f;
                for(int i = 0; i < cap; i++) dst[i] = in.uint8() * f; break; }
            case "uvec1": { float F = 1.0f / 127.0f;
                for(int i = 0; i < cap; ) {
                    MessageReader.oct2uvec(vb, in.int8() * F, in.int8() * F);
                    dst[i++] = vb[0]; dst[i++] = vb[1]; dst[i++] = vb[2];
                } break; }
            case "uvec2": { float F = 1.0f / 32767.0f;
                for(int i = 0; i < cap; ) {
                    MessageReader.oct2uvec(vb, in.int16() * F, in.int16() * F);
                    dst[i++] = vb[0]; dst[i++] = vb[1]; dst[i++] = vb[2];
                } break; }
            default:
                throw new IllegalArgumentException("attribute format not supported for editing: " + fmt);
        }
    }

    private static void writeAny(MessageWriter w, String fmt, float[] vals) {
        switch(fmt) {
            case "f4": for(float v : vals) w.float32(v); break;
            case "f2": for(float v : vals) w.float16(v); break;
            case "sn4": quantSigned(w, vals, 2147483647L); break;
            case "sn2": quantSigned(w, vals, 32767L); break;
            case "sn1": quantSigned(w, vals, 127L); break;
            case "un4": quantUnsigned(w, vals, 4294967295L); break;
            case "un2": quantUnsigned(w, vals, 65535L); break;
            case "un1": quantUnsigned(w, vals, 255L); break;
            case "uvec1": quantOct(w, vals, 127); break;
            case "uvec2": quantOct(w, vals, 32767); break;
            default:
                throw new IllegalArgumentException("attribute format not supported for editing: " + fmt);
        }
    }

    private static void quantUnsigned(MessageWriter w, float[] vals, long max) {
        float maxv = 0;
        for(float v : vals)
            maxv = Math.max(maxv, Math.abs(v));
        w.float32(maxv);
        for(float v : vals) {
            long q = (maxv == 0) ? 0
                    : Math.round(Math.max(0.0, Math.min(1.0, v / maxv)) * max);
            if(max <= 255)
                w.uint8((int) q);
            else if(max <= 65535)
                w.uint16((int) q);
            else
                w.int32((int) q);
        }
    }

    /** Octahedral-encodes unit vectors (eln 3) to two signed-normalised components. */
    private static void quantOct(MessageWriter w, float[] vals, int max) {
        float[] oct = new float[2];
        for(int i = 0; i + 2 < vals.length; i += 3) {
            uvec2oct(oct, vals[i], vals[i + 1], vals[i + 2]);
            for(int k = 0; k < 2; k++) {
                int q = (int) Math.round(Math.max(-1.0, Math.min(1.0, oct[k])) * max);
                if(max <= 127)
                    w.int8(q);
                else
                    w.int16(q);
            }
        }
    }

    /** Octahedral encode of a unit vector (mirrors haven.Utils.uvec2oct). */
    private static void uvec2oct(float[] out, float x, float y, float z) {
        float m = 1.0f / (Math.abs(x) + Math.abs(y) + Math.abs(z));
        float hx = x * m, hy = y * m;
        if(z >= 0) {
            out[0] = hx;
            out[1] = hy;
        } else {
            out[0] = (1 - Math.abs(hy)) * Math.copySign(1, hx);
            out[1] = (1 - Math.abs(hx)) * Math.copySign(1, hy);
        }
    }
}
