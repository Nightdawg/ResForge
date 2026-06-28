package resforge.layers;

import resforge.io.Json;
import resforge.io.MessageReader;
import resforge.io.MessageWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed editor for the {@code props} layer (from haven.Resource.Props): a
 * version byte followed by a {@code tto} list of alternating string keys and
 * values. It is exposed as editable JSON, but only when the round-trip is
 * provably lossless — {@link #toJsonIfLossless} decodes, re-serializes to JSON,
 * re-encodes, and returns the JSON only if it reproduces the original bytes
 * exactly. Otherwise the caller falls back to a raw passthrough, so a props
 * layer can never be corrupted.
 *
 * <p>Each value is rendered with an explicit type tag so the exact {@code tto}
 * encoding is preserved — the same tagged-value form {@link Mat2Codec} uses. A
 * string stays a plain JSON string; every other value is a single-entry object
 * naming its exact {@code tto} type, e.g. {@code {"u8":50}}, {@code {"f32":0.5}},
 * {@code {"color":[204,204,204,255]}}, {@code {"coord":[x,y]}},
 * {@code {"bytes":"<base64>"}}, {@code {"list":[…]}} or {@code {"map":{…}}}.
 * Tagging everything (rather than leaving integers/lists/maps bare) makes a JSON
 * object unambiguously a tag, records the exact wire width instead of re-deriving
 * it, and keeps this codec symmetric with {@link Mat2Codec}.
 *
 * <p>Supported value types: string, nil, the integer widths (u8/u16/i8/i16/int/
 * long), float32/float64, color, fcolor, coord, fcoord32/fcoord64, byte blobs
 * (base64), uid, resid, resource specs, and nested list/map. The remaining
 * {@code tto} types (float8/float16 and the snorm/unorm/mnorm numbers) are not
 * modelled and simply keep the layer raw, since their round-trip is not provably
 * byte-exact.
 */
public final class PropsCodec {
    private static final int T_END = 0;
    private static final int T_INT = 1;
    private static final int T_STR = 2;
    private static final int T_COORD = 3;
    private static final int T_UINT8 = 4;
    private static final int T_UINT16 = 5;
    private static final int T_COLOR = 6;
    private static final int T_FCOLOR = 7;
    private static final int T_TTOL = 8;
    private static final int T_INT8 = 9;
    private static final int T_INT16 = 10;
    private static final int T_NIL = 12;
    private static final int T_UID = 13;
    private static final int T_BYTES = 14;
    private static final int T_FLOAT32 = 15;
    private static final int T_FLOAT64 = 16;
    private static final int T_FCOORD32 = 18;
    private static final int T_FCOORD64 = 19;
    private static final int T_MAP = 32;
    private static final int T_LONG = 33;
    private static final int T_RESSPEC = 34;
    private static final int T_RESID = 35;

    /** Hard cap on tto list/map nesting, so a crafted payload fails with a clear
     *  {@link Unsupported} (a {@code RuntimeException}, caught by the lossless-or-raw
     *  guard) instead of a {@link StackOverflowError}. Real props nest a level or two. */
    private static final int MAX_DEPTH = 256;

    private PropsCodec() {
    }

    public static class Unsupported extends RuntimeException {
        public Unsupported(String msg) {
            super(msg);
        }
    }

    /** Decodes a props payload into a {@code {"version":1,"props":{...}}} model. */
    public static Map<String, Object> decode(byte[] payload) {
        MessageReader in = new MessageReader(payload);
        int ver = in.uint8();
        if(ver != 1)
            throw new Unsupported("props version " + ver);
        List<Object> flat = new ArrayList<>();
        while(!in.eom()) {
            int t = in.uint8();
            if(t == T_END)
                break;
            flat.add(readValue(in, t, 0));
        }
        if(!in.eom())
            throw new Unsupported("trailing data after props list");
        if((flat.size() & 1) != 0)
            throw new Unsupported("props list is not key/value pairs");
        Map<String, Object> props = new LinkedHashMap<>();
        for(int a = 0; a < flat.size(); a += 2) {
            Object key = flat.get(a);
            if(!(key instanceof String))
                throw new Unsupported("non-string props key");
            if(props.containsKey(key))
                throw new Unsupported("duplicate props key: " + key);
            props.put((String) key, flat.get(a + 1));
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("version", (long) ver);
        model.put("props", props);
        return model;
    }

    /** Encodes a {@code {"version":1,"props":{...}}} model back into payload bytes. */
    public static byte[] encode(Map<String, Object> model) {
        Object verObj = model.get("version");
        if(!(verObj instanceof Number))
            throw new Unsupported("missing/invalid version");
        Object propsObj = model.get("props");
        if(!(propsObj instanceof Map))
            throw new Unsupported("missing/invalid props map");
        MessageWriter out = new MessageWriter();
        out.uint8(Nums.u8(verObj));
        for(Map.Entry<?, ?> e : ((Map<?, ?>) propsObj).entrySet()) {
            writeValue(out, String.valueOf(e.getKey()));
            writeValue(out, e.getValue());
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

    private static Object readValue(MessageReader in, int t, int depth) {
        switch(t) {
            case T_STR:      return in.string();
            case T_NIL:      return null;
            case T_UINT8:    return tag("u8", (long) in.uint8());
            case T_UINT16:   return tag("u16", (long) in.uint16());
            case T_INT8:     return tag("i8", (long) in.int8());
            case T_INT16:    return tag("i16", (long) in.int16());
            case T_INT:      return tag("int", (long) in.int32());
            case T_LONG:     return tag("long", in.int64());
            case T_FLOAT32:  return tag("f32", (double) in.float32());
            case T_FLOAT64:  return tag("f64", in.float64());
            case T_COLOR:    return tag("color", uint8List(in, 4));
            case T_FCOLOR:   return tag("fcolor", float32List(in, 4));
            case T_COORD:    return tag("coord", int32List(in, 2));
            case T_FCOORD32: return tag("fcoord32", float32List(in, 2));
            case T_FCOORD64: return tag("fcoord64", float64List(in, 2));
            case T_BYTES:    return tag("bytes", Base64.getEncoder().encodeToString(readBytes(in)));
            case T_UID:      return tag("uid", in.int64());
            case T_RESID:    return tag("resid", (long) in.uint16());
            case T_RESSPEC:  return tag("resspec", resSpec(in));
            case T_TTOL:     return tag("list", readList(in, depth));
            case T_MAP:      return tag("map", readMap(in, depth));
            default:         throw new Unsupported("tto type tag " + t);
        }
    }

    private static List<Object> readList(MessageReader in, int depth) {
        if(depth >= MAX_DEPTH)
            throw new Unsupported("tto nesting too deep");
        List<Object> list = new ArrayList<>();
        while(!in.eom()) {
            int t = in.uint8();
            if(t == T_END)
                break;
            list.add(readValue(in, t, depth + 1));
        }
        return list;
    }

    private static Map<String, Object> readMap(MessageReader in, int depth) {
        if(depth >= MAX_DEPTH)
            throw new Unsupported("tto nesting too deep");
        Map<String, Object> map = new LinkedHashMap<>();
        while(!in.eom()) {
            int t = in.uint8();
            if(t == T_END)
                break;
            Object key = readValue(in, t, depth + 1);
            if(!(key instanceof String))
                throw new Unsupported("non-string map key");
            Object val = readValue(in, in.uint8(), depth + 1);
            map.put((String) key, val);
        }
        return map;
    }

    private static List<Object> uint8List(MessageReader in, int n) {
        List<Object> l = new ArrayList<>(n);
        for(int i = 0; i < n; i++)
            l.add((long) in.uint8());
        return l;
    }

    private static List<Object> int32List(MessageReader in, int n) {
        List<Object> l = new ArrayList<>(n);
        for(int i = 0; i < n; i++)
            l.add((long) in.int32());
        return l;
    }

    private static List<Object> float32List(MessageReader in, int n) {
        List<Object> l = new ArrayList<>(n);
        for(int i = 0; i < n; i++)
            l.add((double) in.float32());
        return l;
    }

    private static List<Object> float64List(MessageReader in, int n) {
        List<Object> l = new ArrayList<>(n);
        for(int i = 0; i < n; i++)
            l.add(in.float64());
        return l;
    }

    /** Reads a {@code T_BYTES} blob: a {@code uint8} length, or — when its high bit is
     *  set — a {@code 0x80} flag byte followed by an {@code int32} length. */
    private static byte[] readBytes(MessageReader in) {
        int len = in.uint8();
        if((len & 128) != 0)
            len = in.int32();
        return in.bytes(len);
    }

    private static List<Object> resSpec(MessageReader in) {
        List<Object> spec = new ArrayList<>(2);
        spec.add(in.string());
        spec.add((long) in.uint16());
        return spec;
    }

    private static void writeValue(MessageWriter out, Object o) {
        if(o == null) {
            out.uint8(T_NIL);
            return;
        }
        if(o instanceof String) {
            out.uint8(T_STR).string((String) o);
            return;
        }
        if(!(o instanceof Map) || ((Map<?, ?>) o).size() != 1)
            throw new Unsupported("props value must be a string or a single-tag object");
        Map.Entry<?, ?> e = ((Map<?, ?>) o).entrySet().iterator().next();
        String tag = String.valueOf(e.getKey());
        Object v = e.getValue();
        switch(tag) {
            case "u8":       out.uint8(T_UINT8).uint8(Nums.u8(v)); break;
            case "u16":      out.uint8(T_UINT16).uint16(Nums.u16(v)); break;
            case "i8":       out.uint8(T_INT8).int8(Nums.i8(v)); break;
            case "i16":      out.uint8(T_INT16).int16(Nums.i16(v)); break;
            case "int":      out.uint8(T_INT).int32(Nums.i32(v)); break;
            case "long":     out.uint8(T_LONG).int64(((Number) v).longValue()); break;
            case "f32":      out.uint8(T_FLOAT32).float32((float) ((Number) v).doubleValue()); break;
            case "f64":      out.uint8(T_FLOAT64).float64(((Number) v).doubleValue()); break;
            case "color":    writeColor(out, v); break;
            case "fcolor":   writeFloat32Array(out, T_FCOLOR, v, 4, "fcolor"); break;
            case "coord":    writeCoord(out, v); break;
            case "fcoord32": writeFloat32Array(out, T_FCOORD32, v, 2, "fcoord32"); break;
            case "fcoord64": writeFloat64Array(out, T_FCOORD64, v, 2, "fcoord64"); break;
            case "bytes":    writeBytes(out, v); break;
            case "uid":      out.uint8(T_UID).int64(((Number) v).longValue()); break;
            case "resid":    out.uint8(T_RESID).uint16(Nums.u16(v)); break;
            case "resspec":  writeResSpec(out, v); break;
            case "list":     writeList(out, v); break;
            case "map":      writeMap(out, v); break;
            default:         throw new Unsupported("unknown props value tag '" + tag + "'");
        }
    }

    private static void writeColor(MessageWriter out, Object v) {
        out.uint8(T_COLOR);
        for(Object c : asArray(v, 4, "color"))
            out.uint8(Nums.u8(c));
    }

    private static void writeCoord(MessageWriter out, Object v) {
        out.uint8(T_COORD);
        for(Object c : asArray(v, 2, "coord"))
            out.int32(Nums.i32(c));
    }

    private static void writeFloat32Array(MessageWriter out, int type, Object v, int n, String what) {
        out.uint8(type);
        for(Object c : asArray(v, n, what))
            out.float32((float) ((Number) c).doubleValue());
    }

    private static void writeFloat64Array(MessageWriter out, int type, Object v, int n, String what) {
        out.uint8(type);
        for(Object c : asArray(v, n, what))
            out.float64(((Number) c).doubleValue());
    }

    /** Writes a {@code T_BYTES} blob, mirroring {@code Message.addtto}'s length prefix:
     *  a single {@code uint8} for lengths under 128, else a {@code 0x80} flag byte and
     *  an {@code int32} length. */
    private static void writeBytes(MessageWriter out, Object v) {
        if(!(v instanceof String))
            throw new Unsupported("bytes must be a base64 string");
        byte[] b = Base64.getDecoder().decode((String) v);
        out.uint8(T_BYTES);
        if(b.length < 128)
            out.uint8(b.length);
        else
            out.uint8(0x80).int32(b.length);
        out.bytes(b);
    }

    private static void writeResSpec(MessageWriter out, Object v) {
        List<?> spec = asArray(v, 2, "resspec");
        if(!(spec.get(0) instanceof String))
            throw new Unsupported("resspec name must be a string");
        out.uint8(T_RESSPEC).string((String) spec.get(0)).uint16(Nums.u16(spec.get(1)));
    }

    private static void writeList(MessageWriter out, Object v) {
        if(!(v instanceof List))
            throw new Unsupported("list value must be an array");
        out.uint8(T_TTOL);
        for(Object item : (List<?>) v)
            writeValue(out, item);
        out.uint8(T_END);
    }

    private static void writeMap(MessageWriter out, Object v) {
        if(!(v instanceof Map))
            throw new Unsupported("map value must be an object");
        out.uint8(T_MAP);
        for(Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
            out.uint8(T_STR).string(String.valueOf(e.getKey()));
            writeValue(out, e.getValue());
        }
        out.uint8(T_END);
    }

    private static List<?> asArray(Object v, int n, String what) {
        if(!(v instanceof List) || ((List<?>) v).size() != n)
            throw new Unsupported(what + " must be a " + n + "-element array");
        return (List<?>) v;
    }

    private static Map<String, Object> tag(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(name, value);
        return m;
    }
}
