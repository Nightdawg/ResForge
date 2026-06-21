package resforge.model;

import resforge.io.Json;
import resforge.io.MessageWriter;
import resforge.layers.MeshAnimInfo;
import resforge.layers.MeshInfo;
import resforge.layers.SkelInfo;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        public final boolean nrm, tex, otex, bones, morphs, skel;

        Result(byte[] res, int vertices, int matched, boolean nrm, boolean tex, boolean otex,
               boolean bones, boolean morphs, boolean skel) {
            this.res = res;
            this.vertices = vertices;
            this.matched = matched;
            this.nrm = nrm;
            this.tex = tex;
            this.otex = otex;
            this.bones = bones;
            this.morphs = morphs;
            this.skel = skel;
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
        boolean skel = applySkel(g, res);
        return new Result(res.serialize(), r.vertices, r.matched, r.nrm, r.tex, r.otex, r.bones, morphs, skel);
    }

    public static final class RebuildResult {
        public final byte[] res;
        public final int vertices, triangles;
        public final boolean skinned;

        RebuildResult(byte[] res, int vertices, int triangles, boolean skinned) {
            this.res = res;
            this.vertices = vertices;
            this.triangles = triangles;
            this.skinned = skinned;
        }
    }

    /**
     * Rebuilds a model's geometry from the glTF instead of patching it — this is the
     * path that allows <em>adding or removing</em> vertices and triangles (Blender
     * reshaping, subdividing, deleting faces…). It regenerates the {@code vbuf2}
     * (positions/normals/UVs re-quantised into the original formats), the {@code mesh}
     * triangle list, and — for skinned models — the {@code bones2} weights, all at the
     * glTF's vertex count, while keeping every other layer (textures, materials,
     * skeleton, code…). Unlike {@link #apply}, it does not need {@code _VID} and gives
     * up byte-exactness, so it relies on in-game validation.
     *
     * <p>This version targets models with a single shared {@code vbuf2} and one or more
     * {@code mesh} submeshes, whose vertex attributes are positions/normals/UVs/
     * bone-weights. Each glTF primitive becomes a submesh, its part id recovered from
     * the material name ({@code rfmat_<matid>}); primitives are concatenated into the
     * shared vertex buffer.
     */
    public static RebuildResult rebuild(byte[] origRes, byte[] glb) {
        Glb g = parseGlb(glb);
        ResContainer res = ResContainer.parse(origRes);

        int vbufIdx = -1, vbufN = 0, manimN = 0;
        List<Integer> meshIdxs = new ArrayList<>();
        for(int i = 0; i < res.layers.size(); i++) {
            String nm = res.layers.get(i).name;
            if(nm.equals("vbuf2")) { vbufN++; vbufIdx = i; }
            else if(nm.equals("mesh")) meshIdxs.add(i);
            else if(nm.equals("manim")) manimN++;
        }
        if(vbufN != 1 || meshIdxs.isEmpty())
            throw new IllegalArgumentException(
                    "rebuild needs one shared vbuf2 and at least one mesh; this resource has "
                            + vbufN + " vbuf2 and " + meshIdxs.size() + " mesh layers.");
        // morph layers re-encode at the new vertex count (shapes from the glTF targets,
        // timing kept from the original); the frame count must be unchanged.
        List<MeshAnimInfo> manims = new ArrayList<>();
        List<Integer> manimIdxs = new ArrayList<>();
        int totalFrames = 0;
        for(int i = 0; i < res.layers.size(); i++)
            if(res.layers.get(i).name.equals("manim")) {
                MeshAnimInfo mai = MeshAnimInfo.parse(res.layers.get(i).data);
                if(!mai.recognized)
                    throw new IllegalArgumentException("couldn't decode a manim layer to rebuild it.");
                manims.add(mai);
                manimIdxs.add(i);
                totalFrames += mai.frames.size();
            }
        boolean hasManim = !manims.isEmpty();

        byte[] origVbuf = res.layers.get(vbufIdx).data;
        Vbuf2Codec codec = Vbuf2Codec.parse(origVbuf);
        for(Vbuf2Codec.Attr at : codec.attrs) {
            String base = at.name.endsWith("2") ? at.name.substring(0, at.name.length() - 1) : at.name;
            if(!base.equals("pos") && !base.equals("nrm") && !base.equals("tex") && !base.equals("otex")
                    && !base.equals("tan") && !base.equals("bit")
                    && !at.name.equals("bones2") && !at.name.equals("bones"))
                throw new IllegalArgumentException("rebuild doesn't support the '" + at.name + "' vertex attribute yet.");
        }
        boolean hasNrm = codec.attr("nrm") != null, hasTex = codec.attr("tex") != null;
        boolean hasOtex = codec.attr("otex") != null, hasBones = codec.attr("bones") != null;
        boolean hasTan = codec.attr("tan") != null || codec.attr("bit") != null;

        // matid -> a template original mesh layer (for its id/vbufid).
        Map<Integer, MeshInfo> matidToMesh = new LinkedHashMap<>();
        int vbufId = 0;
        for(int mi : meshIdxs) {
            MeshInfo m = MeshInfo.parse(res.layers.get(mi).data);
            if(m.recognized) {
                matidToMesh.putIfAbsent(m.matid, m);
                vbufId = m.vbufid;
            }
        }
        List<Object> materials = materialsOf(g.root);

        // Concatenate each distinct vertex block (Blender splits per material; our own
        // export shares one), de-duplicating by POSITION accessor so a shared buffer
        // isn't copied per primitive.
        List<float[]> cPos = new ArrayList<>(), cNrm = new ArrayList<>(), cTex = new ArrayList<>(),
                cOtex = new ArrayList<>(), cJoints = new ArrayList<>(), cWeights = new ArrayList<>();
        Map<Integer, Integer> posOffset = new HashMap<>();
        int total = 0;
        List<int[]> meshTris = new ArrayList<>();
        List<Integer> meshMatids = new ArrayList<>();
        List<List<float[]>> tChunks = new ArrayList<>();     // morph target deltas, per target -> chunks
        int[] targetN = {-1};

        for(Map<String, Object> prim : allPrimitives(g.root)) {
            Map<String, Object> a = attributesOf(prim);
            if(a == null || !a.containsKey("POSITION"))
                continue;
            int posAcc = idx(a.get("POSITION"));
            int offset;
            if(posOffset.containsKey(posAcc)) {
                offset = posOffset.get(posAcc);
            } else {
                float[] p = readAccessor(g, posAcc, 3);
                int cnt = p.length / 3;
                cPos.add(axisInvert(p));
                if(hasNrm) cNrm.add(axisInvert(readVec(g, a, "NORMAL", 3, "normals")));
                if(hasTex) cTex.add(readVec(g, a, "TEXCOORD_0", 2, "UVs"));
                if(hasOtex) cOtex.add(readVec(g, a, "TEXCOORD_1", 2, "a second UV set"));
                if(hasBones) {
                    cJoints.add(readVec(g, a, "JOINTS_0", 4, "skinning joints"));
                    cWeights.add(readVec(g, a, "WEIGHTS_0", 4, "skinning weights"));
                }
                if(hasManim)
                    readMorphChunks(g, prim, cnt, tChunks, targetN);
                offset = total;
                posOffset.put(posAcc, offset);
                total += cnt;
            }
            int[] tris = readIndices(g, prim, total);
            for(int i = 0; i < tris.length; i++)
                tris[i] += offset;
            meshTris.add(tris);
            meshMatids.add(recoverMatid(prim, materials, matidToMesh));
        }
        if(total == 0)
            throw new IllegalArgumentException("the glTF has no mesh positions to rebuild from.");
        if(total > 0xffff)
            throw new IllegalArgumentException("rebuilt mesh has " + total + " vertices, over the 65535 limit.");

        codec.num = total;
        float[] allPos = concat(cPos);
        float[] allNrm = hasNrm ? concat(cNrm) : null;
        float[] allTex = hasTex ? concat(cTex) : null;
        codec.setAttr("pos", allPos);
        if(hasNrm) codec.setAttr("nrm", allNrm);
        if(hasTex) codec.setAttr("tex", allTex);
        if(hasOtex) codec.setAttr("otex", concat(cOtex));
        if(hasBones)
            rebuildWeights(g, concat(cJoints), concat(cWeights), codec, origVbuf, total);
        if(hasTan) {
            // Haven stores tan and bit identically (verified across all sampled
            // normal-mapped models), so recompute one tangent and write it to both.
            if(allNrm == null || allTex == null)
                throw new IllegalArgumentException("rebuilding tangents needs normals and UVs, which the glTF lacks.");
            float[] tangents = computeTangents(allPos, allNrm, allTex, total, meshTris);
            if(codec.attr("tan") != null) codec.setAttr("tan", tangents);
            if(codec.attr("bit") != null) codec.setAttr("bit", tangents);
        }
        byte[] newVbuf = codec.encode();

        // Re-encode each manim at the new vertex count (shapes from the glTF targets).
        Map<Integer, byte[]> manimReplace = new HashMap<>();
        if(hasManim) {
            if(targetN[0] != totalFrames)
                throw new IllegalArgumentException(
                        "the glTF has " + targetN[0] + " shape keys but the model's morph animation has "
                                + totalFrames + " frames; rebuild can't add or remove morph frames yet.");
            float[][] combined = new float[totalFrames][];
            for(int t = 0; t < totalFrames; t++)
                combined[t] = concat(tChunks.get(t));
            int gi = 0;
            for(int m = 0; m < manims.size(); m++) {
                MeshAnimInfo mai = manims.get(m);
                int cntF = mai.frames.size();
                float[][] fd = new float[cntF][];
                for(int f = 0; f < cntF; f++)
                    fd[f] = combined[gi + f];
                manimReplace.put(manimIdxs.get(m), mai.encodeWith(fd, total, 1e-6f));
                gi += cntF;
            }
        }

        // Rebuild the layer list: vbuf2 in place, all old mesh layers replaced by the
        // new submeshes at the first mesh position, manim layers re-encoded, others kept.
        List<Layer> outLayers = new ArrayList<>();
        java.util.Set<Integer> meshSet = new java.util.HashSet<>(meshIdxs);
        boolean meshEmitted = false;
        int totalTris = 0;
        for(int i = 0; i < res.layers.size(); i++) {
            if(i == vbufIdx) {
                outLayers.add(new Layer("vbuf2", newVbuf));
            } else if(meshSet.contains(i)) {
                if(!meshEmitted) {
                    for(int s = 0; s < meshTris.size(); s++) {
                        int matid = meshMatids.get(s);
                        MeshInfo tmpl = matidToMesh.get(matid);
                        outLayers.add(new Layer("mesh", encodeMeshRaw(matid,
                                tmpl != null ? tmpl.id : -1, vbufId, meshTris.get(s))));
                        totalTris += meshTris.get(s).length / 3;
                    }
                    meshEmitted = true;
                }
            } else if(manimReplace.containsKey(i)) {
                outLayers.add(new Layer("manim", manimReplace.get(i)));
            } else {
                outLayers.add(res.layers.get(i));
            }
        }
        res.layers.clear();
        res.layers.addAll(outLayers);
        return new RebuildResult(res.serialize(), total, totalTris, hasBones);
    }

    /** Reads a required vertex attribute, with a clear error if the glTF is missing it. */
    private static float[] readVec(Glb g, Map<String, Object> a, String key, int comps, String what) {
        if(!a.containsKey(key))
            throw new IllegalArgumentException("the glTF is missing " + what + ", which this model needs.");
        return readAccessor(g, idx(a.get(key)), comps);
    }

    private static float[] concat(List<float[]> chunks) {
        int n = 0;
        for(float[] c : chunks) n += c.length;
        float[] out = new float[n];
        int o = 0;
        for(float[] c : chunks) { System.arraycopy(c, 0, out, o, c.length); o += c.length; }
        return out;
    }

    /** The matid for a primitive, recovered from its material name "rfmat_<matid>". */
    @SuppressWarnings("unchecked")
    private static int recoverMatid(Map<String, Object> prim, List<Object> materials,
                                    Map<Integer, MeshInfo> matidToMesh) {
        Object mo = prim.get("material");
        if(mo != null && materials != null) {
            int mIdx = ((Number) mo).intValue();
            if(mIdx >= 0 && mIdx < materials.size()) {
                Object nm = ((Map<String, Object>) materials.get(mIdx)).get("name");
                Integer matid = parseMatid(nm == null ? null : nm.toString());
                if(matid != null)
                    return matid;
            }
        }
        // fall back to the only original matid if there's just one
        return matidToMesh.size() == 1 ? matidToMesh.keySet().iterator().next() : -1;
    }

    /** Parses the matid out of a "rfmat_<int>" material name (tolerating Blender's ".001" suffixes). */
    private static Integer parseMatid(String name) {
        if(name == null)
            return null;
        int p = name.indexOf("rfmat_");
        if(p < 0)
            return null;
        int i = p + 6;
        int start = i;
        if(i < name.length() && name.charAt(i) == '-') i++;
        while(i < name.length() && Character.isDigit(name.charAt(i))) i++;
        if(i == start || (i == start + 1 && name.charAt(start) == '-'))
            return null;
        try {
            return Integer.parseInt(name.substring(start, i));
        } catch(NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> materialsOf(Map<String, Object> root) {
        return (List<Object>) root.get("materials");
    }

    /**
     * Recomputes per-vertex tangents from positions, normals, UVs and triangles
     * (Lengyel's method: accumulate the UV-gradient tangent over each triangle, then
     * Gram-Schmidt orthogonalise against the normal and normalise). Used for normal-
     * mapped models; Haven stores {@code bit} identical to {@code tan}, so the caller
     * writes this to both.
     */
    private static float[] computeTangents(float[] pos, float[] nrm, float[] tex, int num, List<int[]> meshTris) {
        float[] acc = new float[num * 3];
        for(int[] tris : meshTris)
            for(int i = 0; i + 2 < tris.length; i += 3)
                accumTangent(pos, tex, tris[i], tris[i + 1], tris[i + 2], acc);
        float[] out = new float[num * 3];
        for(int v = 0; v < num; v++) {
            double nx = nrm[v * 3], ny = nrm[v * 3 + 1], nz = nrm[v * 3 + 2];
            double tx = acc[v * 3], ty = acc[v * 3 + 1], tz = acc[v * 3 + 2];
            double d = tx * nx + ty * ny + tz * nz;          // Gram-Schmidt vs normal
            tx -= nx * d; ty -= ny * d; tz -= nz * d;
            double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if(len < 1e-8) {                                  // degenerate: any perpendicular to the normal
                double[] p = perp(nx, ny, nz);
                tx = p[0]; ty = p[1]; tz = p[2];
                len = 1;
            }
            out[v * 3] = (float) (tx / len);
            out[v * 3 + 1] = (float) (ty / len);
            out[v * 3 + 2] = (float) (tz / len);
        }
        return out;
    }

    private static void accumTangent(float[] pos, float[] tex, int i0, int i1, int i2, float[] acc) {
        float e1x = pos[i1 * 3] - pos[i0 * 3], e1y = pos[i1 * 3 + 1] - pos[i0 * 3 + 1], e1z = pos[i1 * 3 + 2] - pos[i0 * 3 + 2];
        float e2x = pos[i2 * 3] - pos[i0 * 3], e2y = pos[i2 * 3 + 1] - pos[i0 * 3 + 1], e2z = pos[i2 * 3 + 2] - pos[i0 * 3 + 2];
        float du1 = tex[i1 * 2] - tex[i0 * 2], dv1 = tex[i1 * 2 + 1] - tex[i0 * 2 + 1];
        float du2 = tex[i2 * 2] - tex[i0 * 2], dv2 = tex[i2 * 2 + 1] - tex[i0 * 2 + 1];
        float den = du1 * dv2 - du2 * dv1;
        if(Math.abs(den) < 1e-12f)
            return;
        float r = 1f / den;
        float tx = r * (e1x * dv2 - e2x * dv1), ty = r * (e1y * dv2 - e2y * dv1), tz = r * (e1z * dv2 - e2z * dv1);
        for(int j : new int[]{i0, i1, i2}) {
            acc[j * 3] += tx; acc[j * 3 + 1] += ty; acc[j * 3 + 2] += tz;
        }
    }

    /** Some unit vector perpendicular to (x,y,z). */
    private static double[] perp(double x, double y, double z) {
        double[] r = (Math.abs(x) <= Math.abs(y) && Math.abs(x) <= Math.abs(z))
                ? new double[]{0, -z, y} : (Math.abs(y) <= Math.abs(z))
                ? new double[]{-z, 0, x} : new double[]{-y, x, 0};
        double l = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
        if(l < 1e-12) return new double[]{1, 0, 0};
        return new double[]{r[0] / l, r[1] / l, r[2] / l};
    }

    /** Reads this primitive's morph-target POSITION deltas (axis-inverted) into the per-target chunk lists. */
    private static void readMorphChunks(Glb g, Map<String, Object> prim, int cnt,
                                        List<List<float[]>> tChunks, int[] targetN) {
        Object tg = prim.get("targets");
        if(tg == null)
            throw new IllegalArgumentException(
                    "this model has morph animation but the glTF has no shape keys to rebuild it from.");
        List<?> targets = (List<?>) tg;
        if(targetN[0] < 0) {
            targetN[0] = targets.size();
            for(int t = 0; t < targetN[0]; t++)
                tChunks.add(new ArrayList<>());
        }
        if(targets.size() != targetN[0])
            throw new IllegalArgumentException("the glTF's parts have inconsistent shape-key counts.");
        for(int t = 0; t < targetN[0]; t++) {
            Object pa = ((Map<?, ?>) targets.get(t)).get("POSITION");
            float[] td = (pa == null) ? new float[cnt * 3]
                    : axisInvert(readAccessor(g, ((Number) pa).intValue(), 3));
            if(td.length != cnt * 3)
                td = java.util.Arrays.copyOf(td, cnt * 3);
            tChunks.get(t).add(td);
        }
    }

    /** Builds bones2 for a rebuilt vbuf from concatenated glTF JOINTS_0/WEIGHTS_0 (joints mapped by name). */
    private static void rebuildWeights(Glb g, float[] gj, float[] gw, Vbuf2Codec codec, byte[] origVbuf, int num) {
        Vbuf2Data sd = Vbuf2Data.parse(origVbuf);
        String[] jointNames = skinJointNames(g.root);
        if(sd == null || sd.boneNames == null || jointNames == null)
            throw new IllegalArgumentException("couldn't recover the skeleton's bone names for rebuild.");
        Map<String, Integer> nameToIdx = new HashMap<>();
        for(int i = 0; i < sd.boneNames.length; i++)
            nameToIdx.putIfAbsent(sd.boneNames[i], i);
        int[] vJoints = new int[num * 4];
        float[] vWeights = new float[num * 4];
        java.util.Arrays.fill(vJoints, -1);
        for(int v = 0; v < num; v++)
            for(int k = 0; k < 4; k++) {
                int ji = Math.round(gj[v * 4 + k]);
                float wt = gw[v * 4 + k];
                if(wt > 0 && ji >= 0 && ji < jointNames.length && jointNames[ji] != null) {
                    Integer bi = nameToIdx.get(jointNames[ji]);
                    if(bi != null) {
                        vJoints[v * 4 + k] = bi;
                        vWeights[v * 4 + k] = wt;
                    }
                }
            }
        codec.setBones2(sd.boneNames, vJoints, vWeights);
    }

    /** Reads the primitive's triangle indices (or generates a trivial list if non-indexed). */
    private static int[] readIndices(Glb g, Map<String, Object> prim, int newNum) {
        Object idxAcc = prim.get("indices");
        if(idxAcc == null) {
            int[] t = new int[newNum - (newNum % 3)];
            for(int i = 0; i < t.length; i++)
                t[i] = i;
            return t;
        }
        float[] raw = readAccessor(g, ((Number) idxAcc).intValue(), 1);
        int[] tris = new int[raw.length];
        for(int i = 0; i < raw.length; i++)
            tris[i] = Math.round(raw[i]);
        return tris;
    }

    private static byte[] encodeMeshRaw(int matid, int id, int vbufid, int[] tris) {
        MessageWriter w = new MessageWriter();
        boolean hasId = id != -1;
        int fl = 16 | (hasId ? 2 : 0);
        w.uint8(fl).uint16(tris.length / 3).int16(matid);
        if(hasId)
            w.int16(id);
        w.int16(vbufid);
        for(int t : tris)
            w.uint16(t);
        return w.toByteArray();
    }


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
        return new Result(null, num, matched, usedNrm, usedTex, usedOtex, didBones, false, false);
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

    /* ----------------------------------------------------- skeleton (skel) re-import */

    /**
     * Re-imports an edited skeleton rest pose (Phase 2c). Each skel bone's new local
     * transform is read from its glTF joint node by name (Blender preserves bone
     * names and node-local translation/rotation), compared to the original, and — if
     * any bone moved beyond a small tolerance — the whole skeleton is re-encoded as a
     * version-1 {@code skel} via {@link SkelInfo#encodeVer1}. Unchanged skeletons are
     * left byte-identical (a plain Blender round-trip drifts only ~0.04°).
     */
    @SuppressWarnings("unchecked")
    private static boolean applySkel(Glb g, ResContainer res) {
        int skelIdx = -1;
        for(int i = 0; i < res.layers.size(); i++)
            if(res.layers.get(i).name.equals("skel")) {
                skelIdx = i;
                break;
            }
        if(skelIdx < 0)
            return false;
        SkelInfo si = SkelInfo.parse(res.layers.get(skelIdx).data);
        if(!si.recognized || si.bones.isEmpty())
            return false;
        Map<String, double[]> nodeXf = skelNodeTransforms(g.root);
        if(nodeXf.isEmpty())
            return false;

        boolean changed = false;
        List<SkelInfo.Bone> out = new ArrayList<>();
        for(SkelInfo.Bone b : si.bones) {
            double[] xf = nodeXf.get(b.name);
            if(xf == null) {                             // bone not in the glTF: keep as-is
                out.add(b);
                continue;
            }
            double dPos = Math.sqrt(sq(xf[0] - b.px) + sq(xf[1] - b.py) + sq(xf[2] - b.pz));
            double[] oq = axisAngleToQuat(b.ax, b.ay, b.az, b.ang);
            double dot = Math.abs(oq[0] * xf[3] + oq[1] * xf[4] + oq[2] * xf[5] + oq[3] * xf[6]);
            double dDeg = Math.toDegrees(2 * Math.acos(Math.min(1, dot)));
            if(dPos > 1e-3 || dDeg > 0.5)
                changed = true;
            double[] aa = quatToAxisAngle(xf[3], xf[4], xf[5], xf[6]);
            out.add(new SkelInfo.Bone(b.name, b.parent, (float) xf[0], (float) xf[1], (float) xf[2],
                    (float) aa[0], (float) aa[1], (float) aa[2], (float) aa[3]));
        }
        if(!changed)
            return false;
        res.layers.set(skelIdx, new Layer("skel", SkelInfo.encodeVer1(out)));
        return true;
    }

    /** Skeleton joint node name -> {tx,ty,tz, qx,qy,qz,qw} local transform. */
    @SuppressWarnings("unchecked")
    private static Map<String, double[]> skelNodeTransforms(Map<String, Object> root) {
        Map<String, double[]> out = new HashMap<>();
        List<Object> nodes = (List<Object>) root.get("nodes");
        List<Object> skins = (List<Object>) root.get("skins");
        if(nodes == null || skins == null)
            return out;
        for(Object so : skins) {
            List<Object> joints = (List<Object>) ((Map<String, Object>) so).get("joints");
            if(joints == null)
                continue;
            for(Object jo : joints) {
                Map<String, Object> n = (Map<String, Object>) nodes.get(((Number) jo).intValue());
                Object nm = n.get("name");
                if(nm == null)
                    continue;
                double[] xf = {0, 0, 0, 0, 0, 0, 1};
                List<Object> tl = (List<Object>) n.get("translation");
                if(tl != null)
                    for(int k = 0; k < 3; k++) xf[k] = ((Number) tl.get(k)).doubleValue();
                List<Object> rl = (List<Object>) n.get("rotation");
                if(rl != null)
                    for(int k = 0; k < 4; k++) xf[3 + k] = ((Number) rl.get(k)).doubleValue();
                out.putIfAbsent(nm.toString(), xf);
            }
        }
        return out;
    }

    private static double sq(double x) {
        return x * x;
    }

    /** Axis-angle (normalized axis + radians) -> quaternion [x,y,z,w]. */
    private static double[] axisAngleToQuat(double ax, double ay, double az, double ang) {
        double s = Math.sin(ang / 2), w = Math.cos(ang / 2);
        return new double[]{ax * s, ay * s, az * s, w};
    }

    /** Quaternion [x,y,z,w] -> {axisX, axisY, axisZ, angle(radians)} with a normalized axis. */
    private static double[] quatToAxisAngle(double x, double y, double z, double w) {
        double n = Math.sqrt(x * x + y * y + z * z + w * w);
        if(n > 0) { x /= n; y /= n; z /= n; w /= n; }
        w = Math.max(-1, Math.min(1, w));
        double ang = 2 * Math.acos(w);
        double s = Math.sqrt(1 - w * w);
        if(s < 1e-6)
            return new double[]{0, 0, 1, 0};
        return new double[]{x / s, y / s, z / s, ang};
    }


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
        return new Result(null, glVerts, glVerts, didNrm, didTex, didOtex, false, false, false);
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

    /** The first primitive (across all meshes) whose attributes include POSITION. */
    private static Map<String, Object> firstPrimitiveWithPosition(Map<String, Object> root) {
        for(Map<String, Object> prim : allPrimitives(root)) {
            Map<String, Object> at = attributesOf(prim);
            if(at != null && at.containsKey("POSITION"))
                return prim;
        }
        return null;
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
