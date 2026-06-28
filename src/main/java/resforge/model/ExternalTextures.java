package resforge.model;

import resforge.layers.Mat2Codec;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves <em>external static</em> material textures for the 3D viewer: a material
 * whose base colour comes from an {@code mlink}/external {@code tex} string naming
 * <strong>one fixed resource</strong> (e.g. mulberry's bark via
 * {@code mlink gfx/terobjs/trees/mulberry-tex}, or knarr's hull/sail). Unlike a local
 * {@code tex}, the image lives in <em>another</em> {@code .res}; this class follows the
 * link by <strong>fetching</strong> that resource (through an injectable {@link Fetcher})
 * and resolving <em>its</em> own {@code matid→mat2→tex} chain with {@link LocalTextures}.
 *
 * <p>The link forms (confirmed against live data):
 * <ul>
 *   <li>external {@code mlink} = {@code [respath, version, matid]} — fetch {@code respath},
 *       resolve that resource's material {@code matid};</li>
 *   <li>local {@code mlink} = {@code [{u8:matid}]} — a link to another <em>local</em>
 *       material, followed recursively (e.g. mulberry id&nbsp;14 → local id&nbsp;0 → external);</li>
 *   <li>external {@code tex} string = {@code [respath, …]} — best-effort: the linked
 *       resource's primary local texture.</li>
 * </ul>
 * An {@code otex} is an overlay, not a base, so it is ignored here. Only matids that
 * {@link LocalTextures} could <em>not</em> resolve locally are attempted (a locally
 * textured material is already handled by {@link ModelGeometry}); genuine runtime
 * {@code varmat} and {@code Dyntex} {@code spr} additions stay unresolved.
 *
 * <p>Offline-safe and deterministic: a {@code null} fetcher (or a fetch that returns
 * {@code null}) resolves nothing, so the viewer's default behaviour is unchanged.
 * Fetches are cached per path, {@link LocalTextures} per resource, and resolution is
 * depth-capped with a cycle guard, so a malformed/looping link graph fails quietly.
 */
public final class ExternalTextures {
    /** Fetches and parses the resource at a resource path, or returns {@code null}
     *  if it is unavailable (offline, 404, parse error). */
    public interface Fetcher {
        ResContainer fetch(String path);
    }

    /** A resolved texture: the raw encoded image bytes and its optional alpha mask. */
    public static final class Resolved {
        public final byte[] image;
        public final byte[] mask;

        Resolved(byte[] image, byte[] mask) {
            this.image = image;
            this.mask = mask;
        }
    }

    private static final int MAX_DEPTH = 8;

    private final Fetcher fetcher;
    private final Map<String, ResContainer> fetchCache = new LinkedHashMap<>();
    private final Map<ResContainer, LocalTextures> ltCache = new IdentityHashMap<>();
    private final Map<ResContainer, Map<Integer, Map<String, Object>>> matCache = new IdentityHashMap<>();

    private ExternalTextures(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Resolves external/linked base textures for the materials of {@code res} that the
     * local resolution could not handle. Returns a {@code matid → Resolved} map for the
     * resource's own materials (empty when {@code res}/{@code fetcher} is {@code null} or
     * nothing resolves). Never throws.
     */
    public static Map<Integer, Resolved> resolve(ResContainer res, Fetcher fetcher) {
        Map<Integer, Resolved> out = new LinkedHashMap<>();
        if(res == null || fetcher == null)
            return out;
        ExternalTextures self = new ExternalTextures(fetcher);
        LocalTextures lt = self.localOf(res);
        for(Integer matid : self.matsOf(res).keySet()) {
            if(lt.texForMatid(matid) != null)
                continue;   // already locally textured — ModelGeometry handles it
            try {
                Resolved r = self.resolveImage(res, matid, 0, new HashSet<>());
                if(r != null && r.image != null)
                    out.put(matid, r);
            } catch(RuntimeException ignored) {
            }
        }
        return out;
    }

    private Resolved resolveImage(ResContainer res, int matid, int depth, Set<String> visited) {
        if(res == null || depth > MAX_DEPTH)
            return null;
        String key = System.identityHashCode(res) + ":" + matid;
        if(!visited.add(key))
            return null;

        LocalTextures lt = localOf(res);
        byte[] localImg = lt.texForMatid(matid);
        if(localImg != null) {
            Integer ord = lt.ordForMatid(matid);
            byte[] mask = (ord != null) ? lt.masks.get(ord) : null;
            return new Resolved(localImg, mask);
        }

        Map<String, Object> mat = matsOf(res).get(matid);
        if(mat == null)
            return null;
        Object entriesObj = mat.get("entries");
        if(!(entriesObj instanceof List))
            return null;
        for(Object eo : (List<?>) entriesObj) {
            if(!(eo instanceof Map))
                continue;
            Map<?, ?> e = (Map<?, ?>) eo;
            String k = String.valueOf(e.get("key"));
            Object valsObj = e.get("values");
            if(!(valsObj instanceof List))
                continue;
            List<?> vals = (List<?>) valsObj;
            if(vals.isEmpty())
                continue;
            Object v0 = vals.get(0);
            if(k.equals("mlink")) {
                if(v0 instanceof String) {
                    ResContainer linked = fetch((String) v0);
                    Integer tm = lastInt(vals);
                    Resolved r = (tm != null)
                            ? resolveImage(linked, tm, depth + 1, visited)
                            : primary(linked);
                    if(r != null)
                        return r;
                } else {
                    Integer local = asInt(v0);
                    if(local != null) {
                        Resolved r = resolveImage(res, local, depth + 1, visited);
                        if(r != null)
                            return r;
                    }
                }
            } else if(k.equals("tex") && v0 instanceof String) {
                Resolved r = primary(fetch((String) v0));
                if(r != null)
                    return r;
            }
            // a numeric local `tex` would already have resolved above; `otex` is an overlay.
        }
        return null;
    }

    /** The linked resource's primary (first non-null) local texture. */
    private Resolved primary(ResContainer res) {
        if(res == null)
            return null;
        LocalTextures lt = localOf(res);
        for(int i = 0; i < lt.images.size(); i++)
            if(lt.images.get(i) != null)
                return new Resolved(lt.images.get(i), lt.masks.get(i));
        return null;
    }

    private ResContainer fetch(String path) {
        if(path == null)
            return null;
        if(fetchCache.containsKey(path))
            return fetchCache.get(path);
        ResContainer r = null;
        try {
            r = fetcher.fetch(path);
        } catch(RuntimeException ignored) {
        }
        fetchCache.put(path, r);
        return r;
    }

    private LocalTextures localOf(ResContainer res) {
        return ltCache.computeIfAbsent(res, LocalTextures::from);
    }

    private Map<Integer, Map<String, Object>> matsOf(ResContainer res) {
        return matCache.computeIfAbsent(res, r -> {
            Map<Integer, Map<String, Object>> m = new LinkedHashMap<>();
            for(Layer l : r.layers) {
                if(!l.name.equals("mat2"))
                    continue;
                try {
                    Map<String, Object> dec = Mat2Codec.decode(l.data);
                    Object id = dec.get("id");
                    if(id instanceof Number)
                        m.putIfAbsent(((Number) id).intValue(), dec);
                } catch(RuntimeException ignored) {
                }
            }
            return m;
        });
    }

    /** An integer from a bare {@code Number} or a single-tag value object ({@code {"u8":N}}). */
    private static Integer asInt(Object o) {
        if(o instanceof Number)
            return ((Number) o).intValue();
        if(o instanceof Map && ((Map<?, ?>) o).size() == 1) {
            Object v = ((Map<?, ?>) o).values().iterator().next();
            if(v instanceof Number)
                return ((Number) v).intValue();
        }
        return null;
    }

    /** The last numeric value after the leading respath — the linked matid in
     *  {@code [respath, version, matid]}. */
    private static Integer lastInt(List<?> vals) {
        Integer last = null;
        for(int i = 1; i < vals.size(); i++) {
            Integer n = asInt(vals.get(i));
            if(n != null)
                last = n;
        }
        return last;
    }
}
