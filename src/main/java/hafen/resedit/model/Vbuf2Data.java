package hafen.resedit.model;

import hafen.resedit.io.MessageReader;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decodes a {@code vbuf2} layer into actual (de-quantised) vertex attribute
 * arrays — positions, normals, texture coords, etc. — for read-only export.
 * Mirrors {@code haven.VertexBuf.loadbuf}/{@code loadbuf2} and the de-quantising
 * formulas in {@code haven.Utils}. Bone data is skipped (not needed for a mesh
 * export). Returns {@code null} if the layer cannot be decoded.
 */
public class Vbuf2Data {
    public int ver;
    public int id;
    public int num;                                    // vertex count
    public final Map<String, float[]> attribs = new LinkedHashMap<>();   // name -> num*eln floats

    private static final Map<String, Integer> ELN = Map.ofEntries(
            Map.entry("pos", 3), Map.entry("pos2", 3),
            Map.entry("nrm", 3), Map.entry("nrm2", 3),
            Map.entry("col", 4), Map.entry("col2", 4),
            Map.entry("tex", 2), Map.entry("tex2", 2),
            Map.entry("tan", 3), Map.entry("tan2", 3),
            Map.entry("bit", 3), Map.entry("bit2", 3),
            Map.entry("otex", 2), Map.entry("otex2", 2));

    /** Normalised lookup: returns the float array for a base name (e.g. "pos"),
     *  whether it was stored bare or formatted ("pos" or "pos2"). */
    public float[] get(String base) {
        float[] v = attribs.get(base);
        return (v != null) ? v : attribs.get(base + "2");
    }

    public static Vbuf2Data parse(byte[] payload) {
        Vbuf2Data d = new Vbuf2Data();
        try {
            MessageReader in = new MessageReader(payload);
            int fl = in.uint8();
            d.ver = fl & 0xf;
            if((fl & ~0xf) != 0 || d.ver >= 2)
                return null;
            if(d.ver >= 1)
                d.id = in.int16();
            d.num = in.uint16();

            while(!in.eom()) {
                String name = in.string();
                if(d.ver >= 1) {
                    int sublen = in.int32();
                    int at = in.position();
                    if(ELN.containsKey(name)) {
                        MessageReader sub = new MessageReader(payload, at, sublen);
                        d.attribs.put(name, decodeAttr(sub, name, d.num));
                    }
                    in.skip(sublen);
                    continue;
                }
                if(name.equals("bones") || name.equals("bones2")) {
                    skipBones(in, name.equals("bones2"));
                    continue;
                }
                Integer eln = ELN.get(name);
                if(eln == null)
                    break;
                d.attribs.put(name, decodeAttr(in, name, d.num));
            }
        } catch(RuntimeException e) {
            return null;
        }
        return d;
    }

    private static float[] decodeAttr(MessageReader in, String name, int num) {
        int eln = ELN.get(name);
        float[] dst = new float[num * eln];
        if(name.endsWith("2")) {
            in.uint8();                 // data version (== 1)
            String fmt = in.string();
            readFmt(in, fmt, dst);
        } else {
            for(int i = 0; i < dst.length; i++)
                dst[i] = in.float32();  // bare float32 (loadbuf)
        }
        return dst;
    }

    private static void readFmt(MessageReader in, String fmt, float[] dst) {
        int cap = dst.length;
        float[] vb = new float[3];
        switch(fmt) {
            case "f4":
                for(int i = 0; i < cap; i++) dst[i] = in.float32();
                break;
            case "f2":
                for(int i = 0; i < cap; i++) dst[i] = hfdec((short) in.int16());
                break;
            case "f1":
                for(int i = 0; i < cap; i++) dst[i] = mfdec((byte) in.int8());
                break;
            case "sf9995":
                for(int i = 0; i < cap; i += 3) {
                    float9995d(in.int32(), vb);
                    dst[i] = vb[0]; dst[i + 1] = vb[1]; dst[i + 2] = vb[2];
                }
                break;
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
            case "rn4": { float m = in.float32(), k = in.float32() / 4294967295.0f;
                for(int i = 0; i < cap; i++) dst[i] = (in.uint32() * k) + m; break; }
            case "rn2": { float m = in.float32(), k = in.float32() / 65535.0f;
                for(int i = 0; i < cap; i++) dst[i] = (in.uint16() * k) + m; break; }
            case "rn1": { float m = in.float32(), k = in.float32() / 255.0f;
                for(int i = 0; i < cap; i++) dst[i] = (in.uint8() * k) + m; break; }
            case "uvech": { float F = 1.0f / 7.0f;
                for(int i = 0; i < cap; ) {
                    int v = in.uint8();
                    oct2uvec(vb, sb((v & 0xf0) >> 4, 4) * F, sb(v & 0x0f, 4) * F);
                    dst[i++] = vb[0]; dst[i++] = vb[1]; dst[i++] = vb[2];
                }
                break; }
            case "uvec1": { float F = 1.0f / 127.0f;
                for(int i = 0; i < cap; ) {
                    oct2uvec(vb, in.int8() * F, in.int8() * F);
                    dst[i++] = vb[0]; dst[i++] = vb[1]; dst[i++] = vb[2];
                }
                break; }
            case "uvec2": { float F = 1.0f / 32767.0f;
                for(int i = 0; i < cap; ) {
                    oct2uvec(vb, in.int16() * F, in.int16() * F);
                    dst[i++] = vb[0]; dst[i++] = vb[1]; dst[i++] = vb[2];
                }
                break; }
            default:
                throw new IllegalStateException("unsupported vertex format: " + fmt);
        }
    }

    private static void skipBones(MessageReader in, boolean v2) {
        String wfmt = "f4";
        if(v2) { in.uint8(); wfmt = in.string(); }
        in.uint8();                  // mba
        int wsz = wfmt.equals("f4") ? 4 : wfmt.equals("un2") ? 2 : wfmt.equals("un1") ? 1 : -1;
        if(wsz < 0)
            throw new IllegalStateException("unknown bone-weight format: " + wfmt);
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

    private static int sb(int v, int bits) {
        int shift = 32 - bits;
        return (v << shift) >> shift;
    }

    /** IEEE-754 half-precision decode. */
    private static float hfdec(short bits) {
        int h = bits & 0xffff;
        int sign = (h >> 15) & 1;
        int exp = (h >> 10) & 0x1f;
        int mant = h & 0x3ff;
        float val;
        if(exp == 0)
            val = (float) (mant * Math.pow(2, -24));
        else if(exp == 31)
            val = (mant == 0) ? Float.POSITIVE_INFINITY : Float.NaN;
        else
            val = (float) ((1 + mant / 1024.0) * Math.pow(2, exp - 15));
        return (sign == 1) ? -val : val;
    }

    /** 8-bit minifloat decode (haven MiniFloat: 1 sign, 4 exp bias 7, 3 mantissa). */
    private static float mfdec(byte bits) {
        int b = bits & 0xff;
        int sign = (b >> 7) & 1;
        int exp = (b >> 3) & 0xf;
        int mant = b & 0x7;
        float val;
        if(exp == 0)
            val = (float) (mant * Math.pow(2, -9));
        else if(exp == 15)
            val = (mant == 0) ? Float.POSITIVE_INFINITY : Float.NaN;
        else
            val = (float) ((1 + mant / 8.0) * Math.pow(2, exp - 7));
        return (sign == 1) ? -val : val;
    }

    private static void float9995d(int word, float[] ret) {
        int xb = (word & 0x7f800000) >> 23, xs = ((word & 0x80000000) >> 31) & 1,
            yb = (word & 0x003fc000) >> 14, ys = ((word & 0x00400000) >> 22) & 1,
            zb = (word & 0x00001fe0) >> 5,  zs = ((word & 0x00002000) >> 13) & 1;
        int me = (word & 0x1f) - 15;
        int xe = Integer.numberOfLeadingZeros(xb) - 24,
            ye = Integer.numberOfLeadingZeros(yb) - 24,
            ze = Integer.numberOfLeadingZeros(zb) - 24;
        ret[0] = (xe == 8) ? 0 : Float.intBitsToFloat((xs << 31) | ((me - xe + 127) << 23) | ((xb << (xe + 16)) & 0x007fffff));
        ret[1] = (ye == 8) ? 0 : Float.intBitsToFloat((ys << 31) | ((me - ye + 127) << 23) | ((yb << (ye + 16)) & 0x007fffff));
        ret[2] = (ze == 8) ? 0 : Float.intBitsToFloat((zs << 31) | ((me - ze + 127) << 23) | ((zb << (ze + 16)) & 0x007fffff));
    }

    private static void oct2uvec(float[] buf, float x, float y) {
        float z = 1 - (Math.abs(x) + Math.abs(y));
        if(z < 0) {
            float xc = x, yc = y;
            x = (1 - Math.abs(yc)) * Math.copySign(1, xc);
            y = (1 - Math.abs(xc)) * Math.copySign(1, yc);
        }
        float f = 1 / (float) Math.sqrt((x * x) + (y * y) + (z * z));
        buf[0] = x * f;
        buf[1] = y * f;
        buf[2] = z * f;
    }
}
