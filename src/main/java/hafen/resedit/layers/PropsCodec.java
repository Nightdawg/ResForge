package hafen.resedit.layers;

import hafen.resedit.io.Json;
import hafen.resedit.io.MessageReader;
import hafen.resedit.io.MessageWriter;

import java.util.ArrayList;
import java.util.Arrays;
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
 * Only JSON-native tto types are handled (string, integer, float64, nested
 * list/map, nil); any layer using other types (coord, color, bytes, float32,
 * norm numbers, resource specs, …) simply stays raw.
 */
public final class PropsCodec {
    private static final int T_END = 0;
    private static final int T_INT = 1;
    private static final int T_STR = 2;
    private static final int T_UINT8 = 4;
    private static final int T_UINT16 = 5;
    private static final int T_TTOL = 8;
    private static final int T_INT8 = 9;
    private static final int T_INT16 = 10;
    private static final int T_NIL = 12;
    private static final int T_FLOAT64 = 16;
    private static final int T_MAP = 32;
    private static final int T_LONG = 33;

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
            flat.add(readValue(in, t));
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
        out.uint8(((Number) verObj).intValue());
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

    private static Object readValue(MessageReader in, int t) {
        switch(t) {
            case T_STR:     return in.string();
            case T_INT:     return (long) in.int32();
            case T_UINT8:   return (long) in.uint8();
            case T_UINT16:  return (long) in.uint16();
            case T_INT8:    return (long) in.int8();
            case T_INT16:   return (long) in.int16();
            case T_LONG:    return in.int64();
            case T_FLOAT64: return in.float64();
            case T_NIL:     return null;
            case T_TTOL:    return readList(in);
            case T_MAP:     return readMap(in);
            default:        throw new Unsupported("tto type tag " + t);
        }
    }

    private static List<Object> readList(MessageReader in) {
        List<Object> list = new ArrayList<>();
        while(!in.eom()) {
            int t = in.uint8();
            if(t == T_END)
                break;
            list.add(readValue(in, t));
        }
        return list;
    }

    private static Map<String, Object> readMap(MessageReader in) {
        Map<String, Object> map = new LinkedHashMap<>();
        while(!in.eom()) {
            int t = in.uint8();
            if(t == T_END)
                break;
            Object key = readValue(in, t);
            if(!(key instanceof String))
                throw new Unsupported("non-string map key");
            Object val = readValue(in, in.uint8());
            map.put((String) key, val);
        }
        return map;
    }

    private static void writeValue(MessageWriter out, Object o) {
        if(o == null) {
            out.uint8(T_NIL);
        } else if(o instanceof String) {
            out.uint8(T_STR).string((String) o);
        } else if(o instanceof Long || o instanceof Integer) {
            writeInt(out, ((Number) o).longValue());
        } else if(o instanceof Double || o instanceof Float) {
            out.uint8(T_FLOAT64).float64(((Number) o).doubleValue());
        } else if(o instanceof List) {
            out.uint8(T_TTOL);
            for(Object item : (List<?>) o)
                writeValue(out, item);
            out.uint8(T_END);
        } else if(o instanceof Map) {
            out.uint8(T_MAP);
            for(Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                writeValue(out, String.valueOf(e.getKey()));
                writeValue(out, e.getValue());
            }
            out.uint8(T_END);
        } else {
            throw new Unsupported("cannot encode " + o.getClass().getSimpleName() + " as props value");
        }
    }

    /** Encodes an integer using the same canonical smallest-type rule as haven.Message.addtto. */
    private static void writeInt(MessageWriter out, long v) {
        if(v >= 0 && v < 256) {
            out.uint8(T_UINT8).uint8((int) v);
        } else if(v >= 0 && v < 65536) {
            out.uint8(T_UINT16).uint16((int) v);
        } else if(v >= -128 && v < 0) {
            out.uint8(T_INT8).int8((int) v);
        } else if(v >= -32768 && v < 0) {
            out.uint8(T_INT16).int16((int) v);
        } else if(v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
            out.uint8(T_INT).int32((int) v);
        } else {
            out.uint8(T_LONG).int64(v);
        }
    }
}
