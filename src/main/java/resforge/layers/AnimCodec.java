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
 * Typed editor for the {@code anim} layer (from haven.Resource.Anim): a simple,
 * fully deterministic sprite-animation record —
 *
 * <pre>
 *   int16  id        (animation id; often -1)
 *   uint16 delay     (frame duration in ms — the animation speed)
 *   uint16 n         (frame count)
 *   int16[n] frames  (the image-layer id shown in each frame)
 * </pre>
 *
 * The frames reference {@code image} layers in the same resource by their id, so
 * editing an {@code anim} changes the animation speed or the frame sequence while
 * the pixels stay in the (separately editable) image layers.
 *
 * Because the format has no type ambiguity it is exposed as editable JSON
 * {@code {"id":…,"delay":…,"frames":[…]}} with the usual lossless-or-raw guard:
 * {@link #toJsonIfLossless} only offers JSON when decode → encode reproduces the
 * original bytes exactly.
 */
public final class AnimCodec {
    private AnimCodec() {
    }

    public static class Unsupported extends RuntimeException {
        public Unsupported(String msg) {
            super(msg);
        }
    }

    /** Decodes an anim payload into a {@code {"id":N,"delay":N,"frames":[…]}} model. */
    public static Map<String, Object> decode(byte[] payload) {
        MessageReader in = new MessageReader(payload);
        int id = in.int16();
        int delay = in.uint16();
        int n = in.uint16();
        List<Object> frames = new ArrayList<>(n);
        for(int i = 0; i < n; i++)
            frames.add((long) in.int16());
        if(!in.eom())
            throw new Unsupported("trailing data after anim frames");
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", (long) id);
        model.put("delay", (long) delay);
        model.put("frames", frames);
        return model;
    }

    /** Encodes a {@code {"id":N,"delay":N,"frames":[…]}} model back into payload bytes. */
    public static byte[] encode(Map<String, Object> model) {
        Object id = model.get("id");
        Object delay = model.get("delay");
        Object frames = model.get("frames");
        if(!(id instanceof Number))
            throw new Unsupported("missing/invalid id");
        if(!(delay instanceof Number))
            throw new Unsupported("missing/invalid delay");
        if(!(frames instanceof List))
            throw new Unsupported("missing/invalid frames list");
        List<?> f = (List<?>) frames;
        MessageWriter out = new MessageWriter();
        out.int16(((Number) id).intValue());
        out.uint16(((Number) delay).intValue());
        out.uint16(f.size());
        for(Object fr : f) {
            if(!(fr instanceof Number))
                throw new Unsupported("frame id is not a number");
            out.int16(((Number) fr).intValue());
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
}
