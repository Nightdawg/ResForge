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
 * Typed editor for the {@code light} layer (from haven.Light.Res): a light source
 * attached to a resource, so the object emits light (a glow) when placed in the world.
 *
 * <p>It is exposed as editable JSON:
 * <pre>
 *   {"version": 0|1, "id": &lt;int&gt;,
 *    "ambient":[r,g,b,a], "diffuse":[r,g,b,a], "specular":[r,g,b,a],
 *    "attenuation":[ac,al,aq]?, "direction":[x,y,z]?, "exponent":&lt;num&gt;?}
 * </pre>
 * Colour components are the raw {@code 0..1} fractions the game stores (not 0–255), so
 * an unchanged layer round-trips byte-for-byte. The optional extras decide the light
 * kind: {@code attenuation} ⇒ point light, {@code attenuation}+{@code exponent} ⇒
 * spotlight, neither ⇒ directional.
 *
 * <p>Version 0 stores every value as a custom-packed float ({@code cpfloat}) and the id
 * as {@code int8}; version 1 uses {@code float32} and an {@code int16} id. Both
 * round-trip exactly (cpfloat via {@link MessageWriter#cpfloat}, float32 natively). Like
 * the other typed layers, it is offered as editable JSON only under the lossless-or-raw
 * guard ({@link #toJsonIfLossless}); the optional extras are re-emitted in tag order
 * (attenuation, direction, exponent), so a layer whose tags are stored in that
 * conventional order round-trips, otherwise it stays raw.
 */
public final class LightCodec {
    private LightCodec() {
    }

    public static class Unsupported extends RuntimeException {
        public Unsupported(String msg) {
            super(msg);
        }
    }

    /** Decodes a light payload into a {@code {"version","id","ambient",...}} model. */
    public static Map<String, Object> decode(byte[] payload) {
        MessageReader in = new MessageReader(payload);
        int ver = in.uint8();
        if(ver != 0 && ver != 1)
            throw new Unsupported("light version " + ver);
        boolean cp = (ver == 0);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("version", (long) ver);
        model.put("id", (long) (cp ? in.int8() : in.int16()));
        model.put("ambient", color(in, cp));
        model.put("diffuse", color(in, cp));
        model.put("specular", color(in, cp));
        while(!in.eom()) {
            int t = in.uint8();
            switch(t) {
                case 1:
                    model.put("attenuation", vec(in, cp, 3));
                    break;
                case 2:
                    model.put("direction", vec(in, cp, 3));
                    break;
                case 3:
                    model.put("exponent", num(in, cp));
                    break;
                default:
                    throw new Unsupported("light data tag " + t);
            }
        }
        return model;
    }

    /** Encodes a {@code {"version","id",...}} model back into payload bytes. */
    public static byte[] encode(Map<String, Object> model) {
        Object verObj = model.get("version");
        if(!(verObj instanceof Number))
            throw new Unsupported("missing/invalid version");
        int ver = ((Number) verObj).intValue();
        if(ver != 0 && ver != 1)
            throw new Unsupported("light version " + ver);
        boolean cp = (ver == 0);

        MessageWriter out = new MessageWriter();
        out.uint8(ver);
        int id = intVal(model.get("id"), "id");
        if(cp)
            out.int8(id);
        else
            out.int16(id);
        writeColor(out, cp, model.get("ambient"), "ambient");
        writeColor(out, cp, model.get("diffuse"), "diffuse");
        writeColor(out, cp, model.get("specular"), "specular");
        if(model.containsKey("attenuation")) {
            out.uint8(1);
            writeVec(out, cp, model.get("attenuation"), "attenuation", 3);
        }
        if(model.containsKey("direction")) {
            out.uint8(2);
            writeVec(out, cp, model.get("direction"), "direction", 3);
        }
        if(model.containsKey("exponent")) {
            out.uint8(3);
            writeNum(out, cp, num(model.get("exponent")));
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

    private static List<Object> color(MessageReader in, boolean cp) {
        return vec(in, cp, 4);
    }

    private static List<Object> vec(MessageReader in, boolean cp, int n) {
        List<Object> v = new ArrayList<>(n);
        for(int i = 0; i < n; i++)
            v.add(num(in, cp));
        return v;
    }

    private static double num(MessageReader in, boolean cp) {
        return cp ? in.cpfloat() : (double) in.float32();
    }

    private static void writeColor(MessageWriter out, boolean cp, Object o, String what) {
        writeVec(out, cp, o, what, 4);
    }

    private static void writeVec(MessageWriter out, boolean cp, Object o, String what, int n) {
        if(!(o instanceof List) || ((List<?>) o).size() != n)
            throw new Unsupported("\"" + what + "\" must be a " + n + "-element array");
        for(Object e : (List<?>) o)
            writeNum(out, cp, num(e));
    }

    private static void writeNum(MessageWriter out, boolean cp, double v) {
        if(cp)
            out.cpfloat(v);
        else
            out.float32((float) v);
    }

    private static double num(Object o) {
        if(!(o instanceof Number))
            throw new Unsupported("expected a number, got " + o);
        return ((Number) o).doubleValue();
    }

    private static int intVal(Object o, String what) {
        if(!(o instanceof Number))
            throw new Unsupported("\"" + what + "\" must be an integer");
        return ((Number) o).intValue();
    }
}
