package resforge.model;

import resforge.layers.MeshInfo;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports a resource's 3D geometry ({@code vbuf2} vertex buffers + {@code mesh}
 * triangle lists) to a Wavefront OBJ — a universally viewable format (Blender,
 * MeshLab, Windows 3D Viewer). Read-only: positions, texture coords and normals
 * are de-quantised to floats; each {@code mesh} layer becomes an OBJ group.
 */
public class ObjExport {
    public static class Result {
        public final String obj;
        public final int vertices, triangles, submeshes;

        Result(String obj, int vertices, int triangles, int submeshes) {
            this.obj = obj;
            this.vertices = vertices;
            this.triangles = triangles;
            this.submeshes = submeshes;
        }
    }

    public static Result toObj(ResContainer res, String sourceName) {
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

        StringBuilder vsec = new StringBuilder(), tsec = new StringBuilder(), nsec = new StringBuilder();
        Map<Integer, int[]> base = new LinkedHashMap<>();   // vbufid -> {vBase, tBase, nBase}
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
            base.put(e.getKey(), new int[]{vBase, tBase, nBase});
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Wavefront OBJ exported by ResForge from ").append(sourceName).append('\n');
        sb.append("# ").append(vbufs.size()).append(" vertex buffer(s), ")
          .append(meshes.size()).append(" submesh(es)\n");
        sb.append(vsec).append(tsec).append(nsec);

        int triangles = 0, submeshes = 0;
        for(int mi = 0; mi < meshes.size(); mi++) {
            MeshInfo m = meshes.get(mi);
            int[] b = base.get(m.vbufid);
            if(b == null)
                continue;
            submeshes++;
            sb.append("g submesh").append(mi);
            if(m.matid >= 0)
                sb.append("_mat").append(m.matid);
            sb.append('\n');
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
        return new Result(sb.toString(), vertices, triangles, submeshes);
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
