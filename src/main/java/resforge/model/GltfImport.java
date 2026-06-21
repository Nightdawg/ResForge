package resforge.model;

import resforge.io.Json;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports a binary glTF ({@code .glb}) back into a resource's geometry — the
 * "edit in Blender, then re-import" half of the 3D round-trip. It is a
 * <em>patch</em>: only the {@code vbuf2} vertex data is re-encoded from the glTF;
 * every other layer (mesh/triangles, skeleton, bone weights, materials, textures,
 * code, …) is carried over from the original {@code .res} byte-for-byte.
 *
 * <p>Blender (and most DCC tools) re-split vertices at UV/normal seams on export,
 * so the glTF vertex count rarely matches the original. To survive that, the
 * exporter tags every vertex with a stable id ({@code _VID}); on import each glTF
 * vertex is mapped back to its original {@code vbuf2} index via that id, so
 * re-split/reordered/duplicated vertices all land correctly regardless of count.
 * Each attribute the glTF provides (positions, normals, both UV sets) is
 * re-quantised into that attribute's original on-wire format — the same precision
 * the game renders from — and Y-up glTF coordinates are converted back to Haven's
 * Z-up. Bone weights and the triangle lists are kept from the original, so
 * skinning and submeshes stay intact.
 *
 * <p>For the {@code _VID} ids to survive a Blender round-trip you must enable
 * <em>Data → Mesh → Attributes</em> in Blender's glTF exporter. A glTF without ids
 * can still be imported only if its vertex count and order exactly match the
 * original (e.g. ResForge's own unedited export, or a tool that preserves order).
 */
public final class GltfImport {
    private GltfImport() {
    }

    public static final class Result {
        public final byte[] res;
        public final int vertices;     // original vbuf2 vertex count
        public final int matched;      // how many were updated directly from the glTF
        public final boolean nrm, tex, otex;

        Result(byte[] res, int vertices, int matched, boolean nrm, boolean tex, boolean otex) {
            this.res = res;
            this.vertices = vertices;
            this.matched = matched;
            this.nrm = nrm;
            this.tex = tex;
            this.otex = otex;
        }
    }

    /** A decoded glTF document (JSON tree + the binary chunk). */
    private static final class Glb {
        final Map<String, Object> root;
        final byte[] data;
        final int binStart;

        Glb(Map<String, Object> root, byte[] data, int binStart) {
            this.root = root;
            this.data = data;
            this.binStart = binStart;
        }
    }

    /** Re-imports {@code glb}'s geometry into {@code origRes}, returning new .res bytes. */
    public static Result apply(byte[] origRes, byte[] glb) {
        Glb g = parseGlb(glb);

        ResContainer res = ResContainer.parse(origRes);
        int vbufIndex = -1;
        for(int i = 0; i < res.layers.size(); i++)
            if(res.layers.get(i).name.equals("vbuf2")) {
                vbufIndex = i;
                break;
            }
        if(vbufIndex < 0)
            throw new IllegalArgumentException("the resource has no vbuf2 geometry to replace");

        Vbuf2Codec codec = Vbuf2Codec.parse(res.layers.get(vbufIndex).data);

        List<Map<String, Object>> prims = allPrimitiveAttributes(g.root);
        if(prims.isEmpty())
            throw new IllegalArgumentException("the glTF has no mesh primitives to import");

        boolean anyVid = false;
        for(Map<String, Object> a : prims)
            if(vidKey(a) != null) {
                anyVid = true;
                break;
            }

        Result r = anyVid
                ? applyById(g, prims, codec)
                : applyByOrder(g, prims, codec);

        res.layers.set(vbufIndex, new Layer("vbuf2", codec.encode()));
        return new Result(res.serialize(), r.vertices, r.matched, r.nrm, r.tex, r.otex);
    }

    /* -------------------------------------------------- id-based scatter (Blender) */

    private static Result applyById(Glb g, List<Map<String, Object>> prims, Vbuf2Codec codec) {
        int num = codec.num;
        float[] origPos = codec.decodeAttr("pos");
        float[] pos = origPos.clone();
        float[] nrm = codec.attr("nrm") != null ? codec.decodeAttr("nrm") : null;
        float[] tex = codec.attr("tex") != null ? codec.decodeAttr("tex") : null;
        float[] otex = codec.attr("otex") != null ? codec.decodeAttr("otex") : null;
        boolean[] hit = new boolean[num];
        boolean usedNrm = false, usedTex = false, usedOtex = false;
        int maxVid = -1;

        for(Map<String, Object> a : prims) {
            String vk = vidKey(a);
            if(vk == null || !a.containsKey("POSITION"))
                continue;
            float[] vid = readAccessor(g, idx(a.get(vk)), 1);
            float[] p = readAccessor(g, idx(a.get("POSITION")), 3);
            float[] n = (nrm != null && a.containsKey("NORMAL"))
                    ? readAccessor(g, idx(a.get("NORMAL")), 3) : null;
            float[] t = (tex != null && a.containsKey("TEXCOORD_0"))
                    ? readAccessor(g, idx(a.get("TEXCOORD_0")), 2) : null;
            float[] o = (otex != null && a.containsKey("TEXCOORD_1"))
                    ? readAccessor(g, idx(a.get("TEXCOORD_1")), 2) : null;

            for(int j = 0; j < vid.length; j++) {
                int v = Math.round(vid[j]);
                if(v > maxVid)
                    maxVid = v;
                if(v < 0 || v >= num)
                    continue;
                hit[v] = true;
                pos[v * 3] = p[j * 3];
                pos[v * 3 + 1] = -p[j * 3 + 2];
                pos[v * 3 + 2] = p[j * 3 + 1];
                if(n != null) {
                    nrm[v * 3] = n[j * 3];
                    nrm[v * 3 + 1] = -n[j * 3 + 2];
                    nrm[v * 3 + 2] = n[j * 3 + 1];
                    usedNrm = true;
                }
                if(t != null) {
                    tex[v * 2] = t[j * 2];
                    tex[v * 2 + 1] = t[j * 2 + 1];
                    usedTex = true;
                }
                if(o != null) {
                    otex[v * 2] = o[j * 2];
                    otex[v * 2 + 1] = o[j * 2 + 1];
                    usedOtex = true;
                }
            }
        }

        int matched = 0;
        for(boolean b : hit)
            if(b)
                matched++;
        if(matched == 0)
            throw new IllegalArgumentException(
                    "no vertices matched: the glTF's vertex ids don't belong to this resource "
                            + "(was the .glb exported from a different .res?).");
        if(maxVid >= num)
            throw new IllegalArgumentException(
                    "the glTF references vertex ids beyond this model (max id " + maxVid
                            + ", but it has " + num + " vertices) — was the .glb exported "
                            + "from a different .res?");

        // Vertices whose id Blender merged away (seam duplicates) share a position
        // with a vertex that *was* matched and move together, so copy that new
        // position; their normal/UV stay as the original.
        if(matched < num)
            fillFromCoincident(origPos, pos, hit, num);

        codec.setAttr("pos", pos);
        if(usedNrm)
            codec.setAttr("nrm", nrm);
        if(usedTex)
            codec.setAttr("tex", tex);
        if(usedOtex)
            codec.setAttr("otex", otex);
        return new Result(null, num, matched, usedNrm, usedTex, usedOtex);
    }

    /** Copies the new position of a matched vertex onto un-matched coincident ones. */
    private static void fillFromCoincident(float[] origPos, float[] pos, boolean[] hit, int num) {
        Map<Long, Integer> firstHit = new HashMap<>();
        for(int i = 0; i < num; i++)
            if(hit[i])
                firstHit.putIfAbsent(posKey(origPos, i), i);
        for(int k = 0; k < num; k++) {
            if(hit[k])
                continue;
            Integer i = firstHit.get(posKey(origPos, k));
            if(i != null) {
                pos[k * 3] = pos[i * 3];
                pos[k * 3 + 1] = pos[i * 3 + 1];
                pos[k * 3 + 2] = pos[i * 3 + 2];
            }
        }
    }

    private static long posKey(float[] p, int i) {
        long a = Float.floatToIntBits(p[i * 3]) & 0xffffffffL;
        long b = Float.floatToIntBits(p[i * 3 + 1]) & 0xffffffffL;
        long c = Float.floatToIntBits(p[i * 3 + 2]) & 0xffffffffL;
        return (a * 1000003L + b) * 1000003L + c;
    }

    /* --------------------------------------------- order-based (no ids: exact count) */

    private static Result applyByOrder(Glb g, List<Map<String, Object>> prims, Vbuf2Codec codec) {
        if(prims.size() != 1)
            throw new IllegalArgumentException(
                    "this glTF has no vertex ids and multiple primitives, so vertices can't be "
                            + "matched. Re-export it from ResForge and enable \"Data > Mesh > "
                            + "Attributes\" in Blender's glTF export so vertex ids are preserved.");
        Map<String, Object> a = prims.get(0);
        if(!a.containsKey("POSITION"))
            throw new IllegalArgumentException("the glTF has no mesh positions to import");
        float[] p = readAccessor(g, idx(a.get("POSITION")), 3);
        int glVerts = p.length / 3;
        if(glVerts != codec.num)
            throw new IllegalArgumentException(
                    "vertex count changed (glTF has " + glVerts + ", resource has " + codec.num
                            + ") and the glTF carries no vertex ids. Re-export the model from "
                            + "ResForge and enable \"Data > Mesh > Attributes\" in Blender's glTF "
                            + "export so edited models can be matched back regardless of count.");

        codec.setAttr("pos", axisInvert(p));
        boolean didNrm = false, didTex = false, didOtex = false;
        if(a.containsKey("NORMAL") && codec.attr("nrm") != null) {
            codec.setAttr("nrm", axisInvert(readAccessor(g, idx(a.get("NORMAL")), 3)));
            didNrm = true;
        }
        if(a.containsKey("TEXCOORD_0") && codec.attr("tex") != null) {
            codec.setAttr("tex", readAccessor(g, idx(a.get("TEXCOORD_0")), 2));
            didTex = true;
        }
        if(a.containsKey("TEXCOORD_1") && codec.attr("otex") != null) {
            codec.setAttr("otex", readAccessor(g, idx(a.get("TEXCOORD_1")), 2));
            didOtex = true;
        }
        return new Result(null, glVerts, glVerts, didNrm, didTex, didOtex);
    }

    /* ----------------------------------------------------------- glb / accessors */

    @SuppressWarnings("unchecked")
    private static Glb parseGlb(byte[] glb) {
        if(glb.length < 20 || le32(glb, 0) != 0x46546C67)
            throw new IllegalArgumentException("not a binary glTF (.glb) file");
        int jsonLen = le32(glb, 12);
        if(le32(glb, 16) != 0x4E4F534A)
            throw new IllegalArgumentException("malformed glTF: missing JSON chunk");
        Object parsed = Json.parse(new String(glb, 20, jsonLen, StandardCharsets.UTF_8));
        int binHeader = 20 + jsonLen;
        if(binHeader + 8 > glb.length || le32(glb, binHeader + 4) != 0x004E4942)
            throw new IllegalArgumentException("malformed glTF: missing BIN chunk");
        return new Glb((Map<String, Object>) parsed, glb, binHeader + 8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> allPrimitiveAttributes(Map<String, Object> root) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> meshes = (List<Object>) root.get("meshes");
        if(meshes == null)
            return out;
        for(Object mo : meshes) {
            List<Object> prims = (List<Object>) ((Map<String, Object>) mo).get("primitives");
            if(prims == null)
                continue;
            for(Object po : prims) {
                Map<String, Object> attrs = (Map<String, Object>) ((Map<String, Object>) po).get("attributes");
                if(attrs != null && !attrs.isEmpty())
                    out.add(attrs);
            }
        }
        return out;
    }

    /** The attribute key holding the stable vertex id, if any (Blender may keep or drop the underscore). */
    private static String vidKey(Map<String, Object> attrs) {
        for(String k : new String[]{"_VID", "VID", "_vid"})
            if(attrs.containsKey(k))
                return k;
        return null;
    }

    private static int idx(Object o) {
        return ((Number) o).intValue();
    }

    @SuppressWarnings("unchecked")
    private static float[] readAccessor(Glb g, int index, int comps) {
        List<Object> accessors = (List<Object>) g.root.get("accessors");
        Map<String, Object> acc = (Map<String, Object>) accessors.get(index);
        if(((Number) acc.get("componentType")).intValue() != 5126)
            throw new IllegalArgumentException("only FLOAT vertex attributes are supported on import");
        int count = ((Number) acc.get("count")).intValue();
        int bvIndex = ((Number) acc.get("bufferView")).intValue();
        Map<String, Object> bv = (Map<String, Object>) ((List<Object>) g.root.get("bufferViews")).get(bvIndex);
        int bvOff = bv.get("byteOffset") == null ? 0 : ((Number) bv.get("byteOffset")).intValue();
        int accOff = acc.get("byteOffset") == null ? 0 : ((Number) acc.get("byteOffset")).intValue();
        int stride = bv.get("byteStride") == null ? comps * 4 : ((Number) bv.get("byteStride")).intValue();
        int base = g.binStart + bvOff + accOff;
        float[] out = new float[count * comps];
        for(int i = 0; i < count; i++)
            for(int c = 0; c < comps; c++)
                out[i * comps + c] = Float.intBitsToFloat(le32(g.data, base + i * stride + c * 4));
        return out;
    }

    /** glTF Y-up -> Haven Z-up: (gx, gy, gz) -> (gx, -gz, gy). */
    private static float[] axisInvert(float[] v) {
        float[] out = new float[v.length];
        for(int i = 0; i + 2 < v.length; i += 3) {
            out[i] = v[i];
            out[i + 1] = -v[i + 2];
            out[i + 2] = v[i + 1];
        }
        return out;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }
}
