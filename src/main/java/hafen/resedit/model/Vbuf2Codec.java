package hafen.resedit.model;

import hafen.resedit.io.MessageReader;
import hafen.resedit.io.MessageWriter;

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
    public void setPositions(float[] vals) {
        Attr a = position();
        if(a == null)
            throw new IllegalStateException("no position attribute");
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
}
