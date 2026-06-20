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
 * Typed editor for the {@code obst} layer (from haven.Resource.Obstacle): an
 * object's movement-collision shape — one or more polygons that block walking.
 *
 * <pre>
 *   uint8  ver                  (1 or 2)
 *   if ver == 2: string id
 *   uint8  n                    number of polygons
 *   uint8  count[0..n-1]        each polygon's point count (all counts first)
 *   then, per polygon, per point: float16 x, float16 y
 * </pre>
 *
 * The coordinates are stored as {@code float16} (tile units; the client scales by
 * the tile size). Because half-precision is lossy in general, the layer is
 * exposed as editable JSON only under the lossless-or-raw guard
 * ({@link #toJsonIfLossless}): decode → JSON → encode must reproduce the original
 * bytes exactly, otherwise it stays raw. The points are kept at their exact
 * decoded values, so an unchanged layer round-trips byte-for-byte; an edited
 * coordinate is stored at the same float16 precision the game itself uses.
 */
public final class ObstCodec {
    private ObstCodec() {
    }

    public static class Unsupported extends RuntimeException {
        public Unsupported(String msg) {
            super(msg);
        }
    }

    /** Decodes an obst payload into a {@code {"version","id"?,"polygons"}} model. */
    public static Map<String, Object> decode(byte[] payload) {
        MessageReader in = new MessageReader(payload);
        int ver = in.uint8();
        if(ver < 1 || ver > 2)
            throw new Unsupported("obst version " + ver);
        String id = (ver >= 2) ? in.string() : null;
        int n = in.uint8();
        int[] counts = new int[n];
        for(int i = 0; i < n; i++)
            counts[i] = in.uint8();
        List<Object> polygons = new ArrayList<>(n);
        for(int i = 0; i < n; i++) {
            List<Object> pts = new ArrayList<>(counts[i]);
            for(int o = 0; o < counts[i]; o++) {
                List<Object> pt = new ArrayList<>(2);
                pt.add((double) in.float16());
                pt.add((double) in.float16());
                pts.add(pt);
            }
            polygons.add(pts);
        }
        if(!in.eom())
            throw new Unsupported("trailing data after obst polygons");
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("version", (long) ver);
        if(id != null)
            model.put("id", id);
        model.put("polygons", polygons);
        return model;
    }

    /** Encodes a {@code {"version","id"?,"polygons"}} model back into payload bytes. */
    public static byte[] encode(Map<String, Object> model) {
        Object verObj = model.get("version");
        if(!(verObj instanceof Number))
            throw new Unsupported("missing/invalid version");
        int ver = ((Number) verObj).intValue();
        if(ver < 1 || ver > 2)
            throw new Unsupported("obst version " + ver);
        Object polysObj = model.get("polygons");
        if(!(polysObj instanceof List))
            throw new Unsupported("missing/invalid polygons list");
        List<?> polys = (List<?>) polysObj;
        if(polys.size() > 255)
            throw new Unsupported("too many polygons (max 255)");

        MessageWriter out = new MessageWriter();
        out.uint8(ver);
        if(ver >= 2) {
            Object id = model.get("id");
            if(!(id instanceof String))
                throw new Unsupported("version 2 obst requires a string id");
            out.string((String) id);
        }
        out.uint8(polys.size());
        for(Object p : polys) {
            if(!(p instanceof List))
                throw new Unsupported("polygon is not an array of points");
            int sz = ((List<?>) p).size();
            if(sz > 255)
                throw new Unsupported("too many points in a polygon (max 255)");
            out.uint8(sz);
        }
        for(Object p : polys) {
            for(Object ptObj : (List<?>) p) {
                if(!(ptObj instanceof List) || ((List<?>) ptObj).size() != 2)
                    throw new Unsupported("point must be a 2-element [x,y] array");
                List<?> pt = (List<?>) ptObj;
                out.float16(num(pt.get(0)));
                out.float16(num(pt.get(1)));
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

    private static float num(Object o) {
        if(!(o instanceof Number))
            throw new Unsupported("coordinate must be a number");
        return ((Number) o).floatValue();
    }
}
