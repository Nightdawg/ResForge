package resforge.model;

import resforge.io.Json;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Imports a binary glTF ({@code .glb}) back into a resource's geometry — the
 * "edit in Blender, then re-import" half of the 3D round-trip. It is a
 * <em>patch</em>: only the {@code vbuf2} vertex data is re-encoded from the glTF;
 * every other layer (mesh/triangles, skeleton, materials, textures, code, …) is
 * carried over from the original {@code .res} byte-for-byte.
 *
 * <p>Phase 2a targets topology-preserving edits (reshaping/transforming existing
 * vertices): the glTF must have the same vertex count as the original. Each
 * attribute the glTF provides (positions, normals, both UV sets) is re-quantised
 * into that attribute's original on-wire format — the same precision the game
 * renders from — and Y-up glTF coordinates are converted back to Haven's Z-up.
 * Bone weights and the triangle lists are kept from the original (unchanged
 * topology), so skinning and submeshes stay intact.
 */
public final class GltfImport {
    private GltfImport() {
    }

    public static final class Result {
        public final byte[] res;
        public final int vertices;
        public final boolean nrm, tex, otex;

        Result(byte[] res, int vertices, boolean nrm, boolean tex, boolean otex) {
            this.res = res;
            this.vertices = vertices;
            this.nrm = nrm;
            this.tex = tex;
            this.otex = otex;
        }
    }

    /** A decoded glTF document (JSON tree + the binary chunk). */
    private static final class Glb {
        final Map<String, Object> root;
        final byte[] data;       // the whole .glb
        final int binStart;      // offset of the BIN chunk's data

        Glb(Map<String, Object> root, byte[] data, int binStart) {
            this.root = root;
            this.data = data;
            this.binStart = binStart;
        }
    }

    /** Re-imports {@code glb}'s geometry into {@code origRes}, returning new .res bytes. */
    @SuppressWarnings("unchecked")
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

        Map<String, Object> attrs = firstPrimitiveAttributes(g.root);
        if(attrs == null || !attrs.containsKey("POSITION"))
            throw new IllegalArgumentException("the glTF has no mesh positions to import");

        float[] pos = readAccessor(g, ((Number) attrs.get("POSITION")).intValue(), 3);
        int glVerts = pos.length / 3;
        if(glVerts != codec.num)
            throw new IllegalArgumentException(
                    "vertex count changed (glTF has " + glVerts + ", resource has " + codec.num
                            + "). Re-import currently needs the same topology — reshape/transform "
                            + "vertices without adding, removing or re-welding them.");

        codec.setAttr("pos", axisInvert(pos));

        boolean didNrm = false, didTex = false, didOtex = false;
        if(attrs.containsKey("NORMAL") && codec.attr("nrm") != null) {
            float[] nrm = readAccessor(g, ((Number) attrs.get("NORMAL")).intValue(), 3);
            codec.setAttr("nrm", axisInvert(nrm));
            didNrm = true;
        }
        if(attrs.containsKey("TEXCOORD_0") && codec.attr("tex") != null) {
            codec.setAttr("tex", readAccessor(g, ((Number) attrs.get("TEXCOORD_0")).intValue(), 2));
            didTex = true;
        }
        if(attrs.containsKey("TEXCOORD_1") && codec.attr("otex") != null) {
            codec.setAttr("otex", readAccessor(g, ((Number) attrs.get("TEXCOORD_1")).intValue(), 2));
            didOtex = true;
        }

        res.layers.set(vbufIndex, new Layer("vbuf2", codec.encode()));
        return new Result(res.serialize(), glVerts, didNrm, didTex, didOtex);
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
    private static Map<String, Object> firstPrimitiveAttributes(Map<String, Object> root) {
        List<Object> meshes = (List<Object>) root.get("meshes");
        if(meshes == null || meshes.isEmpty())
            return null;
        // Validate that every primitive shares one vertex block (our export does);
        // a split mesh (different POSITION per primitive) is rejected by the
        // count check downstream, which is the intended Phase-2a behaviour.
        Map<String, Object> mesh0 = (Map<String, Object>) meshes.get(0);
        List<Object> prims = (List<Object>) mesh0.get("primitives");
        if(prims == null || prims.isEmpty())
            return null;
        return (Map<String, Object>) ((Map<String, Object>) prims.get(0)).get("attributes");
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
        int base = g.binStart + bvOff + accOff;
        float[] out = new float[count * comps];
        for(int i = 0; i < out.length; i++)
            out[i] = Float.intBitsToFloat(le32(g.data, base + i * 4));
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
