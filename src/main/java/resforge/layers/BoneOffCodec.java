package resforge.layers;

import resforge.io.Json;
import resforge.io.MessageReader;
import resforge.io.MessageWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed editor for the {@code boneoff} ("bone offset" / equip-point) layer (from
 * haven.Skeleton.BoneOffset): a named program of small transform opcodes that
 * positions an equipped item relative to a skeleton — translate, rotate, equip-point
 * reference, bone-alignment, null-rotation, scale — composed in order.
 *
 * <p>It is exposed as editable JSON:
 * <pre>
 *   {"name": "&lt;point&gt;", "ops": [ {"op": "...", ...}, ... ]}
 * </pre>
 * with one object per opcode:
 * <ul>
 *   <li>{@code translate}      — {@code x,y,z} ({@code cpfloat}, opcode 0)</li>
 *   <li>{@code translate_f32}  — {@code x,y,z} ({@code float32}, opcode 16)</li>
 *   <li>{@code rotate}         — {@code angle} (radians) + {@code axis:[x,y,z]} ({@code cpfloat}, opcode 1)</li>
 *   <li>{@code rotate_q}       — {@code angleTurns} (fraction of a turn) + {@code axisOct:[a,b]}
 *       (octahedral {@code snorm16} ints, opcode 17)</li>
 *   <li>{@code eqpoint}        — {@code bone} (opcode 2)</li>
 *   <li>{@code bonealign}      — {@code ref:[x,y,z]} ({@code cpfloat}) + {@code from,to} (opcode 3)</li>
 *   <li>{@code bonealign_q}    — {@code refOct:[a,b]} (octahedral ints) + {@code from,to} (opcode 19)</li>
 *   <li>{@code nullrot}        — (opcode 4)</li>
 *   <li>{@code scale}          — {@code scale} ({@code float32}, opcode 5)</li>
 * </ul>
 *
 * <p>The quantised rotation (opcode 17/19) keeps its axis as the raw octahedral
 * {@code snorm16} integers rather than a decoded unit vector, because the octahedral
 * round-trip is not byte-exact (a decoded→re-encoded axis can drift ±1). Storing the
 * raw components guarantees every recognised {@code boneoff} round-trips exactly while
 * still letting the angle be edited within its unsigned-16-bit turn range and the
 * friendlier {@code cpfloat}/{@code float32} translations be edited as plain numbers.
 * Like the other typed layers, it is offered as editable JSON only
 * under the lossless-or-raw guard ({@link #toJsonIfLossless}): if decode → JSON →
 * encode does not reproduce the original bytes, the layer stays raw.
 */
public final class BoneOffCodec {
    private BoneOffCodec() {
    }

    public static class Unsupported extends RuntimeException {
        public Unsupported(String msg) {
            super(msg);
        }
    }

    /** Decodes a boneoff payload into a {@code {"name","ops":[...]}} model. */
    public static Map<String, Object> decode(byte[] payload) {
        MessageReader in = new MessageReader(payload);
        String name = in.string();
        List<Object> ops = new ArrayList<>();
        while(!in.eom()) {
            int code = in.uint8();
            Map<String, Object> op = new LinkedHashMap<>();
            switch(code) {
                case 0:
                    op.put("op", "translate");
                    op.put("x", in.cpfloat());
                    op.put("y", in.cpfloat());
                    op.put("z", in.cpfloat());
                    break;
                case 16:
                    op.put("op", "translate_f32");
                    op.put("x", (double) in.float32());
                    op.put("y", (double) in.float32());
                    op.put("z", (double) in.float32());
                    break;
                case 1:
                    op.put("op", "rotate");
                    op.put("angle", in.cpfloat());
                    op.put("axis", xyz(in.cpfloat(), in.cpfloat(), in.cpfloat()));
                    break;
                case 17:
                    op.put("op", "rotate_q");
                    op.put("angleTurns", (double) in.uint16() / 65536.0);
                    op.put("axisOct", octRaw(in.int16(), in.int16()));
                    break;
                case 2:
                    op.put("op", "eqpoint");
                    op.put("bone", in.string());
                    break;
                case 3:
                    op.put("op", "bonealign");
                    op.put("ref", xyz(in.cpfloat(), in.cpfloat(), in.cpfloat()));
                    op.put("from", in.string());
                    op.put("to", in.string());
                    break;
                case 19:
                    op.put("op", "bonealign_q");
                    op.put("refOct", octRaw(in.int16(), in.int16()));
                    op.put("from", in.string());
                    op.put("to", in.string());
                    break;
                case 4:
                    op.put("op", "nullrot");
                    break;
                case 5:
                    op.put("op", "scale");
                    op.put("scale", (double) in.float32());
                    break;
                default:
                    throw new Unsupported("boneoff opcode " + code);
            }
            ops.add(op);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("name", name);
        model.put("ops", ops);
        return model;
    }

    /** Encodes a {@code {"name","ops":[...]}} model back into payload bytes. */
    public static byte[] encode(Map<String, Object> model) {
        Object nameObj = model.get("name");
        if(!(nameObj instanceof String))
            throw new Unsupported("boneoff requires a string name");
        Object opsObj = model.get("ops");
        if(!(opsObj instanceof List))
            throw new Unsupported("boneoff requires an ops array");

        MessageWriter out = new MessageWriter();
        out.string((String) nameObj);
        for(Object o : (List<?>) opsObj) {
            if(!(o instanceof Map))
                throw new Unsupported("each op must be an object");
            Map<?, ?> op = (Map<?, ?>) o;
            String kind = str(op.get("op"), "op");
            switch(kind) {
                case "translate":
                    out.uint8(0);
                    out.cpfloat(num(op.get("x"))).cpfloat(num(op.get("y"))).cpfloat(num(op.get("z")));
                    break;
                case "translate_f32":
                    out.uint8(16);
                    out.float32((float) num(op.get("x"))).float32((float) num(op.get("y")))
                            .float32((float) num(op.get("z")));
                    break;
                case "rotate": {
                    out.uint8(1);
                    double[] ax = vec3(op.get("axis"), "axis");
                    out.cpfloat(num(op.get("angle"))).cpfloat(ax[0]).cpfloat(ax[1]).cpfloat(ax[2]);
                    break;
                }
                case "rotate_q": {
                    out.uint8(17);
                    out.uint16(turns(op.get("angleTurns")));
                    int[] oct = octPair(op.get("axisOct"), "axisOct");
                    out.int16(oct[0]).int16(oct[1]);
                    break;
                }
                case "eqpoint":
                    out.uint8(2);
                    out.string(str(op.get("bone"), "bone"));
                    break;
                case "bonealign": {
                    out.uint8(3);
                    double[] ref = vec3(op.get("ref"), "ref");
                    out.cpfloat(ref[0]).cpfloat(ref[1]).cpfloat(ref[2]);
                    out.string(str(op.get("from"), "from")).string(str(op.get("to"), "to"));
                    break;
                }
                case "bonealign_q": {
                    out.uint8(19);
                    int[] oct = octPair(op.get("refOct"), "refOct");
                    out.int16(oct[0]).int16(oct[1]);
                    out.string(str(op.get("from"), "from")).string(str(op.get("to"), "to"));
                    break;
                }
                case "nullrot":
                    out.uint8(4);
                    break;
                case "scale":
                    out.uint8(5);
                    out.float32((float) num(op.get("scale")));
                    break;
                default:
                    throw new Unsupported("unknown boneoff op \"" + kind + "\"");
            }
        }
        return out.toByteArray();
    }

    /** Returns editable JSON for the layer, or null if it cannot round-trip losslessly. */
    public static String toJsonIfLossless(byte[] payload) {
        try {
            Map<String, Object> model = decode(payload);
            String json = Json.write(model);
            @SuppressWarnings("unchecked")
            Map<String, Object> reparsed = (Map<String, Object>) Json.parse(json);
            if(Arrays.equals(payload, encode(reparsed)))
                return json;
        } catch(RuntimeException e) {
            /* fall through to raw */
        }
        return null;
    }

    private static List<Object> xyz(double x, double y, double z) {
        List<Object> v = new ArrayList<>(3);
        v.add(x);
        v.add(y);
        v.add(z);
        return v;
    }

    private static List<Object> octRaw(int a, int b) {
        List<Object> v = new ArrayList<>(2);
        v.add((long) a);
        v.add((long) b);
        return v;
    }

    private static double num(Object o) {
        if(!(o instanceof Number))
            throw new Unsupported("expected a number, got " + o);
        return ((Number) o).doubleValue();
    }

    private static String str(Object o, String what) {
        if(!(o instanceof String))
            throw new Unsupported("expected a string for \"" + what + "\"");
        return (String) o;
    }

    private static double[] vec3(Object o, String what) {
        if(!(o instanceof List) || ((List<?>) o).size() != 3)
            throw new Unsupported("\"" + what + "\" must be a 3-element [x,y,z] array");
        List<?> l = (List<?>) o;
        return new double[]{num(l.get(0)), num(l.get(1)), num(l.get(2))};
    }

    private static int[] octPair(Object o, String what) {
        if(!(o instanceof List) || ((List<?>) o).size() != 2)
            throw new Unsupported("\"" + what + "\" must be a 2-element [a,b] array");
        List<?> l = (List<?>) o;
        return new int[]{int16(l.get(0)), int16(l.get(1))};
    }

    private static int int16(Object o) {
        if(!(o instanceof Number))
            throw new Unsupported("octahedral component must be an integer");
        long v = ((Number) o).longValue();
        if(v < -0x8000 || v > 0x7fff)
            throw new Unsupported("octahedral component out of int16 range: " + v);
        return (int) v;
    }

    private static int turns(Object o) {
        if(!(o instanceof Number))
            throw new Unsupported("angleTurns must be a number");
        double turns = ((Number) o).doubleValue();
        double maximum = 65535.0 / 65536.0;
        if(!Double.isFinite(turns))
            throw new Unsupported("angleTurns must be finite");
        if(turns < 0.0 || turns > maximum)
            throw new Unsupported("angleTurns out of range [0, " + maximum + "]: " + turns);
        return (int) Math.round(turns * 65536.0);
    }
}
