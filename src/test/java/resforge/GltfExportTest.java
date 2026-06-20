package resforge;

import resforge.io.Json;
import resforge.io.MessageWriter;
import resforge.model.GltfExport;
import resforge.res.Layer;
import resforge.res.ResContainer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfExportTest {

    /** vbuf2 ver=0 with pos2(f4) + tex2(f4) [+ optional otex2(f4)], 3 vertices. */
    private static byte[] vbuf(boolean withOtex) {
        MessageWriter w = new MessageWriter();
        w.uint8(0);                 // fl -> ver 0
        w.uint16(3);                // 3 vertices
        w.string("pos2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})
            w.float32(v);
        w.string("nrm2").uint8(1).string("f4");
        for(int i = 0; i < 9; i++)
            w.float32(0f);
        w.string("tex2").uint8(1).string("f4");
        for(int i = 0; i < 6; i++)
            w.float32(0.5f);
        if(withOtex) {
            w.string("otex2").uint8(1).string("f4");
            for(int i = 0; i < 6; i++)
                w.float32(0.25f);
        }
        return w.toByteArray();
    }

    private static byte[] mesh(int matid) {
        MessageWriter w = new MessageWriter();
        w.uint8(16);                // fl: has vbufid, not stripped
        w.uint16(1);                // 1 triangle
        w.int16(matid);
        w.int16(0);                 // vbufid
        w.uint16(0).uint16(1).uint16(2);
        return w.toByteArray();
    }

    private static byte[] tex(int id) {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
        MessageWriter w = new MessageWriter();
        w.int16(id).uint16(0).uint16(0).uint16(64).uint16(64);
        w.uint8(0).int32(png.length).bytes(png);
        return w.toByteArray();
    }

    private static byte[] mat2(int id, int texIdx) {
        MessageWriter w = new MessageWriter();
        w.uint16(id);
        w.string("tex").uint8(4).uint8(texIdx).uint8(0);
        return w.toByteArray();
    }

    /* ----- glb parsing helpers ----- */

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonOf(byte[] glb) {
        int jlen = le32(glb, 12);
        return (Map<String, Object>) Json.parse(new String(glb, 20, jlen, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstPrimitive(Map<String, Object> root) {
        List<Object> meshes = (List<Object>) root.get("meshes");
        Map<String, Object> m0 = (Map<String, Object>) meshes.get(0);
        return (Map<String, Object>) ((List<Object>) m0.get("primitives")).get(0);
    }

    /* ----------------------------------------------------------------- tests */

    @Test
    void exportsValidGlbContainer() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbuf(false)));
        res.layers.add(new Layer("mesh", mesh(-1)));

        GltfExport.Result r = GltfExport.toGlb(res, "test.res");
        assertEquals(3, r.vertices);
        assertEquals(1, r.triangles);
        assertEquals(1, r.submeshes);

        byte[] glb = r.glb;
        assertEquals("glTF", new String(glb, 0, 4, StandardCharsets.UTF_8));
        assertEquals(2, le32(glb, 4));                       // version
        assertEquals(glb.length, le32(glb, 8));              // total length matches
        assertEquals(0x4E4F534A, le32(glb, 16));             // JSON chunk type
        int jlen = le32(glb, 12);
        assertEquals(0x004E4942, le32(glb, 20 + jlen + 4));  // BIN chunk type
    }

    @Test
    void primitiveHasGeometryAttributesAndIndices() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbuf(false)));
        res.layers.add(new Layer("mesh", mesh(-1)));

        Map<String, Object> root = jsonOf(GltfExport.toGlb(res, "t.res").glb);
        Map<String, Object> prim = firstPrimitive(root);
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) prim.get("attributes");
        assertTrue(attrs.containsKey("POSITION"));
        assertTrue(attrs.containsKey("NORMAL"));
        assertTrue(attrs.containsKey("TEXCOORD_0"));
        assertTrue(prim.containsKey("indices"));
    }

    @Test
    void exportsBothUvSetsWhenOtexPresent() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbuf(true)));      // with otex
        res.layers.add(new Layer("mesh", mesh(-1)));

        Map<String, Object> root = jsonOf(GltfExport.toGlb(res, "t.res").glb);
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) firstPrimitive(root).get("attributes");
        assertTrue(attrs.containsKey("TEXCOORD_0"), "tex -> TEXCOORD_0");
        assertTrue(attrs.containsKey("TEXCOORD_1"), "otex -> TEXCOORD_1 (the thing OBJ can't carry)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportsTexturedGlbWithMaterial() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(0)));
        res.layers.add(new Layer("mat2", mat2(1, 0)));
        res.layers.add(new Layer("vbuf2", vbuf(false)));
        res.layers.add(new Layer("mesh", mesh(1)));

        GltfExport.Result r = GltfExport.toGlb(res, "horse.res");
        assertEquals(1, r.textures);

        Map<String, Object> root = jsonOf(r.glb);
        List<Object> materials = (List<Object>) root.get("materials");
        List<Object> images = (List<Object>) root.get("images");
        assertEquals(1, materials.size());
        assertEquals(1, images.size());
        assertEquals("image/png", ((Map<String, Object>) images.get(0)).get("mimeType"));

        Map<String, Object> pbr = (Map<String, Object>)
                ((Map<String, Object>) materials.get(0)).get("pbrMetallicRoughness");
        assertTrue(pbr.containsKey("baseColorTexture"));
        assertEquals(0L, ((Number) firstPrimitive(root).get("material")).longValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void accessorsAndBufferViewsStayWithinBuffer() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbuf(true)));
        res.layers.add(new Layer("mesh", mesh(-1)));

        byte[] glb = GltfExport.toGlb(res, "t.res").glb;
        Map<String, Object> root = jsonOf(glb);
        long bufLen = ((Number) ((Map<String, Object>)
                ((List<Object>) root.get("buffers")).get(0)).get("byteLength")).longValue();
        List<Object> bvs = (List<Object>) root.get("bufferViews");

        // every bufferView is 4-aligned and within the buffer
        int[] bvLen = new int[bvs.size()];
        for(int i = 0; i < bvs.size(); i++) {
            Map<String, Object> bv = (Map<String, Object>) bvs.get(i);
            int off = ((Number) bv.get("byteOffset")).intValue();
            int len = ((Number) bv.get("byteLength")).intValue();
            bvLen[i] = len;
            assertEquals(0, off & 3, "bufferView " + i + " must be 4-aligned");
            assertTrue(off + len <= bufLen, "bufferView " + i + " within buffer");
        }
        // every accessor's data fits its bufferView
        Map<String, Integer> comps = Map.of("SCALAR", 1, "VEC2", 2, "VEC3", 3, "VEC4", 4);
        for(Object o : (List<Object>) root.get("accessors")) {
            Map<String, Object> ac = (Map<String, Object>) o;
            int bv = ((Number) ac.get("bufferView")).intValue();
            int ct = ((Number) ac.get("componentType")).intValue();
            int count = ((Number) ac.get("count")).intValue();
            int csz = (ct == 5126 || ct == 5125) ? 4 : (ct == 5123 || ct == 5122) ? 2 : 1;
            int need = count * comps.get((String) ac.get("type")) * csz;
            assertTrue(need <= bvLen[bv], "accessor data fits its bufferView");
        }
    }

    @Test
    void noGeometryYieldsEmptyResult() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("image", new byte[]{0, 0, 0}));
        GltfExport.Result r = GltfExport.toGlb(res, "x.res");
        assertEquals(0, r.vertices);
        assertEquals(0, r.triangles);
        assertFalse(r.glb.length == 0);   // still a valid (empty-mesh) container
    }
}
