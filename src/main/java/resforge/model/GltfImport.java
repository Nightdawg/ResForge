package resforge.model;

import resforge.io.Json;
import resforge.layers.MeshAnimInfo;
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
        public final boolean nrm, tex, otex, bones, morphs;

        Result(byte[] res, int vertices, int matched, boolean nrm, boolean tex, boolean otex,
               boolean bones, boolean morphs) {
            this.res = res;
            this.vertices = vertices;
            this.matched = matched;
            this.nrm = nrm;
            this.tex = tex;
            this.otex = otex;
            this.bones = bones;
            this.morphs = morphs;
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
        byte[] origVbuf = res.layers.get(vbufIndex).data;

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
                ? applyById(g, prims, codec, origVbuf)
                : applyByOrder(g, prims, codec);

        res.layers.set(vbufIndex, new Layer("vbuf2", codec.encode()));
        boolean morphs = anyVid && applyMorphs(g, res, origVbuf);
        return new Result(res.serialize(), r.vertices, r.matched, r.nrm, r.tex, r.otex, r.bones, morphs);
    }

    /* -------------------------------------------------- id-based scatter (Blender) */

    private static Result applyById(Glb g, List<Map<String, Object>> prims, Vbuf2Codec codec, byte[] origVbuf) {
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
        int[] coincident = (matched < num) ? coincidentSource(origPos, hit, num) : null;
        if(coincident != null)
            for(int k = 0; k < num; k++)
                if(coincident[k] >= 0) {
                    int i = coincident[k];
                    pos[k * 3] = pos[i * 3];
                    pos[k * 3 + 1] = pos[i * 3 + 1];
                    pos[k * 3 + 2] = pos[i * 3 + 2];
                }

        codec.setAttr("pos", pos);
        if(usedNrm)
            codec.setAttr("nrm", nrm);
        if(usedTex)
            codec.setAttr("tex", tex);
        if(usedOtex)
            codec.setAttr("otex", otex);

        boolean didBones = applyWeights(g, prims, codec, origVbuf, origPos);
        return new Result(null, num, matched, usedNrm, usedTex, usedOtex, didBones, false);
    }

    /**
     * Re-imports skinning weights (Phase 2b): scatters the glTF's JOINTS_0/WEIGHTS_0
     * back to each vertex by {@code _VID}, mapping glTF joints to bone names via the
     * skin (Blender reorders joints, so names are the stable key). It only re-encodes
     * {@code bones2} when the weights actually changed from what the original would
     * export, so a pure mesh edit leaves skinning byte-identical.
     */
    private static boolean applyWeights(Glb g, List<Map<String, Object>> prims, Vbuf2Codec codec,
                                        byte[] origVbuf, float[] origPos) {
        if(codec.bones2Format() == null)
            return false;
        Vbuf2Data sd = Vbuf2Data.parse(origVbuf);
        if(sd == null || sd.boneNames == null || sd.vJoints == null)
            return false;
        String[] jointNames = skinJointNames(g.root);
        if(jointNames == null)
            return false;

        int num = codec.num;
        Map<String, Integer> nameToIdx = new HashMap<>();
        for(int i = 0; i < sd.boneNames.length; i++)
            nameToIdx.putIfAbsent(sd.boneNames[i], i);

        int[] newJoints = sd.vJoints.clone();
        float[] newWeights = sd.vWeights.clone();
        boolean[] hitW = new boolean[num];
        boolean sawWeights = false;

        for(Map<String, Object> a : prims) {
            String vk = vidKey(a);
            if(vk == null || !a.containsKey("JOINTS_0") || !a.containsKey("WEIGHTS_0"))
                continue;
            sawWeights = true;
            float[] vid = readAccessor(g, idx(a.get(vk)), 1);
            float[] gj = readAccessor(g, idx(a.get("JOINTS_0")), 4);
            float[] gw = readAccessor(g, idx(a.get("WEIGHTS_0")), 4);
            for(int j = 0; j < vid.length; j++) {
                int v = Math.round(vid[j]);
                if(v < 0 || v >= num)
                    continue;
                float sum = gw[j * 4] + gw[j * 4 + 1] + gw[j * 4 + 2] + gw[j * 4 + 3];
                if(sum <= 1e-6f)
                    continue;                            // unweighted in glTF: keep original
                hitW[v] = true;
                for(int k = 0; k < 4; k++) {
                    int ji = Math.round(gj[j * 4 + k]);
                    float wt = gw[j * 4 + k];
                    int bIdx = -1;
                    if(wt > 0 && ji >= 0 && ji < jointNames.length && jointNames[ji] != null) {
                        Integer bi = nameToIdx.get(jointNames[ji]);
                        if(bi != null)
                            bIdx = bi;
                    }
                    newJoints[v * 4 + k] = bIdx;
                    newWeights[v * 4 + k] = (bIdx >= 0) ? wt : 0;
                }
            }
        }
        if(!sawWeights)
            return false;

        int matchedW = 0;
        for(boolean b : hitW)
            if(b)
                matchedW++;
        if(matchedW < num) {
            int[] coincident = coincidentSource(origPos, hitW, num);
            for(int k = 0; k < num; k++)
                if(coincident[k] >= 0) {
                    int i = coincident[k];
                    System.arraycopy(newJoints, i * 4, newJoints, k * 4, 4);
                    System.arraycopy(newWeights, i * 4, newWeights, k * 4, 4);
                }
        }

        if(!weightsChanged(sd.vJoints, sd.vWeights, newJoints, newWeights, num))
            return false;                                // unchanged: keep original bones2 byte-for-byte
        codec.setBones2(sd.boneNames, newJoints, newWeights);
        return true;
    }

    /** True if any vertex's influence set (bone→weight, nonzero) differs beyond tolerance. */
    private static boolean weightsChanged(int[] j0, float[] w0, int[] j1, float[] w1, int num) {
        for(int v = 0; v < num; v++) {
            Map<Integer, Float> m0 = inflMap(j0, w0, v);
            Map<Integer, Float> m1 = inflMap(j1, w1, v);
            if(!m0.keySet().equals(m1.keySet()))
                return true;
            for(Map.Entry<Integer, Float> e : m0.entrySet())
                if(Math.abs(e.getValue() - m1.get(e.getKey())) > 0.01f)
                    return true;
        }
        return false;
    }

    private static Map<Integer, Float> inflMap(int[] joints, float[] weights, int v) {
        Map<Integer, Float> m = new HashMap<>();
        for(int k = 0; k < 4; k++) {
            int b = joints[v * 4 + k];
            float w = weights[v * 4 + k];
            if(b >= 0 && w > 0)
                m.merge(b, w, Float::sum);
        }
        return m;
    }

    /** glTF joint index → bone name, read from the mesh's skin (handles Blender's joint reorder). */
    @SuppressWarnings("unchecked")
    private static String[] skinJointNames(Map<String, Object> root) {
        List<Object> skins = (List<Object>) root.get("skins");
        List<Object> nodes = (List<Object>) root.get("nodes");
        if(skins == null || skins.isEmpty() || nodes == null)
            return null;
        Map<String, Object> skin = (Map<String, Object>) skins.get(0);
        for(Object no : nodes) {                         // prefer the skin actually used by a mesh node
            Map<String, Object> n = (Map<String, Object>) no;
            if(n.containsKey("mesh") && n.containsKey("skin")) {
                skin = (Map<String, Object>) skins.get(idx(n.get("skin")));
                break;
            }
        }
        List<Object> joints = (List<Object>) skin.get("joints");
        if(joints == null)
            return null;
        String[] names = new String[joints.size()];
        for(int i = 0; i < joints.size(); i++) {
            Map<String, Object> node = (Map<String, Object>) nodes.get(idx(joints.get(i)));
            Object nm = node.get("name");
            names[i] = (nm == null) ? null : nm.toString();
        }
        return names;
    }

    /** For each un-hit vertex, a hit vertex sharing its original position (or -1). */
    private static int[] coincidentSource(float[] origPos, boolean[] hit, int num) {
        Map<Long, Integer> firstHit = new HashMap<>();
        for(int i = 0; i < num; i++)
            if(hit[i])
                firstHit.putIfAbsent(posKey(origPos, i), i);
        int[] src = new int[num];
        for(int k = 0; k < num; k++) {
            src[k] = -1;
            if(!hit[k]) {
                Integer i = firstHit.get(posKey(origPos, k));
                if(i != null)
                    src[k] = i;
            }
        }
        return src;
    }

    private static long posKey(float[] p, int i) {
        long a = Float.floatToIntBits(p[i * 3]) & 0xffffffffL;
        long b = Float.floatToIntBits(p[i * 3 + 1]) & 0xffffffffL;
        long c = Float.floatToIntBits(p[i * 3 + 2]) & 0xffffffffL;
        return (a * 1000003L + b) * 1000003L + c;
    }

    /* ----------------------------------------------- morph (manim) shape re-import */

    /**
     * Re-imports edited morph (mesh-animation) <em>shapes</em>: each glTF morph
     * target is a frame's per-vertex deltas, scattered back by {@code _VID} and
     * axis-inverted, then re-encoded into the matching {@code manim} layer keeping
     * its original timing/order/format. The animation timeline itself (frame times,
     * counts) is kept from the original — only the shapes change — which sidesteps
     * the brittle round-trip of Blender's shape-key animation. Change-gated: a manim
     * whose shapes are unchanged is left byte-identical.
     */
    private static boolean applyMorphs(Glb g, ResContainer res, byte[] origVbuf) {
        List<MeshAnimInfo> manims = new ArrayList<>();
        List<Integer> manimLayer = new ArrayList<>();
        for(int i = 0; i < res.layers.size(); i++)
            if(res.layers.get(i).name.equals("manim")) {
                MeshAnimInfo mi = MeshAnimInfo.parse(res.layers.get(i).data);
                if(mi.recognized) {
                    manims.add(mi);
                    manimLayer.add(i);
                }
            }
        if(manims.isEmpty())
            return false;

        Vbuf2Codec codec = Vbuf2Codec.parse(origVbuf);
        int num = codec.num;
        float[] origPos = codec.decodeAttr("pos");

        int total = 0;
        for(MeshAnimInfo mi : manims)
            total += mi.frames.size();
        float[][] origDense = new float[total][];
        {
            int gi = 0;
            for(MeshAnimInfo mi : manims)
                for(MeshAnimInfo.Frame fr : mi.frames) {
                    float[] dd = new float[num * 3];
                    if(fr.idx != null && fr.pos != null)
                        for(int q = 0; q < fr.idx.length; q++) {
                            int v = fr.idx[q];
                            if(v >= 0 && v < num) {
                                dd[v * 3] = fr.pos[q * 3];
                                dd[v * 3 + 1] = fr.pos[q * 3 + 1];
                                dd[v * 3 + 2] = fr.pos[q * 3 + 2];
                            }
                        }
                    origDense[gi++] = dd;
                }
        }

        List<Map<String, Object>> fullPrims = allPrimitives(g.root);
        float[][] newDeltas = new float[total][];
        for(int t = 0; t < total; t++)
            newDeltas[t] = origDense[t].clone();
        boolean[] hit = new boolean[num];
        boolean sawTargets = false;

        for(Map<String, Object> p : fullPrims) {
            Object tg = p.get("targets");
            Map<String, Object> at = attributesOf(p);
            if(tg == null || at == null)
                continue;
            String vk = vidKey(at);
            if(vk == null)
                continue;
            List<?> targets = (List<?>) tg;
            if(targets.size() != total)
                return false;                            // shape-key count changed: keep original
            sawTargets = true;
            float[] vid = readAccessor(g, idx(at.get(vk)), 1);
            for(int t = 0; t < total; t++) {
                Object posAcc = ((Map<?, ?>) targets.get(t)).get("POSITION");
                if(posAcc == null)
                    continue;
                float[] tp = readAccessor(g, idx(posAcc), 3);
                for(int j = 0; j < vid.length; j++) {
                    int v = Math.round(vid[j]);
                    if(v < 0 || v >= num)
                        continue;
                    hit[v] = true;
                    newDeltas[t][v * 3] = tp[j * 3];
                    newDeltas[t][v * 3 + 1] = -tp[j * 3 + 2];
                    newDeltas[t][v * 3 + 2] = tp[j * 3 + 1];
                }
            }
        }
        if(!sawTargets)
            return false;

        int hits = 0;
        for(boolean b : hit)
            if(b)
                hits++;
        if(hits < num) {
            int[] src = coincidentSource(origPos, hit, num);
            for(int t = 0; t < total; t++)
                for(int k = 0; k < num; k++)
                    if(src[k] >= 0)
                        System.arraycopy(newDeltas[t], src[k] * 3, newDeltas[t], k * 3, 3);
        }

        boolean any = false;
        int gi = 0;
        for(int m = 0; m < manims.size(); m++) {
            MeshAnimInfo mi = manims.get(m);
            int cnt = mi.frames.size();
            boolean changed = false, allF3 = true;
            for(int f = 0; f < cnt; f++) {
                if(mi.frames.get(f).fmt != 3)
                    allF3 = false;
                if(maxDiff(newDeltas[gi + f], origDense[gi + f]) > 1e-3f)
                    changed = true;
            }
            if(changed && allF3) {
                float[][] fd = new float[cnt][];
                for(int f = 0; f < cnt; f++)
                    fd[f] = newDeltas[gi + f];
                res.layers.set(manimLayer.get(m), new Layer("manim", mi.encodeWith(fd, num, 1e-6f)));
                any = true;
            }
            gi += cnt;
        }
        return any;
    }

    private static float maxDiff(float[] a, float[] b) {
        float m = 0;
        for(int i = 0; i < a.length; i++)
            m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    /* --------------------------------------------- order-based (no ids: exact count) */

    private static final String ATTR_HINT =
            "In Blender's glTF export, expand \"Data > Mesh\" and tick \"Attributes\" "
                    + "(it is OFF by default) so the per-vertex ids ResForge embeds survive the "
                    + "round-trip, then export and re-import again.";

    private static Result applyByOrder(Glb g, List<Map<String, Object>> prims, Vbuf2Codec codec) {
        if(prims.size() != 1)
            throw new IllegalArgumentException(
                    "this glTF has no vertex ids and multiple primitives, so its vertices can't be "
                            + "matched to the model. " + ATTR_HINT);
        Map<String, Object> a = prims.get(0);
        if(!a.containsKey("POSITION"))
            throw new IllegalArgumentException("the glTF has no mesh positions to import");
        float[] p = readAccessor(g, idx(a.get("POSITION")), 3);
        int glVerts = p.length / 3;
        if(glVerts != codec.num)
            throw new IllegalArgumentException(
                    "this glTF has no vertex ids and its " + glVerts + " vertices don't match the "
                            + "model's " + codec.num + " (tools like Blender re-split vertices, so "
                            + "the count changes). " + ATTR_HINT);

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
        return new Result(null, glVerts, glVerts, didNrm, didTex, didOtex, false, false);
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> allPrimitives(Map<String, Object> root) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> meshes = (List<Object>) root.get("meshes");
        if(meshes == null)
            return out;
        for(Object mo : meshes) {
            List<Object> prims = (List<Object>) ((Map<String, Object>) mo).get("primitives");
            if(prims == null)
                continue;
            for(Object po : prims)
                out.add((Map<String, Object>) po);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributesOf(Map<String, Object> prim) {
        return (Map<String, Object>) prim.get("attributes");
    }

    private static int idx(Object o) {
        return ((Number) o).intValue();
    }

    @SuppressWarnings("unchecked")
    private static float[] readAccessor(Glb g, int index, int comps) {
        List<Object> accessors = (List<Object>) g.root.get("accessors");
        Map<String, Object> acc = (Map<String, Object>) accessors.get(index);
        int ct = ((Number) acc.get("componentType")).intValue();
        boolean normalized = Boolean.TRUE.equals(acc.get("normalized"));
        int count = ((Number) acc.get("count")).intValue();
        List<Object> bufferViews = (List<Object>) g.root.get("bufferViews");
        float[] out = new float[count * comps];

        // Dense base values (absent for an all-zero accessor or a sparse morph target).
        Object bvObj = acc.get("bufferView");
        if(bvObj != null) {
            Map<String, Object> bv = (Map<String, Object>) bufferViews.get(((Number) bvObj).intValue());
            int bvOff = num(bv.get("byteOffset"));
            int accOff = num(acc.get("byteOffset"));
            int compSize = compSize(ct);
            int stride = bv.get("byteStride") == null ? comps * compSize : ((Number) bv.get("byteStride")).intValue();
            int base = g.binStart + bvOff + accOff;
            for(int i = 0; i < count; i++)
                for(int c = 0; c < comps; c++)
                    out[i * comps + c] = decodeComp(g.data, base + i * stride + c * compSize, ct, normalized);
        }

        // Sparse overrides (Blender exports shape-key morph targets this way).
        Map<String, Object> sparse = (Map<String, Object>) acc.get("sparse");
        if(sparse != null) {
            int sc = ((Number) sparse.get("count")).intValue();
            Map<String, Object> sIdx = (Map<String, Object>) sparse.get("indices");
            Map<String, Object> sVal = (Map<String, Object>) sparse.get("values");
            int idxCt = ((Number) sIdx.get("componentType")).intValue();
            int idxSize = compSize(idxCt);
            Map<String, Object> idxBv = (Map<String, Object>) bufferViews.get(((Number) sIdx.get("bufferView")).intValue());
            int idxBase = g.binStart + num(idxBv.get("byteOffset")) + num(sIdx.get("byteOffset"));
            int valSize = compSize(ct);
            Map<String, Object> valBv = (Map<String, Object>) bufferViews.get(((Number) sVal.get("bufferView")).intValue());
            int valBase = g.binStart + num(valBv.get("byteOffset")) + num(sVal.get("byteOffset"));
            for(int s = 0; s < sc; s++) {
                int ei = (int) leUint(g.data, idxBase + s * idxSize, idxSize);
                for(int c = 0; c < comps; c++)
                    out[ei * comps + c] = decodeComp(g.data, valBase + (s * comps + c) * valSize, ct, normalized);
            }
        }
        return out;
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static int compSize(int componentType) {
        switch(componentType) {
            case 5126: case 5125: return 4;     // FLOAT, UNSIGNED_INT
            case 5123: case 5122: return 2;     // (UNSIGNED_)SHORT
            case 5121: case 5120: return 1;     // (UNSIGNED_)BYTE
            default: throw new IllegalArgumentException("unsupported accessor componentType: " + componentType);
        }
    }

    /** Decodes one component at {@code off}, honouring componentType and the normalized flag. */
    private static float decodeComp(byte[] data, int off, int ct, boolean normalized) {
        switch(ct) {
            case 5126: return Float.intBitsToFloat(le32(data, off));                          // FLOAT
            case 5125: return leUint(data, off, 4);                                           // UNSIGNED_INT
            case 5123: return normalized ? leUint(data, off, 2) / 65535f : leUint(data, off, 2);
            case 5121: return normalized ? leUint(data, off, 1) / 255f : leUint(data, off, 1);
            case 5122: return normalized ? Math.max(-1f, signExtend(leUint(data, off, 2), 2) / 32767f)
                    : signExtend(leUint(data, off, 2), 2);
            case 5120: return normalized ? Math.max(-1f, signExtend(leUint(data, off, 1), 1) / 127f)
                    : signExtend(leUint(data, off, 1), 1);
            default: throw new IllegalArgumentException("unsupported accessor componentType: " + ct);
        }
    }

    private static long leUint(byte[] b, int off, int size) {
        long v = 0;
        for(int i = 0; i < size; i++)
            v |= (long) (b[off + i] & 0xff) << (8 * i);
        return v;
    }

    private static long signExtend(long v, int size) {
        int bits = size * 8;
        long sign = 1L << (bits - 1);
        return (v ^ sign) - sign;
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
