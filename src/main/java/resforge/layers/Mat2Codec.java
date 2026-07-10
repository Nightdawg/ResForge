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
 * Typed editor for the {@code mat2} (material) layer. Its payload is a
 * {@code uint16} id followed, until end of message, by a sequence of material
 * commands — each a NUL-terminated string key plus a {@code tto} value list
 * terminated by {@code T_END} (0x00):
 *
 * <pre>
 *   uint16 id
 *   repeat until EOF:
 *       string key                 (e.g. "col", "tex", "light")
 *       tto values until T_END     (the arguments for that command)
 * </pre>
 *
 * It is exposed as editable JSON, but only when the round-trip is provably
 * lossless — {@link #toJsonIfLossless} decodes, re-serializes to JSON,
 * re-encodes, and returns the JSON only if it reproduces the original bytes
 * exactly. Otherwise the caller falls back to raw passthrough, so a mat2 layer
 * can never be corrupted.
 *
 * <p>Each value is rendered with an explicit type tag so the exact {@code tto}
 * encoding is preserved (a string stays a plain JSON string; everything else is
 * a single-entry object such as {@code {"color":[204,204,204,255]}},
 * {@code {"f32":0.5}} or {@code {"u8":0}}). Commands carrying a {@code tto} type
 * this codec does not model (coord, bytes, norm numbers, resource specs, …)
 * simply keep the layer raw.
 */
public final class Mat2Codec {
    private static final int MAX_TTO_DEPTH = 256;

    private static final int T_END = 0;
    private static final int T_INT = 1;
    private static final int T_STR = 2;
    private static final int T_UINT8 = 4;
    private static final int T_UINT16 = 5;
    private static final int T_COLOR = 6;
    private static final int T_TTOL = 8;
    private static final int T_INT8 = 9;
    private static final int T_INT16 = 10;
    private static final int T_NIL = 12;
    private static final int T_FLOAT32 = 15;
    private static final int T_FLOAT64 = 16;
    private static final int T_MAP = 32;
    private static final int T_LONG = 33;

    private Mat2Codec() {
    }

    public static class Unsupported extends RuntimeException {
        public Unsupported(String msg) {
            super(msg);
        }
    }

    /** Decodes a mat2 payload into a {@code {"id":N,"entries":[{key,values}]}} model. */
    public static Map<String, Object> decode(byte[] payload) {
        MessageReader in = new MessageReader(payload);
        int id = in.uint16();
        List<Object> entries = new ArrayList<>();
        while(!in.eom()) {
            String key = in.string();
            List<Object> values = new ArrayList<>();
            int t;
            while((t = in.uint8()) != T_END)
                values.add(readValue(in, t, 0));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);
            entry.put("values", values);
            entries.add(entry);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", (long) id);
        model.put("entries", entries);
        return model;
    }

    /** Encodes a {@code {"id":N,"entries":[{key,values}]}} model back into payload bytes. */
    public static byte[] encode(Map<String, Object> model) {
        Object idObj = model.get("id");
        if(!(idObj instanceof Number))
            throw new Unsupported("missing/invalid id");
        Object entriesObj = model.get("entries");
        if(!(entriesObj instanceof List))
            throw new Unsupported("missing/invalid entries list");
        MessageWriter out = new MessageWriter();
        out.uint16(Nums.u16(idObj));
        for(Object eObj : (List<?>) entriesObj) {
            if(!(eObj instanceof Map))
                throw new Unsupported("entry is not an object");
            Map<?, ?> entry = (Map<?, ?>) eObj;
            Object key = entry.get("key");
            if(!(key instanceof String))
                throw new Unsupported("entry missing string key");
            Object values = entry.get("values");
            if(!(values instanceof List))
                throw new Unsupported("entry missing values list");
            out.string((String) key);
            for(Object v : (List<?>) values)
                writeValue(out, v);
            out.uint8(T_END);
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
            case T_STR:     return in.string();
            case T_NIL:     return null;
            case T_UINT8:   return tag("u8", (long) in.uint8());
            case T_UINT16:  return tag("u16", (long) in.uint16());
            case T_INT8:    return tag("i8", (long) in.int8());
            case T_INT16:   return tag("i16", (long) in.int16());
            case T_INT:     return tag("int", (long) in.int32());
            case T_LONG:    return tag("long", in.int64());
            case T_FLOAT32: return tag("f32", (double) in.float32());
            case T_FLOAT64: return tag("f64", in.float64());
            case T_COLOR:   return tag("color", colorList(in));
            case T_TTOL:    return tag("list", readList(in, depth + 1));
            case T_MAP:     return tag("map", readMap(in, depth + 1));
            default:        throw new Unsupported("tto type tag " + t);
        }
    }

    private static List<Object> colorList(MessageReader in) {
        List<Object> rgba = new ArrayList<>(4);
        for(int i = 0; i < 4; i++)
            rgba.add((long) in.uint8());
        return rgba;
    }

    private static List<Object> readList(MessageReader in, int depth) {
        if(depth > MAX_TTO_DEPTH)
            throw new Unsupported("tto nesting too deep");
        List<Object> list = new ArrayList<>();
        int t;
        while((t = in.uint8()) != T_END)
            list.add(readValue(in, t, depth));
        return list;
    }

    private static Map<String, Object> readMap(MessageReader in, int depth) {
        if(depth > MAX_TTO_DEPTH)
            throw new Unsupported("tto nesting too deep");
        Map<String, Object> map = new LinkedHashMap<>();
        int t;
        while((t = in.uint8()) != T_END) {
            Object key = readValue(in, t, depth);
            if(!(key instanceof String))
                throw new Unsupported("non-string map key");
            map.put((String) key, readValue(in, in.uint8(), depth));
        }
        return map;
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
            throw new Unsupported("value must be a string or a single-tag object");
        Map.Entry<?, ?> e = ((Map<?, ?>) o).entrySet().iterator().next();
        String tag = String.valueOf(e.getKey());
        Object v = e.getValue();
        switch(tag) {
            case "u8":    out.uint8(T_UINT8).uint8(Nums.u8(v)); break;
            case "u16":   out.uint8(T_UINT16).uint16(Nums.u16(v)); break;
            case "i8":    out.uint8(T_INT8).int8(Nums.i8(v)); break;
            case "i16":   out.uint8(T_INT16).int16(Nums.i16(v)); break;
            case "int":   out.uint8(T_INT).int32(Nums.i32(v)); break;
            case "long":  out.uint8(T_LONG).int64(((Number) v).longValue()); break;
            case "f32":   out.uint8(T_FLOAT32).float32((float) ((Number) v).doubleValue()); break;
            case "f64":   out.uint8(T_FLOAT64).float64(((Number) v).doubleValue()); break;
            case "color": writeColor(out, v); break;
            case "list":  writeList(out, v); break;
            case "map":   writeMap(out, v); break;
            default:      throw new Unsupported("unknown value tag '" + tag + "'");
        }
    }

    private static void writeColor(MessageWriter out, Object v) {
        if(!(v instanceof List) || ((List<?>) v).size() != 4)
            throw new Unsupported("color must be a 4-element [r,g,b,a] array");
        out.uint8(T_COLOR);
        for(Object c : (List<?>) v)
            out.uint8(Nums.u8(c));
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

    private static Map<String, Object> tag(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(name, value);
        return m;
    }
}
