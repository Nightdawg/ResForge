package resforge.model;

import resforge.io.Json;
import resforge.layers.Mat2Codec;
import resforge.layers.MeshInfo;
import resforge.layers.TexInfo;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports a resource's 3D geometry ({@code vbuf2} vertex buffers + {@code mesh}
 * triangle lists) to a binary glTF 2.0 file ({@code .glb}) — a modern, JSON-based
 * format with native import/export support in Blender. Unlike the OBJ export, glTF
 * carries <em>both</em> of Haven's texture-coordinate sets ({@code tex} →
 * {@code TEXCOORD_0}, {@code otex} → {@code TEXCOORD_1}) and is the basis for the
 * eventual round-trip (skeleton, animation and morph support to follow).
 *
 * <p>This first stage exports static, textured geometry: positions, normals and
 * the two UV sets per vertex buffer, one primitive per {@code mesh} submesh, PBR
 * materials referencing the resource's own {@code tex} layers (embedded in the
 * {@code .glb}), and the {@code mesh.matid → mat2 → local tex} mapping reused from
 * the OBJ exporter. Coordinates are converted from Haven's Z-up to glTF's Y-up.
 * The {@code .glb} is fully self-contained (geometry + textures in one file).
 */
public final class GltfExport {
    public static final class Result {
        public final byte[] glb;
        public final int vertices, triangles, submeshes, textures;

        Result(byte[] glb, int vertices, int triangles, int submeshes, int textures) {
            this.glb = glb;
            this.vertices = vertices;
            this.triangles = triangles;
            this.submeshes = submeshes;
            this.textures = textures;
        }
    }

    private static final int FLOAT = 5126, USHORT = 5123;
    private static final int ARRAY_BUFFER = 34962, ELEMENT_ARRAY_BUFFER = 34963;

    private static final class TexMat {
        final String matName;
        final byte[] image;
        final String mime;

        TexMat(String matName, byte[] image, String mime) {
            this.matName = matName;
            this.image = image;
            this.mime = mime;
        }
    }

    /** A growing little-endian binary buffer with 4-byte alignment helpers. */
    private static final class Buf {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();

        int size() {
            return o.size();
        }

        void align4() {
            while((o.size() & 3) != 0)
                o.write(0);
        }

        void f32(float v) {
            int b = Float.floatToIntBits(v);
            o.write(b & 0xff);
            o.write((b >>> 8) & 0xff);
            o.write((b >>> 16) & 0xff);
            o.write((b >>> 24) & 0xff);
        }

        void u16(int v) {
            o.write(v & 0xff);
            o.write((v >>> 8) & 0xff);
        }

        void bytes(byte[] b) {
            o.writeBytes(b);
        }

        byte[] toByteArray() {
            return o.toByteArray();
        }
    }

    public static Result toGlb(ResContainer res, String sourceName) {
        Map<Integer, Vbuf2Data> vbufs = new LinkedHashMap<>();
        for(Layer l : res.layers)
            if(l.name.equals("vbuf2")) {
                Vbuf2Data d = Vbuf2Data.parse(l.data);
                if(d != null)
                    vbufs.putIfAbsent(d.id, d);
            }
        List<MeshInfo> meshes = new ArrayList<>();
        for(Layer l : res.layers)
            if(l.name.equals("mesh")) {
                MeshInfo mi = MeshInfo.parse(l.data);
                if(mi.recognized && mi.indices != null)
                    meshes.add(mi);
            }

        List<TexMat> texMats = collectTextures(res);
        Map<Integer, Integer> matToTex = collectMatToTex(res, texMats.size());

        Buf bin = new Buf();
        List<Object> bufferViews = new ArrayList<>();
        List<Object> accessors = new ArrayList<>();

        // Per vertex-buffer: the attribute accessor indices shared by its submeshes.
        Map<Integer, Map<String, Object>> vbufAttribs = new LinkedHashMap<>();
        int vertices = 0;
        for(Map.Entry<Integer, Vbuf2Data> e : vbufs.entrySet()) {
            Vbuf2Data d = e.getValue();
            float[] pos = d.get("pos");
            if(pos == null)
                continue;
            Map<String, Object> attribs = new LinkedHashMap<>();
            attribs.put("POSITION", addVec3(bin, bufferViews, accessors, pos, d.num, true, true));
            float[] nrm = d.get("nrm");
            if(nrm != null)
                attribs.put("NORMAL", addVec3(bin, bufferViews, accessors, nrm, d.num, true, false));
            float[] tex = d.get("tex");
            if(tex != null)
                attribs.put("TEXCOORD_0", addVec2(bin, bufferViews, accessors, tex, d.num));
            float[] otex = d.get("otex");
            if(otex != null)
                attribs.put("TEXCOORD_1", addVec2(bin, bufferViews, accessors, otex, d.num));
            vbufAttribs.put(e.getKey(), attribs);
            vertices += d.num;
        }

        List<Object> primitives = new ArrayList<>();
        int triangles = 0, submeshes = 0;
        for(MeshInfo m : meshes) {
            Map<String, Object> attribs = vbufAttribs.get(m.vbufid);
            if(attribs == null)
                continue;
            submeshes++;
            int idxAccessor = addIndices(bin, bufferViews, accessors, m.indices);
            Map<String, Object> prim = new LinkedHashMap<>();
            prim.put("attributes", new LinkedHashMap<>(attribs));
            prim.put("indices", idxAccessor);
            if(!texMats.isEmpty())
                prim.put("material", texOrdFor(m.matid, matToTex, texMats.size()));
            primitives.add(prim);
            triangles += m.indices.length / 3;
        }

        String base = baseName(sourceName);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("asset", obj("version", "2.0", "generator", "ResForge"));
        root.put("scene", 0);
        root.put("scenes", List.of(obj("nodes", List.of(0))));
        root.put("nodes", List.of(obj("mesh", 0, "name", base)));
        root.put("meshes", List.of(obj("name", base, "primitives", primitives)));

        if(!texMats.isEmpty()) {
            List<Object> images = new ArrayList<>();
            List<Object> textures = new ArrayList<>();
            List<Object> materials = new ArrayList<>();
            for(int i = 0; i < texMats.size(); i++) {
                TexMat tm = texMats.get(i);
                int bv = addImage(bin, bufferViews, tm.image);
                images.add(obj("bufferView", bv, "mimeType", tm.mime));
                textures.add(obj("source", i, "sampler", 0));
                Map<String, Object> pbr = new LinkedHashMap<>();
                pbr.put("baseColorTexture", obj("index", i, "texCoord", 0));
                pbr.put("metallicFactor", 0.0);
                pbr.put("roughnessFactor", 1.0);
                Map<String, Object> mat = new LinkedHashMap<>();
                mat.put("name", tm.matName);
                mat.put("doubleSided", Boolean.TRUE);
                mat.put("alphaMode", "MASK");
                mat.put("alphaCutoff", 0.5);
                mat.put("pbrMetallicRoughness", pbr);
                materials.add(mat);
            }
            root.put("materials", materials);
            root.put("textures", textures);
            root.put("images", images);
            root.put("samplers", List.of(obj("wrapS", 10497, "wrapT", 10497)));
        }

        root.put("accessors", accessors);
        root.put("bufferViews", bufferViews);
        root.put("buffers", List.of(obj("byteLength", bin.size())));

        byte[] glb = assembleGlb(Json.write(root), bin.toByteArray());
        return new Result(glb, vertices, triangles, submeshes, texMats.size());
    }

    /* ------------------------------------------------------- accessors/buffers */

    private static Integer addVec3(Buf bin, List<Object> bvs, List<Object> accs,
                                   float[] data, int num, boolean convert, boolean minmax) {
        bin.align4();
        int off = bin.size();
        double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        float[] c = new float[3];
        for(int i = 0; i < num; i++) {
            float x = data[i * 3], y = data[i * 3 + 1], z = data[i * 3 + 2];
            if(convert) {
                c[0] = x; c[1] = z; c[2] = -y;          // Haven Z-up -> glTF Y-up
            } else {
                c[0] = x; c[1] = y; c[2] = z;
            }
            for(int k = 0; k < 3; k++) {
                bin.f32(c[k]);
                if(c[k] < mn[k]) mn[k] = c[k];
                if(c[k] > mx[k]) mx[k] = c[k];
            }
        }
        int bv = addBufferView(bvs, off, bin.size() - off, ARRAY_BUFFER);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", FLOAT);
        acc.put("count", num);
        acc.put("type", "VEC3");
        if(minmax) {
            acc.put("min", List.of(mn[0], mn[1], mn[2]));
            acc.put("max", List.of(mx[0], mx[1], mx[2]));
        }
        accs.add(acc);
        return accs.size() - 1;
    }

    private static Integer addVec2(Buf bin, List<Object> bvs, List<Object> accs, float[] data, int num) {
        bin.align4();
        int off = bin.size();
        for(int i = 0; i < num; i++) {
            bin.f32(data[i * 2]);
            bin.f32(data[i * 2 + 1]);
        }
        int bv = addBufferView(bvs, off, bin.size() - off, ARRAY_BUFFER);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", FLOAT);
        acc.put("count", num);
        acc.put("type", "VEC2");
        accs.add(acc);
        return accs.size() - 1;
    }

    private static Integer addIndices(Buf bin, List<Object> bvs, List<Object> accs, short[] indices) {
        bin.align4();
        int off = bin.size();
        for(short s : indices)
            bin.u16(s & 0xffff);
        int bv = addBufferView(bvs, off, bin.size() - off, ELEMENT_ARRAY_BUFFER);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", USHORT);
        acc.put("count", indices.length);
        acc.put("type", "SCALAR");
        accs.add(acc);
        return accs.size() - 1;
    }

    private static int addImage(Buf bin, List<Object> bvs, byte[] image) {
        bin.align4();
        int off = bin.size();
        bin.bytes(image);
        return addBufferView(bvs, off, bin.size() - off, -1);
    }

    private static int addBufferView(List<Object> bvs, int off, int len, int target) {
        Map<String, Object> bv = new LinkedHashMap<>();
        bv.put("buffer", 0);
        bv.put("byteOffset", off);
        bv.put("byteLength", len);
        if(target > 0)
            bv.put("target", target);
        bvs.add(bv);
        return bvs.size() - 1;
    }

    /* ----------------------------------------------------------- glb container */

    private static byte[] assembleGlb(String json, byte[] bin) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] jsonChunk = pad(jsonBytes, (byte) 0x20);      // pad with spaces
        byte[] binChunk = pad(bin, (byte) 0x00);             // pad with zeros
        int total = 12 + 8 + jsonChunk.length + 8 + binChunk.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        le(out, 0x46546C67);                                  // "glTF"
        le(out, 2);                                            // version
        le(out, total);
        le(out, jsonChunk.length);
        le(out, 0x4E4F534A);                                  // "JSON"
        out.writeBytes(jsonChunk);
        le(out, binChunk.length);
        le(out, 0x004E4942);                                  // "BIN\0"
        out.writeBytes(binChunk);
        return out.toByteArray();
    }

    private static byte[] pad(byte[] data, byte fill) {
        int padded = (data.length + 3) & ~3;
        if(padded == data.length)
            return data;
        byte[] r = Arrays.copyOf(data, padded);
        for(int i = data.length; i < padded; i++)
            r[i] = fill;
        return r;
    }

    private static void le(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >>> 8) & 0xff);
        o.write((v >>> 16) & 0xff);
        o.write((v >>> 24) & 0xff);
    }

    /* ------------------------------------------------- textures / material map */

    private static List<TexMat> collectTextures(ResContainer res) {
        List<TexMat> out = new ArrayList<>();
        int ord = 0;
        for(Layer l : res.layers) {
            if(!l.name.equals("tex"))
                continue;
            TexInfo ti = TexInfo.parse(l.data);
            if(!ti.found)
                continue;
            byte[] image = Arrays.copyOfRange(l.data, ti.imageOffset, ti.imageOffset + ti.imageLen);
            out.add(new TexMat("tex" + ord, image, mime(ti.imageFormat)));
            ord++;
        }
        return out;
    }

    private static String mime(String fmt) {
        if(fmt == null)
            return "image/png";
        switch(fmt.toLowerCase(Locale.ROOT)) {
            case "jpg":
            case "jpeg": return "image/jpeg";
            default:     return "image/png";
        }
    }

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

    private static Integer firstLocalTexOrdinal(List<?> entries, int texCount) {
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
            return 0;
        Integer ord = matToTex.get(matid);
        return (ord != null) ? ord : 0;
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

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for(int i = 0; i + 1 < kv.length; i += 2)
            m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
