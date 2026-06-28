package resforge.model;

import resforge.layers.Mat2Codec;
import resforge.layers.TexInfo;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a resource's <em>local</em> textures and the {@code matid → texture}
 * mapping for the 3D viewer, mirroring the chain the glTF export uses:
 * a {@code mesh}'s {@code matid} selects a {@code mat2} (by id) whose
 * {@code tex}/{@code otex} command's first value is the <em>id</em> of one of this
 * resource's own {@code tex} layers (the client's {@code flayer(TexR.class, id)}
 * lookup — an id, not a positional index).
 *
 * <p>Only <strong>local</strong> textures are handled here. Materials that point
 * at an external texture (an {@code mlink}/string respath in another resource) or
 * that are supplied by a <em>variable material</em> (a {@code code}/{@code codeentry}
 * "varmat") are <em>not</em> resolved — {@link #texForMatid} returns {@code -1} —
 * and the caller falls back to flat shading for those parts. Resolving variable
 * materials is a planned follow-on (Tier 2 part 2).
 */
public final class LocalTextures {
    /** Raw encoded image bytes (PNG/JPEG) for each local {@code tex} layer, in order. */
    public final List<byte[]> images = new ArrayList<>();
    /** Raw alpha-mask bytes (tag 4) per local {@code tex} layer, aligned with
     *  {@link #images}; {@code null} where a texture has no mask. */
    public final List<byte[]> masks = new ArrayList<>();
    private final Map<Integer, Integer> matidToTex = new LinkedHashMap<>();

    private LocalTextures() {}

    /** Build the local-texture table + matid map for a resource. Never throws. */
    public static LocalTextures from(ResContainer res) {
        LocalTextures lt = new LocalTextures();
        Map<Integer, Integer> texIdToOrd = new LinkedHashMap<>();   // tex layer id -> ordinal
        for(Layer l : res.layers) {
            if(!l.name.equals("tex"))
                continue;
            try {
                TexInfo ti = TexInfo.parse(l.data);
                if(ti.recognized)
                    texIdToOrd.putIfAbsent(ti.id, lt.images.size());
                lt.images.add(ti.found
                        ? Arrays.copyOfRange(l.data, ti.imageOffset, ti.imageOffset + ti.imageLen)
                        : null);
                lt.masks.add(ti.maskFound
                        ? Arrays.copyOfRange(l.data, ti.maskOffset, ti.maskOffset + ti.maskLen)
                        : null);
            } catch(RuntimeException e) {
                lt.images.add(null);
                lt.masks.add(null);
            }
        }
        for(Layer l : res.layers) {
            if(!l.name.equals("mat2"))
                continue;
            try {
                Map<String, Object> m = Mat2Codec.decode(l.data);
                int id = ((Number) m.get("id")).intValue();
                Integer ord = firstLocalTexOrdinal((List<?>) m.get("entries"), texIdToOrd);
                if(ord != null && lt.images.get(ord) != null)
                    lt.matidToTex.put(id, ord);
            } catch(RuntimeException ignored) {
            }
        }
        return lt;
    }

    /** The raw image bytes for a mesh's {@code matid}, or {@code null} if the
     *  material has no resolvable local texture. */
    public byte[] texForMatid(int matid) {
        Integer ord = matidToTex.get(matid);
        return (ord == null) ? null : images.get(ord);
    }

    /** The local-texture ordinal for a mesh's {@code matid}, or {@code null}. */
    public Integer ordForMatid(int matid) {
        return matidToTex.get(matid);
    }

    /** True if any material resolves to a local texture. */
    public boolean any() {
        return !matidToTex.isEmpty();
    }

    /* The first tex/otex command whose first value is a local texture id, mapped to
     * its tex-layer ordinal. Mirrors GltfExport.firstLocalTexOrdinal — a string first
     * value means an external (mlink) texture, which is skipped. The numeric first
     * value is the tex layer's own id (the client's flayer(TexR.class, id) lookup),
     * not its position, so it is resolved through the id->ordinal map. */
    private static Integer firstLocalTexOrdinal(List<?> entries, Map<Integer, Integer> texIdToOrd) {
        for(Object e : entries) {
            Map<?, ?> entry = (Map<?, ?>) e;
            String key = String.valueOf(entry.get("key"));
            if(!key.equals("tex") && !key.equals("otex"))
                continue;
            List<?> vals = (List<?>) entry.get("values");
            if(vals.isEmpty() || vals.get(0) instanceof String)
                continue;
            Object first = vals.get(0);
            if(first instanceof Map) {
                Object v = ((Map<?, ?>) first).values().iterator().next();
                if(v instanceof Number) {
                    Integer ord = texIdToOrd.get(((Number) v).intValue());
                    if(ord != null)
                        return ord;
                }
            }
        }
        return null;
    }
}
