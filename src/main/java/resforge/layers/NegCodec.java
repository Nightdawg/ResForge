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
 * Typed editor for the {@code neg} layer (from haven.Resource.Neg): an object's
 * 2D interaction geometry — where you click it and any connection points.
 *
 * <pre>
 *   coord  center                 int16 x, int16 y  (the click hotspot)
 *   12 bytes = 3 coords           the client skips these; CarryGun reads them as
 *                                 tl/br/oc (top-left/bottom-right/object-center)
 *   uint8  en                     number of endpoint groups
 *   per group: uint8 id, uint16 n, n × coord
 * </pre>
 *
 * Every field is an {@code int16}/{@code uint8}/{@code uint16}, so the layer is
 * exactly reversible and is exposed as editable JSON
 * {@code {"center":[x,y],"bounds":[[x,y],[x,y],[x,y]],"endpoints":[{"id":N,"coords":[[x,y],…]}]}}
 * under the lossless-or-raw guard ({@link #toJsonIfLossless}). The endpoint
 * groups are kept as an ordered list (preserving order and any repeated ids) so
 * the bytes round-trip exactly.
 */
public final class NegCodec {
    private NegCodec() {
    }

    public static class Unsupported extends RuntimeException {
        public Unsupported(String msg) {
            super(msg);
        }
    }

    /** Decodes a neg payload into a {@code {"center","bounds","endpoints"}} model. */
    public static Map<String, Object> decode(byte[] payload) {
        MessageReader in = new MessageReader(payload);
        List<Object> center = coord(in);
        List<Object> bounds = new ArrayList<>(3);
        for(int i = 0; i < 3; i++)
            bounds.add(coord(in));
        int en = in.uint8();
        List<Object> endpoints = new ArrayList<>(en);
        for(int i = 0; i < en; i++) {
            int id = in.uint8();
            int n = in.uint16();
            List<Object> coords = new ArrayList<>(n);
            for(int o = 0; o < n; o++)
                coords.add(coord(in));
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("id", (long) id);
            group.put("coords", coords);
            endpoints.add(group);
        }
        if(!in.eom())
            throw new Unsupported("trailing data after neg endpoints");
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("center", center);
        model.put("bounds", bounds);
        model.put("endpoints", endpoints);
        return model;
    }

    /** Encodes a {@code {"center","bounds","endpoints"}} model back into payload bytes. */
    public static byte[] encode(Map<String, Object> model) {
        MessageWriter out = new MessageWriter();
        writeCoord(out, model.get("center"));
        Object bounds = model.get("bounds");
        if(!(bounds instanceof List) || ((List<?>) bounds).size() != 3)
            throw new Unsupported("bounds must be a 3-element array of [x,y] coords");
        for(Object c : (List<?>) bounds)
            writeCoord(out, c);
        Object endpoints = model.get("endpoints");
        if(!(endpoints instanceof List))
            throw new Unsupported("missing/invalid endpoints list");
        List<?> groups = (List<?>) endpoints;
        out.uint8(groups.size());
        for(Object gObj : groups) {
            if(!(gObj instanceof Map))
                throw new Unsupported("endpoint group is not an object");
            Map<?, ?> g = (Map<?, ?>) gObj;
            Object id = g.get("id");
            Object coords = g.get("coords");
            if(!(id instanceof Number))
                throw new Unsupported("endpoint group missing numeric id");
            if(!(coords instanceof List))
                throw new Unsupported("endpoint group missing coords list");
            out.uint8(((Number) id).intValue());
            out.uint16(((List<?>) coords).size());
            for(Object c : (List<?>) coords)
                writeCoord(out, c);
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

    private static List<Object> coord(MessageReader in) {
        List<Object> c = new ArrayList<>(2);
        c.add((long) in.int16());
        c.add((long) in.int16());
        return c;
    }

    private static void writeCoord(MessageWriter out, Object o) {
        if(!(o instanceof List) || ((List<?>) o).size() != 2)
            throw new Unsupported("coord must be a 2-element [x,y] array");
        List<?> c = (List<?>) o;
        if(!(c.get(0) instanceof Number) || !(c.get(1) instanceof Number))
            throw new Unsupported("coord components must be numbers");
        out.int16(((Number) c.get(0)).intValue());
        out.int16(((Number) c.get(1)).intValue());
    }
}
