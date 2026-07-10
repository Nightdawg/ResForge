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

    private static byte[] mat2External(int id, String path) {
        MessageWriter w = new MessageWriter();
        w.uint16(id);
        w.string("tex").uint8(2).string(path).uint8(0);
        return w.toByteArray();
    }

    /** vbuf2 ver0 with pos2 + a bones2 attribute: one bone "root", full weight on all 3 verts. */
    private static byte[] vbufBones() {
        MessageWriter w = new MessageWriter();
        w.uint8(0);
        w.uint16(3);
        w.string("pos2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})
            w.float32(v);
        w.string("bones2").uint8(1).string("f4").uint8(1);   // data-ver 1, fmt f4, mba 1
        w.string("root").uint16(3).uint16(0)                 // span: run 3 from vert 0
                .float32(1f).float32(1f).float32(1f)
                .uint16(0).uint16(0);                        // end spans
        w.string("");                                        // end bones
        return w.toByteArray();
    }

    /** skel (ver-1 sub-format) with a single root bone "root". */
    private static byte[] skel() {
        MessageWriter w = new MessageWriter();
        w.string("\u0001");                                  // switch to ver-1 bone encoding
        w.string("root").string("");
        w.float32(0).float32(0).float32(0);
        w.uint16(0).int16(0).int16(0);
        return w.toByteArray();
    }

    /** skan (fmt 1, loop) animating bone "root" with a single keyframe. */
    private static byte[] skan(int id) {
        MessageWriter w = new MessageWriter();
        w.int16(id);
        w.uint8(2);                  // fl: fmt = (2&6)>>1 = 1, no nspeed
        w.uint8(1);                  // mode = loop
        w.float32(1.0f);             // length
        w.string("root").uint16(1);  // one track "root", one frame
        w.uint16(0).int16(0).int16(0).int16(0).uint16(0).int16(0).int16(0);  // fmt-1 frame
        return w.toByteArray();
    }

    /** manim (fmt 3 float16) with 2 frames each morphing vertex 0, over the 3-vert vbuf. */
    private static byte[] manim(int id, float len) {
        MessageWriter w = new MessageWriter();
        w.uint8(1).int16(id).uint8(0).float32(len);   // ver, id, rnd=0, len
        // frame 0 @ t=0: vertex 0 delta (0.1, 0.2, 0.3)
        w.uint8(3).float32(0f).uint16(1).uint16(0).uint16(1).float16(0.1f).float16(0.2f).float16(0.3f);
        // frame 1 @ t=len/2: vertex 0 delta (-0.1, 0, 0)
        w.uint8(3).float32(len / 2).uint16(1).uint16(0).uint16(1).float16(-0.1f).float16(0f).float16(0f);
        w.uint8(0);                                   // end of frames
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
    void materialResolvesTextureByIdNotByPosition() {
        // tex ids that differ from their positions (as in the mulberry tree).
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(10)));          // ordinal 0, id 10
        res.layers.add(new Layer("tex", tex(20)));          // ordinal 1, id 20
        res.layers.add(new Layer("mat2", mat2(7, 20)));     // matid 7 -> tex id 20, at ordinal 1
        res.layers.add(new Layer("vbuf2", vbuf(false)));
        res.layers.add(new Layer("mesh", mesh(7)));

        GltfExport.Result r = GltfExport.toGlb(res, "t.res");
        assertEquals(2, r.textures);

        Map<String, Object> root = jsonOf(r.glb);
        List<Object> materials = (List<Object>) root.get("materials");
        Map<String, Object> pbr = (Map<String, Object>)
                ((Map<String, Object>) materials.get(0)).get("pbrMetallicRoughness");
        Map<String, Object> bct = (Map<String, Object>) pbr.get("baseColorTexture");
        assertEquals(1L, ((Number) bct.get("index")).longValue(),
                "tex id 20 is the 2nd embedded texture (ordinal 1), resolved by id not position");
    }

    @Test
    @SuppressWarnings("unchecked")
    void singleTextureIsNotAssignedToUnmappedMaterials() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(0)));
        res.layers.add(new Layer("mat2", mat2(7, 0)));
        res.layers.add(new Layer("vbuf2", vbuf(false)));
        res.layers.add(new Layer("mesh", mesh(7)));
        res.layers.add(new Layer("mesh", mesh(-1)));

        Map<String, Object> root = jsonOf(GltfExport.toGlb(res, "mixed.res").glb);
        Map<String, Map<String, Object>> materials = materialsByName(root);

        assertTrue(pbr(materials.get("rfmat_7")).containsKey("baseColorTexture"));
        assertFalse(pbr(materials.get("rfmat_-1")).containsKey("baseColorTexture"),
                "matid -1 means no local material and must not inherit texture zero");
    }

    @Test
    @SuppressWarnings("unchecked")
    void multipleTexturesAreNotAssignedToUnknownMaterialIds() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(10)));
        res.layers.add(new Layer("tex", tex(20)));
        res.layers.add(new Layer("mat2", mat2(7, 20)));
        res.layers.add(new Layer("vbuf2", vbuf(false)));
        res.layers.add(new Layer("mesh", mesh(7)));
        res.layers.add(new Layer("mesh", mesh(99)));

        Map<String, Object> root = jsonOf(GltfExport.toGlb(res, "mixed.res").glb);
        Map<String, Map<String, Object>> materials = materialsByName(root);

        Map<String, Object> mappedTexture =
                (Map<String, Object>) pbr(materials.get("rfmat_7")).get("baseColorTexture");
        assertEquals(1L, ((Number) mappedTexture.get("index")).longValue());
        assertFalse(pbr(materials.get("rfmat_99")).containsKey("baseColorTexture"));
    }

    @Test
    void externalOnlyMaterialDoesNotReceiveLocalTexture() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(0)));
        res.layers.add(new Layer("mat2",
                mat2External(7, "gfx/terobjs/trees/mulberry-tex")));
        res.layers.add(new Layer("vbuf2", vbuf(false)));
        res.layers.add(new Layer("mesh", mesh(7)));

        Map<String, Object> root = jsonOf(GltfExport.toGlb(res, "external.res").glb);
        Map<String, Map<String, Object>> materials = materialsByName(root);

        assertFalse(pbr(materials.get("rfmat_7")).containsKey("baseColorTexture"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> materialsByName(Map<String, Object> root) {
        Map<String, Map<String, Object>> byName = new java.util.LinkedHashMap<>();
        for(Object material : (List<Object>) root.get("materials")) {
            Map<String, Object> map = (Map<String, Object>) material;
            byName.put((String) map.get("name"), map);
        }
        return byName;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pbr(Map<String, Object> material) {
        return (Map<String, Object>) material.get("pbrMetallicRoughness");
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
    @SuppressWarnings("unchecked")
    void skinnedModelHasSkinJointsAndWeights() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("skel", skel()));
        res.layers.add(new Layer("vbuf2", vbufBones()));
        res.layers.add(new Layer("mesh", mesh(-1)));

        Map<String, Object> root = jsonOf(GltfExport.toGlb(res, "rig.res").glb);
        // a skin with one joint, and an inverseBindMatrices MAT4 accessor
        List<Object> skins = (List<Object>) root.get("skins");
        assertEquals(1, skins.size());
        Map<String, Object> skin = (Map<String, Object>) skins.get(0);
        assertEquals(1, ((List<Object>) skin.get("joints")).size());
        int ibm = ((Number) skin.get("inverseBindMatrices")).intValue();
        Map<String, Object> ibmAcc = (Map<String, Object>) ((List<Object>) root.get("accessors")).get(ibm);
        assertEquals("MAT4", ibmAcc.get("type"));
        assertEquals(1L, ((Number) ibmAcc.get("count")).longValue());

        // the primitive carries skinning attributes; the mesh node references the skin
        Map<String, Object> attrs = (Map<String, Object>) firstPrimitive(root).get("attributes");
        assertTrue(attrs.containsKey("JOINTS_0"));
        assertTrue(attrs.containsKey("WEIGHTS_0"));
        Map<String, Object> meshNode = (Map<String, Object>) ((List<Object>) root.get("nodes")).get(0);
        assertEquals(0L, ((Number) meshNode.get("skin")).longValue());

        // a local skel poses the joint via a connected hierarchy under a ROOT node
        int jointNode = ((Number) ((List<Object>) skin.get("joints")).get(0)).intValue();
        Map<String, Object> jn = (Map<String, Object>) ((List<Object>) root.get("nodes")).get(jointNode);
        assertTrue(jn.containsKey("translation") || jn.containsKey("rotation"),
                "a local skel should give the joint a local transform");
        boolean hasRoot = false;
        for(Object o : (List<Object>) root.get("nodes"))
            if("ROOT".equals(((Map<String, Object>) o).get("name")))
                hasRoot = true;
        assertTrue(hasRoot, "a conversion ROOT node should parent the skeleton");
    }

    @Test
    @SuppressWarnings("unchecked")
    void skinWithoutLocalSkelUsesIdentityJoints() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufBones()));     // bones, but no skel layer
        res.layers.add(new Layer("mesh", mesh(-1)));

        Map<String, Object> root = jsonOf(GltfExport.toGlb(res, "rig.res").glb);
        assertTrue(root.containsKey("skins"));               // weights still export
        Map<String, Object> attrs = (Map<String, Object>) firstPrimitive(root).get("attributes");
        assertTrue(attrs.containsKey("JOINTS_0"));
        assertTrue(attrs.containsKey("WEIGHTS_0"));
        // with no local skel the joint stays identity-placed (no matrix)
        Map<String, Object> skin = (Map<String, Object>) ((List<Object>) root.get("skins")).get(0);
        int jointNode = ((Number) ((List<Object>) skin.get("joints")).get(0)).intValue();
        Map<String, Object> jn = (Map<String, Object>) ((List<Object>) root.get("nodes")).get(jointNode);
        assertFalse(jn.containsKey("matrix"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void skanLayerBecomesGltfAnimation() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("skel", skel()));
        res.layers.add(new Layer("vbuf2", vbufBones()));
        res.layers.add(new Layer("mesh", mesh(-1)));
        res.layers.add(new Layer("skan", skan(5)));

        Map<String, Object> root = jsonOf(GltfExport.toGlb(res, "rig.res").glb);
        List<Object> animations = (List<Object>) root.get("animations");
        assertEquals(1, animations.size());

        Map<String, Object> an = (Map<String, Object>) animations.get(0);
        List<Object> channels = (List<Object>) an.get("channels");
        List<Object> samplers = (List<Object>) an.get("samplers");
        // one bone track -> a translation and a rotation channel/sampler
        assertEquals(2, channels.size());
        assertEquals(2, samplers.size());

        int rootJoint = ((Number) ((List<Object>) ((Map<String, Object>)
                ((List<Object>) root.get("skins")).get(0)).get("joints")).get(0)).intValue();
        java.util.Set<String> paths = new java.util.HashSet<>();
        for(Object c : channels) {
            Map<String, Object> target = (Map<String, Object>) ((Map<String, Object>) c).get("target");
            assertEquals(rootJoint, ((Number) target.get("node")).intValue());
            paths.add((String) target.get("path"));
        }
        assertTrue(paths.contains("translation"));
        assertTrue(paths.contains("rotation"));

        // the sampler input is a SCALAR accessor with the required min/max
        int input = ((Number) ((Map<String, Object>) samplers.get(0)).get("input")).intValue();
        Map<String, Object> in = (Map<String, Object>) ((List<Object>) root.get("accessors")).get(input);
        assertEquals("SCALAR", in.get("type"));
        assertTrue(in.containsKey("min") && in.containsKey("max"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void manimLayerBecomesMorphTargetsAndWeightAnimation() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbuf(false)));     // 3 vertices, pos + tex
        res.layers.add(new Layer("mesh", mesh(-1)));
        res.layers.add(new Layer("manim", manim(0, 1.0f)));  // 2 frames

        Map<String, Object> root = jsonOf(GltfExport.toGlb(res, "morph.res").glb);
        Map<String, Object> mesh0 = (Map<String, Object>) ((List<Object>) root.get("meshes")).get(0);

        // mesh has weights, and the primitive has one morph target per frame
        List<Object> weights = (List<Object>) mesh0.get("weights");
        assertEquals(2, weights.size());
        Map<String, Object> prim = firstPrimitive(root);
        List<Object> targets = (List<Object>) prim.get("targets");
        assertEquals(2, targets.size());
        assertTrue(((Map<String, Object>) targets.get(0)).containsKey("POSITION"));

        // a morph animation drives the mesh node's weights
        List<Object> animations = (List<Object>) root.get("animations");
        assertEquals(1, animations.size());
        Map<String, Object> an = (Map<String, Object>) animations.get(0);
        Map<String, Object> ch = (Map<String, Object>) ((List<Object>) an.get("channels")).get(0);
        Map<String, Object> target = (Map<String, Object>) ch.get("target");
        assertEquals("weights", target.get("path"));
        assertEquals(0L, ((Number) target.get("node")).longValue());

        // weight output count == keyframes (frames + loop close) * morph target count
        Map<String, Object> sampler = (Map<String, Object>) ((List<Object>) an.get("samplers")).get(0);
        List<Object> accessors = (List<Object>) root.get("accessors");
        int inCount = ((Number) ((Map<String, Object>) accessors.get(
                ((Number) sampler.get("input")).intValue())).get("count")).intValue();
        int outCount = ((Number) ((Map<String, Object>) accessors.get(
                ((Number) sampler.get("output")).intValue())).get("count")).intValue();
        assertEquals(3, inCount);                 // 2 frames + 1 loop-close keyframe
        assertEquals(inCount * 2, outCount);      // * 2 morph targets
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
