package resforge.model;

import resforge.layers.Mat2Codec;
import resforge.layers.MeshInfo;
import resforge.layers.TexInfo;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports a resource's 3D geometry ({@code vbuf2} vertex buffers + {@code mesh}
 * triangle lists) to a Wavefront OBJ — a universally viewable format (Blender,
 * MeshLab, Windows 3D Viewer). Read-only: positions, texture coords and normals
 * are de-quantised to floats; each {@code mesh} layer becomes an OBJ group.
 *
 * <p>When the resource carries its own {@code tex} (texture) layers, a companion
 * {@code .mtl} material library and the texture image file(s) are produced too,
 * so the model opens <em>textured</em>. Material→texture assignment follows the
 * {@code mesh.matid → mat2.id → local tex} chain; for a single texture it is
 * applied to everything. Textures referenced indirectly (a {@code mat2}
 * {@code mlink}/{@code tex} pointing at <em>another</em> resource) are not
 * fetched — only this resource's own {@code tex} layers are exported, and the
 * per-mesh mapping for multi-texture models is best-effort.
 */
public class ObjExport {
    public static final class TexFile {
        public final String name;     // suggested filename, relative to the .obj
        public final byte[] data;     // the encoded image bytes (JPEG/PNG/...)

        TexFile(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    public static class Result {
        public final String obj;
        public final String mtl;              // material library text, or null if no textures
        public final String baseName;         // base used for the .mtl + texture filenames
        public final List<TexFile> textures;  // texture image files to write next to the .obj
        public final int vertices, triangles, submeshes;

        Result(String obj, String mtl, String baseName, List<TexFile> textures,
               int vertices, int triangles, int submeshes) {
            this.obj = obj;
            this.mtl = mtl;
            this.baseName = baseName;
            this.textures = textures;
            this.vertices = vertices;
            this.triangles = triangles;
            this.submeshes = submeshes;
        }
    }

    /** One local texture: its exported file name + the material name that references it. */
    private static final class TexMat {
        final String fileName;     // e.g. "male_tex0.jpg"
        final String matName;      // e.g. "tex0"
        final byte[] image;

        TexMat(String fileName, String matName, byte[] image) {
            this.fileName = fileName;
            this.matName = matName;
            this.image = image;
        }
    }

    public static Result toObj(ResContainer res, String sourceName) {
        String base = baseName(sourceName);

        Map<Integer, Vbuf2Data> vbufs = new LinkedHashMap<>();
        for(Layer l : res.layers) {
            if(l.name.equals("vbuf2")) {
                Vbuf2Data d = Vbuf2Data.parse(l.data);
                if(d != null)
                    vbufs.putIfAbsent(d.id, d);
            }
        }
        List<MeshInfo> meshes = new ArrayList<>();
        for(Layer l : res.layers) {
            if(l.name.equals("mesh")) {
                MeshInfo mi = MeshInfo.parse(l.data);
                if(mi.recognized && mi.indices != null)
                    meshes.add(mi);
            }
        }

        List<TexMat> texMats = collectTextures(res, base);
        Map<Integer, Integer> matToTex = collectMatToTex(res, texMats.size());

        StringBuilder vsec = new StringBuilder(), tsec = new StringBuilder(), nsec = new StringBuilder();
        Map<Integer, int[]> bases = new LinkedHashMap<>();   // vbufid -> {vBase, tBase, nBase}
        int vc = 0, tc = 0, nc = 0, vertices = 0;
        for(Map.Entry<Integer, Vbuf2Data> e : vbufs.entrySet()) {
            Vbuf2Data d = e.getValue();
            float[] pos = d.get("pos"), nrm = d.get("nrm"), tex = d.get("tex");
            if(pos == null)
                continue;
            int vBase = vc + 1, tBase = -1, nBase = -1;
            for(int i = 0; i < d.num; i++)
                vsec.append("v ").append(f(pos[i * 3])).append(' ').append(f(pos[i * 3 + 1]))
                    .append(' ').append(f(pos[i * 3 + 2])).append('\n');
            vc += d.num;
            vertices += d.num;
            if(tex != null) {
                tBase = tc + 1;
                for(int i = 0; i < d.num; i++)
                    tsec.append("vt ").append(f(tex[i * 2])).append(' ').append(f(tex[i * 2 + 1])).append('\n');
                tc += d.num;
            }
            if(nrm != null) {
                nBase = nc + 1;
                for(int i = 0; i < d.num; i++)
                    nsec.append("vn ").append(f(nrm[i * 3])).append(' ').append(f(nrm[i * 3 + 1]))
                        .append(' ').append(f(nrm[i * 3 + 2])).append('\n');
                nc += d.num;
            }
            bases.put(e.getKey(), new int[]{vBase, tBase, nBase});
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Wavefront OBJ exported by ResForge from ").append(sourceName).append('\n');
        sb.append("# ").append(vbufs.size()).append(" vertex buffer(s), ")
          .append(meshes.size()).append(" submesh(es)\n");
        if(!texMats.isEmpty()) {
            sb.append("mtllib ").append(base).append(".mtl\n");
            if(texMats.size() > 1)
                sb.append("# Note: multi-texture model — per-mesh texture mapping is best-effort;\n")
                  .append("#       textures linked from other resources (mat2 mlink) are not included.\n");
        }
        sb.append(vsec).append(tsec).append(nsec);

        int triangles = 0, submeshes = 0;
        for(int mi = 0; mi < meshes.size(); mi++) {
            MeshInfo m = meshes.get(mi);
            int[] b = bases.get(m.vbufid);
            if(b == null)
                continue;
            submeshes++;
            sb.append("g submesh").append(mi);
            if(m.matid >= 0)
                sb.append("_mat").append(m.matid);
            sb.append('\n');
            if(!texMats.isEmpty()) {
                int ord = texOrdFor(m.matid, matToTex, texMats.size());
                sb.append("usemtl ").append(texMats.get(ord).matName).append('\n');
            }
            int vBase = b[0], tBase = b[1], nBase = b[2];
            for(int t = 0; t + 2 < m.indices.length; t += 3) {
                sb.append('f');
                for(int k = 0; k < 3; k++) {
                    int idx = m.indices[t + k] & 0xffff;
                    sb.append(' ').append(ref(vBase + idx,
                            tBase < 0 ? -1 : tBase + idx,
                            nBase < 0 ? -1 : nBase + idx));
                }
                sb.append('\n');
                triangles++;
            }
        }

        String mtl = texMats.isEmpty() ? null : buildMtl(texMats, sourceName);
        List<TexFile> texFiles = new ArrayList<>();
        for(TexMat tm : texMats)
            texFiles.add(new TexFile(tm.fileName, tm.image));

        return new Result(sb.toString(), mtl, base, texFiles, vertices, triangles, submeshes);
    }

    /** Collects this resource's own tex layers as exportable images + material names. */
    private static List<TexMat> collectTextures(ResContainer res, String base) {
        List<TexMat> out = new ArrayList<>();
        int ord = 0;
        for(Layer l : res.layers) {
            if(!l.name.equals("tex"))
                continue;
            TexInfo ti = TexInfo.parse(l.data);
            if(!ti.found)
                continue;
            byte[] image = Arrays.copyOfRange(l.data, ti.imageOffset, ti.imageOffset + ti.imageLen);
            String ext = ti.imageFormat != null ? ti.imageFormat : "img";
            out.add(new TexMat(base + "_tex" + ord + "." + ext, "tex" + ord, image));
            ord++;
        }
        return out;
    }

    /** Maps a material id (mat2.id) to a local-tex ordinal, via the first local tex/otex index. */
    private static Map<Integer, Integer> collectMatToTex(ResContainer res, int texCount) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        if(texCount == 0)
            return map;
        for(Layer l : res.layers) {
            if(!l.name.equals("mat2"))
                continue;
            try {
                Map<String, Object> m = Mat2Codec.decode(l.data);
                int id = ((Number) m.get("id")).intValue();
                Integer ord = firstLocalTexOrdinal((List<?>) m.get("entries"), texCount);
                if(ord != null)
                    map.put(id, ord);
            } catch(RuntimeException ignored) {
            }
        }
        return map;
    }

    /** The local-tex ordinal a material references, or null (e.g. it links an external tex). */
    private static Integer firstLocalTexOrdinal(List<?> entries, int texCount) {
        for(Object e : entries) {
            Map<?, ?> entry = (Map<?, ?>) e;
            String key = String.valueOf(entry.get("key"));
            if(!key.equals("tex") && !key.equals("otex"))
                continue;
            List<?> vals = (List<?>) entry.get("values");
            if(vals.isEmpty())
                continue;
            // A leading string means the texture lives in another resource (mlink-style) — skip.
            if(vals.get(0) instanceof String)
                continue;
            Object first = vals.get(0);
            if(first instanceof Map) {
                Object v = ((Map<?, ?>) first).values().iterator().next();
                if(v instanceof Number) {
                    int k = ((Number) v).intValue();
                    if(k >= 0 && k < texCount)
                        return k;
                }
            }
        }
        return null;
    }

    private static int texOrdFor(int matid, Map<Integer, Integer> matToTex, int texCount) {
        if(texCount == 1)
            return 0;                               // single texture: applies to everything
        Integer ord = matToTex.get(matid);
        return (ord != null) ? ord : 0;             // best-effort; default to the first texture
    }

    private static String buildMtl(List<TexMat> texMats, String sourceName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Material library exported by ResForge from ").append(sourceName).append('\n');
        for(TexMat tm : texMats) {
            sb.append("newmtl ").append(tm.matName).append('\n');
            sb.append("Ka 1.000 1.000 1.000\n");
            sb.append("Kd 1.000 1.000 1.000\n");
            sb.append("d 1.0\n");
            sb.append("illum 1\n");
            sb.append("map_Kd ").append(tm.fileName).append('\n');
        }
        return sb.toString();
    }

    private static String baseName(String sourceName) {
        String n = sourceName;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if(slash >= 0)
            n = n.substring(slash + 1);
        if(n.toLowerCase(Locale.ROOT).endsWith(".res"))
            n = n.substring(0, n.length() - 4);
        return n.isEmpty() ? "model" : n;
    }

    private static String ref(int v, int t, int n) {
        if(t < 0 && n < 0) return Integer.toString(v);
        if(t < 0) return v + "//" + n;
        if(n < 0) return v + "/" + t;
        return v + "/" + t + "/" + n;
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
