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
 * Typed editor for the {@code action} layer (from haven.Resource.AButton): a
 * fully deterministic, fixed-shape record exposed as editable JSON.
 *
 * Action layer format:
 * <pre>
 *   string parent          (parent resource name, or "")
 *   uint16 parentVer
 *   string name
 *   string prereq          (prerequisite skill, often "")
 *   uint16 hotkey          (a key code; ASCII for letter keys)
 *   uint16 adCount
 *   string[adCount] ad     (action data / arguments)
 * </pre>
 *
 * The encoding has no type ambiguity, so decode/encode is exactly reversible;
 * {@link #toJsonIfLossless} still guards with a byte-for-byte round-trip check
 * and falls back to a raw passthrough on any surprise, so editing an action
 * layer can never corrupt a resource.
 */
public final class ActionCodec {
    private ActionCodec() {
    }

    public static class Unsupported extends RuntimeException {
        public Unsupported(String msg) {
            super(msg);
        }
    }

    public static Map<String, Object> decode(byte[] payload) {
        MessageReader in = new MessageReader(payload);
        String parent = in.string();
        int parentVer = in.uint16();
        String name = in.string();
        String prereq = in.string();
        int hotkey = in.uint16();
        int adCount = in.uint16();
        if(adCount < 0 || adCount > in.remaining())
            throw new Unsupported("implausible ad count " + adCount);
        List<Object> ad = new ArrayList<>(adCount);
        for(int i = 0; i < adCount; i++)
            ad.add(in.string());
        if(!in.eom())
            throw new Unsupported("trailing data after action record");

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("parent", parent);
        model.put("parentVer", (long) parentVer);
        model.put("name", name);
        model.put("prereq", prereq);
        model.put("hotkey", (long) hotkey);
        model.put("ad", ad);
        return model;
    }

    public static byte[] encode(Map<String, Object> model) {
        MessageWriter out = new MessageWriter();
        out.string(str(model, "parent"));
        out.uint16(u16(model, "parentVer"));
        out.string(str(model, "name"));
        out.string(str(model, "prereq"));
        out.uint16(u16(model, "hotkey"));
        Object adObj = model.get("ad");
        if(!(adObj instanceof List))
            throw new Unsupported("'ad' must be a list of strings");
        List<?> ad = (List<?>) adObj;
        out.uint16(ad.size());
        for(Object a : ad) {
            if(!(a instanceof String))
                throw new Unsupported("'ad' entries must be strings");
            out.string((String) a);
        }
        return out.toByteArray();
    }

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

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if(!(v instanceof String))
            throw new Unsupported("'" + key + "' must be a string");
        return (String) v;
    }

    private static int u16(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if(!(v instanceof Number))
            throw new Unsupported("'" + key + "' must be a number");
        long n = ((Number) v).longValue();
        if(n < 0 || n > 0xffff)
            throw new Unsupported("'" + key + "' out of uint16 range: " + n);
        return (int) n;
    }
}
