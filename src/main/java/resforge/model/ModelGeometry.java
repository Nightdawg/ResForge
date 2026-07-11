package resforge.model;

import resforge.layers.MeshInfo;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembles a flat triangle mesh (a "soup" of per-triangle vertices with
 * positions and normals) from a resource's {@code vbuf2} + {@code mesh} layers,
 * for the in-app 3D viewer. Reuses the same decoders as the glTF export
 * ({@link Vbuf2Data} + {@link MeshInfo}); coordinates are kept in Haven's native
 * Z-up space (the viewer orients its camera with +Z up).
 *
 * <p>Skinned models are assembled in their bind / rest pose (no bone transforms),
 * matching the static-geometry view the glTF export gives. Returns {@code null}
 * from {@link #from} when the resource has no usable geometry.
 */
public final class ModelGeometry {
    /** 9 floats per triangle (3 vertices × xyz), Haven Z-up. */
    public final float[] positions;
    /** 9 floats per triangle (3 vertices × xyz), unit normals. */
    public final float[] normals;
    /** 6 floats per triangle (3 vertices × uv) — texture coordinates (may be 0 where absent). */
    public final float[] uv;
    /** Per triangle: index into {@link #materials}, or -1 if the triangle is untextured. */
    public final int[] triMat;
    /** The textured materials used by the model (in first-seen order); each names the
     *  local-texture palette ordinal it was authored with. The viewer lets the user
     *  re-point each to any palette entry (e.g. mulberry's seasonal leaf variants). */
    public final java.util.List<Material> materials;
    /** The full local-texture palette (every {@code tex} layer's encoded image, in
     *  order; {@code null} where a layer had no usable image). Includes textures not
     *  referenced by any material, so the viewer can offer them as alternatives. */
    public final java.util.List<byte[]> localTextures;
    /** Alpha-mask bytes per palette entry (aligned with {@link #localTextures}); null where none. */
    public final java.util.List<byte[]> localMasks;
    /** The {@code tex} layer id per palette entry (aligned with {@link #localTextures}). */
    public final java.util.List<Integer> localTexIds;
    /** Resolved <em>external static</em> textures (encoded image bytes), appended after the
     *  local palette: a material with an external base has {@code defaultTex} =
     *  {@code localTextures.size() + i} into this combined palette. Empty unless external
     *  resolution was requested (see {@link #from(ResContainer, ExternalTextures.Fetcher)}). */
    public final java.util.List<byte[]> externalTextures;
    /** Alpha-mask bytes per external texture (aligned with {@link #externalTextures}); null where none. */
    public final java.util.List<byte[]> externalMasks;
    public final int triangleCount;
    public final int vertexCount;       // distinct source vertices that fed the soup
    public final int submeshCount;
    /** Axis-aligned bounding box min/max (xyz) and its centre + radius. */
    public final float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
    public final float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
    public final float[] center = new float[3];
    public final float radius;

    /** A textured material: its {@code matid} and the palette ordinal it is drawn with
     *  by default — an index into the <em>combined</em> palette ({@link #localTextures}
     *  followed by any resolved {@link #externalTextures}); for a local-base material this
     *  is just its local ordinal. {@code localBase} is true when its base colour comes from
     *  a local {@code tex} (so the local palette is genuinely its to swap); false for any
     *  non-local base — an external-static material ({@code mlink}/external string &rarr;
     *  another resource, resolved into the external palette), a runtime varmat, a
     *  {@code Dyntex} {@code spr} addition, or a local {@code otex} overlay only —
     *  rendered as an approximation but not offered a picker. */
    public static final class Material {
        public final int matid;
        public final int defaultTex;
        public final boolean localBase;
        Material(int matid, int defaultTex, boolean localBase) {
            this.matid = matid;
            this.defaultTex = defaultTex;
            this.localBase = localBase;
        }
    }

    private ModelGeometry(float[] positions, float[] normals, float[] uv, int[] triMat,
                          java.util.List<Material> materials, java.util.List<byte[]> localTextures,
                          java.util.List<byte[]> localMasks, java.util.List<Integer> localTexIds,
                          java.util.List<byte[]> externalTextures, java.util.List<byte[]> externalMasks,
                          int triangleCount, int vertexCount, int submeshCount) {
        this.positions = positions;
        this.normals = normals;
        this.uv = uv;
        this.triMat = triMat;
        this.materials = materials;
        this.localTextures = localTextures;
        this.localMasks = localMasks;
        this.localTexIds = localTexIds;
        this.externalTextures = externalTextures;
        this.externalMasks = externalMasks;
        this.triangleCount = triangleCount;
        this.vertexCount = vertexCount;
        this.submeshCount = submeshCount;
        for(int i = 0; i < positions.length; i += 3)
            for(int a = 0; a < 3; a++) {
                float v = positions[i + a];
                if(v < min[a]) min[a] = v;
                if(v > max[a]) max[a] = v;
            }
        float r = 0;
        if(triangleCount > 0) {
            for(int a = 0; a < 3; a++)
                center[a] = (min[a] + max[a]) / 2f;
            for(int i = 0; i < positions.length; i += 3) {
                float dx = positions[i] - center[0];
                float dy = positions[i + 1] - center[1];
                float dz = positions[i + 2] - center[2];
                float d2 = dx * dx + dy * dy + dz * dz;
                if(d2 > r) r = d2;
            }
            r = (float) Math.sqrt(r);
        }
        this.radius = (r > 0) ? r : 1f;
    }

    /** True if at least one material has a resolvable local texture. */
    public boolean hasTextures() {
        return !materials.isEmpty();
    }

    /** Build the geometry for a resource (local textures only), or {@code null} if it
     *  has no geometry. Equivalent to {@link #from(ResContainer, ExternalTextures.Fetcher)}
     *  with no fetcher (so external static materials stay unresolved/shaded). */
    public static ModelGeometry from(ResContainer res) {
        return from(res, null, Integer.MAX_VALUE);
    }

    /** Build the geometry for a resource, or {@code null} if it has no geometry. When a
     *  {@code fetcher} is supplied, materials whose base is an <em>external static</em>
     *  material ({@code mlink}/external {@code tex} string &rarr; another resource) are
     *  resolved by fetching that resource (see {@link ExternalTextures}) and textured from
     *  the appended external palette; a {@code null} fetcher leaves them shaded. */
    public static ModelGeometry from(ResContainer res, ExternalTextures.Fetcher fetcher) {
        return from(res, fetcher, Integer.MAX_VALUE);
    }

    /** Build geometry while rejecting a triangle count above {@code maxTriangles}
     * before allocating the triangle soup. Used by bounded GUI previews. */
    public static ModelGeometry from(ResContainer res, ExternalTextures.Fetcher fetcher,
                                     int maxTriangles) {
        if(maxTriangles < 0)
            throw new IllegalArgumentException("maxTriangles must be non-negative");
        Map<Integer, Vbuf2Data> vbufs = new LinkedHashMap<>();
        for(Layer l : res.layers)
            if(l.name.equals("vbuf2")) {
                Vbuf2Data d = Vbuf2Data.parse(l.data);
                if(d != null)
                    vbufs.putIfAbsent(d.id, d);
            }
        java.util.List<MeshInfo> meshes = new java.util.ArrayList<>();
        int tris = 0;
        for(Layer l : res.layers)
            if(l.name.equals("mesh")) {
                MeshInfo mi = MeshInfo.parse(l.data);
                if(mi.recognized && mi.indices != null && vbufs.containsKey(mi.vbufid)
                        && vbufs.get(mi.vbufid).get("pos") != null) {
                    int added = mi.indices.length / 3;
                    if(added > maxTriangles - tris)
                        throw new IllegalArgumentException("3D preview exceeds the triangle limit of "
                                + maxTriangles);
                    meshes.add(mi);
                    tris += added;
                }
            }
        if(tris == 0)
            return null;

        LocalTextures lt = LocalTextures.from(res);
        Map<Integer, ExternalTextures.Resolved> ext = ExternalTextures.resolve(res, fetcher);
        // Distinct textured materials in first-seen mesh order; each remembers the
        // palette ordinal of its authored texture. triMat maps a triangle to one.
        java.util.List<Material> materials = new java.util.ArrayList<>();
        Map<Integer, Integer> matidToMat = new java.util.HashMap<>();
        // Resolved external textures, appended after the local palette.
        java.util.List<byte[]> externalTextures = new java.util.ArrayList<>();
        java.util.List<byte[]> externalMasks = new java.util.ArrayList<>();
        Map<Integer, Integer> matidToExtOrd = new java.util.HashMap<>();
        int localCount = lt.images.size();

        float[] pos = new float[tris * 9];
        float[] nrm = new float[tris * 9];
        float[] uv = new float[tris * 6];
        int[] triMat = new int[tris];
        int o = 0;          // pos/nrm cursor (9 per triangle)
        int uo = 0;         // uv cursor (6 per triangle)
        int tri = 0;        // emitted-triangle counter
        java.util.Set<Integer> usedVbufs = new java.util.LinkedHashSet<>();
        for(MeshInfo m : meshes) {
            Vbuf2Data d = vbufs.get(m.vbufid);
            float[] vp = d.get("pos");
            float[] vn = d.get("nrm");
            float[] vt = d.get("tex");
            usedVbufs.add(m.vbufid);

            int matIndex = -1;
            Integer ord = (vt != null) ? lt.ordForMatid(m.matid) : null;
            if(ord != null && lt.images.get(ord) != null) {
                Integer mi = matidToMat.get(m.matid);
                if(mi == null) {
                    mi = materials.size();
                    materials.add(new Material(m.matid, ord, lt.isLocalBaseTex(m.matid)));
                    matidToMat.put(m.matid, mi);
                }
                matIndex = mi;
            } else if(vt != null) {
                ExternalTextures.Resolved r = ext.get(m.matid);
                if(r != null && r.image != null) {
                    Integer eo = matidToExtOrd.get(m.matid);
                    if(eo == null) {
                        eo = externalTextures.size();
                        externalTextures.add(r.image);
                        externalMasks.add(r.mask);
                        matidToExtOrd.put(m.matid, eo);
                    }
                    Integer mi = matidToMat.get(m.matid);
                    if(mi == null) {
                        mi = materials.size();
                        materials.add(new Material(m.matid, localCount + eo, false));
                        matidToMat.put(m.matid, mi);
                    }
                    matIndex = mi;
                }
            }

            short[] idx = m.indices;
            for(int t = 0; t + 2 < idx.length; t += 3) {
                int a = idx[t] & 0xffff, b = idx[t + 1] & 0xffff, c = idx[t + 2] & 0xffff;
                if(a >= d.num || b >= d.num || c >= d.num)
                    continue;
                int[] vidx = {a, b, c};
                float[] face = (vn == null) ? faceNormal(vp, a, b, c) : null;
                for(int k = 0; k < 3; k++) {
                    int v = vidx[k];
                    pos[o] = vp[v * 3];
                    pos[o + 1] = vp[v * 3 + 1];
                    pos[o + 2] = vp[v * 3 + 2];
                    if(vn != null) {
                        nrm[o] = vn[v * 3];
                        nrm[o + 1] = vn[v * 3 + 1];
                        nrm[o + 2] = vn[v * 3 + 2];
                    } else {
                        nrm[o] = face[0];
                        nrm[o + 1] = face[1];
                        nrm[o + 2] = face[2];
                    }
                    if(vt != null) {
                        uv[uo] = vt[v * 2];
                        uv[uo + 1] = vt[v * 2 + 1];
                    }
                    o += 3;
                    uo += 2;
                }
                triMat[tri++] = matIndex;
            }
        }
        if(o == 0)
            return null;
        if(tri != tris) {   // some triangles were skipped (out-of-range indices)
            pos = java.util.Arrays.copyOf(pos, o);
            nrm = java.util.Arrays.copyOf(nrm, o);
            uv = java.util.Arrays.copyOf(uv, uo);
            triMat = java.util.Arrays.copyOf(triMat, tri);
        }
        int verts = 0;          // distinct source vertices (vbufs are shared across submeshes)
        for(int id : usedVbufs)
            verts += vbufs.get(id).num;
        return new ModelGeometry(pos, nrm, uv, triMat, materials,
                lt.images, lt.masks, lt.texIds, externalTextures, externalMasks,
                tri, verts, meshes.size());
    }

    private static float[] faceNormal(float[] vp, int a, int b, int c) {
        float ux = vp[b * 3] - vp[a * 3], uy = vp[b * 3 + 1] - vp[a * 3 + 1], uz = vp[b * 3 + 2] - vp[a * 3 + 2];
        float wx = vp[c * 3] - vp[a * 3], wy = vp[c * 3 + 1] - vp[a * 3 + 1], wz = vp[c * 3 + 2] - vp[a * 3 + 2];
        float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if(len < 1e-9f)
            return new float[]{0, 0, 1};
        return new float[]{nx / len, ny / len, nz / len};
    }
}
