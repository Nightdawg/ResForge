package resforge;

import resforge.io.MessageWriter;
import resforge.model.ModelGeometry;
import resforge.res.Layer;
import resforge.res.ResContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the 3D-viewer geometry assembler. */
class ModelGeometryTest {

    /** vbuf2 ver=0 with pos2(f4) + nrm2(f4), 3 vertices forming one triangle. */
    private static byte[] vbuf(float[] xyz) {
        MessageWriter w = new MessageWriter();
        w.uint8(0);                 // fl -> ver 0
        w.uint16(3);                // 3 vertices
        w.string("pos2").uint8(1).string("f4");
        for(float v : xyz)
            w.float32(v);
        w.string("nrm2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1})
            w.float32(v);
        return w.toByteArray();
    }

    private static byte[] mesh(int i0, int i1, int i2) {
        MessageWriter w = new MessageWriter();
        w.uint8(16);                // fl: has vbufid, not stripped
        w.uint16(1);                // 1 triangle
        w.int16(0);                 // matid
        w.int16(0);                 // vbufid
        w.uint16(i0).uint16(i1).uint16(i2);
        return w.toByteArray();
    }

    /** A 1-triangle mesh (verts 0,1,2) referencing the given matid + vbuf 0. */
    private static byte[] meshMat(int matid) {
        MessageWriter w = new MessageWriter();
        w.uint8(16);
        w.uint16(1);
        w.int16(matid);
        w.int16(0);
        w.uint16(0).uint16(1).uint16(2);
        return w.toByteArray();
    }

    /** vbuf2 ver=0 with pos2(f4) + nrm2(f4) + tex2(f4), 3 vertices (so texturing applies). */
    private static byte[] vbufTex() {
        MessageWriter w = new MessageWriter();
        w.uint8(0);
        w.uint16(3);
        w.string("pos2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})
            w.float32(v);
        w.string("nrm2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1})
            w.float32(v);
        w.string("tex2").uint8(1).string("f4");
        for(int i = 0; i < 6; i++)
            w.float32(0.5f);
        return w.toByteArray();
    }

    private static byte[] vbufSkinned() {
        MessageWriter w = new MessageWriter();
        w.uint8(0).uint16(3);
        w.string("pos2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})
            w.float32(v);
        w.string("nrm2").uint8(1).string("f4");
        for(int i = 0; i < 3; i++)
            w.float32(0).float32(0).float32(1);
        w.string("bones2").uint8(1).string("f4").uint8(1);
        w.string("root").uint16(3).uint16(0)
                .float32(1).float32(1).float32(1)
                .uint16(0).uint16(0);
        w.string("");
        return w.toByteArray();
    }

    /** A tex layer wrapping a tiny valid PNG signature so TexInfo locates it. */
    private static byte[] tex(int id, byte marker) {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, marker, 2};
        MessageWriter w = new MessageWriter();
        w.int16(id).uint16(0).uint16(0).uint16(64).uint16(64);
        w.uint8(0).int32(png.length).bytes(png);
        return w.toByteArray();
    }

    /** A mat2 whose local `tex` command points at the given tex layer id. */
    private static byte[] mat2Local(int id, int texId) {
        MessageWriter w = new MessageWriter();
        w.uint16(id);
        w.string("tex").uint8(4).uint8(texId).uint8(0);
        return w.toByteArray();
    }

    @Test
    void assemblesTrianglesAndBoundingBox() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbuf(new float[]{0, 0, 0, 2, 0, 0, 0, 2, 0})));
        res.layers.add(new Layer("mesh", mesh(0, 1, 2)));

        ModelGeometry g = ModelGeometry.from(res);
        assertTrue(g != null);
        assertEquals(1, g.triangleCount);
        assertEquals(1, g.submeshCount);
        assertEquals(3, g.vertexCount);
        assertEquals(9, g.positions.length);
        // Bounding box of the triangle (0,0,0)-(2,0,0)-(0,2,0).
        assertEquals(0f, g.min[0]); assertEquals(0f, g.min[1]); assertEquals(0f, g.min[2]);
        assertEquals(2f, g.max[0]); assertEquals(2f, g.max[1]); assertEquals(0f, g.max[2]);
        assertEquals(1f, g.center[0]); assertEquals(1f, g.center[1]); assertEquals(0f, g.center[2]);
        // Normals carried through from the data.
        assertEquals(1f, g.normals[2], 1e-6);
    }

    @Test
    void noGeometryReturnsNull() {
        ResContainer res = new ResContainer(1);
        res.layers.add(new Layer("tooltip", "hi".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNull(ModelGeometry.from(res));
    }

    @Test
    void outOfRangeIndicesAreSkipped() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbuf(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})));
        res.layers.add(new Layer("mesh", mesh(0, 1, 9)));   // index 9 >= 3 vertices
        assertNull(ModelGeometry.from(res), "a triangle referencing a missing vertex is dropped");
    }

    @Test
    void computesAFaceNormalWhenTheBufferHasNoNormals() {
        MessageWriter w = new MessageWriter();
        w.uint8(0);
        w.uint16(3);
        w.string("pos2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})   // CCW in the z=0 plane
            w.float32(v);
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", w.toByteArray()));
        res.layers.add(new Layer("mesh", mesh(0, 1, 2)));

        ModelGeometry g = ModelGeometry.from(res);
        assertTrue(g != null);
        // Face normal of a CCW triangle in the z=0 plane is +Z.
        assertEquals(0f, g.normals[0], 1e-6);
        assertEquals(0f, g.normals[1], 1e-6);
        assertEquals(1f, Math.abs(g.normals[2]), 1e-6);
    }

    @Test
    void exposesPaletteMaterialsAndPerMaterialDefault() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(1, (byte) 0xAA)));   // ordinal 0, id 1
        res.layers.add(new Layer("tex", tex(3, (byte) 0xBB)));   // ordinal 1, id 3 (an alternate)
        res.layers.add(new Layer("mat2", mat2Local(9, 1)));      // matid 9 -> tex id 1 (ordinal 0)
        res.layers.add(new Layer("vbuf2", vbufTex()));
        res.layers.add(new Layer("mesh", meshMat(9)));

        ModelGeometry g = ModelGeometry.from(res);
        assertTrue(g != null);
        assertTrue(g.hasTextures());
        // The palette carries every local tex layer (incl. the unused alternate), with ids.
        assertEquals(2, g.localTextures.size());
        assertEquals(java.util.List.of(1, 3), g.localTexIds);
        // One textured material (matid 9), authored to the first texture (id 1, ordinal 0).
        assertEquals(1, g.materials.size());
        assertEquals(9, g.materials.get(0).matid);
        assertEquals(0, g.materials.get(0).defaultTex);
        assertTrue(g.materials.get(0).localBase, "a local `tex` material is a swappable local base");
        // Every triangle maps to material index 0.
        for(int m : g.triMat)
            assertEquals(0, m);
    }

    /** A mat2 with an external `mlink` base + a local `otex` overlay (knarr pattern). */
    private static byte[] mat2MlinkPlusLocalOtex(int id, String link, int otexId) {
        MessageWriter w = new MessageWriter();
        w.uint16(id);
        w.string("mlink").uint8(2).string(link).uint8(0);
        w.string("otex").uint8(4).uint8(otexId).uint8(0);
        return w.toByteArray();
    }

    @Test
    void variableExternalMaterialIsTexturedButFlaggedNotLocalBase() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(0, (byte) 0xAA)));   // ordinal 0 (the otex overlay)
        res.layers.add(new Layer("tex", tex(1, (byte) 0xBB)));   // a local base for the other material
        res.layers.add(new Layer("mat2", mat2Local(8, 1)));                                   // local base
        res.layers.add(new Layer("mat2", mat2MlinkPlusLocalOtex(9, "gfx/x/peartree-tex", 0)));// variable base
        res.layers.add(new Layer("vbuf2", vbufTex()));
        res.layers.add(new Layer("mesh", meshMat(8)));
        res.layers.add(new Layer("mesh", meshMat(9)));

        ModelGeometry g = ModelGeometry.from(res);
        assertTrue(g != null);
        assertEquals(2, g.materials.size(), "both materials resolve to a local texture, so both render");
        ModelGeometry.Material m8 = g.materials.stream().filter(m -> m.matid == 8).findFirst().orElseThrow();
        ModelGeometry.Material m9 = g.materials.stream().filter(m -> m.matid == 9).findFirst().orElseThrow();
        assertTrue(m8.localBase, "matid 8 has a local `tex` base");
        assertFalse(m9.localBase, "matid 9's base is an external mlink (otex overlay only)");
    }

    @Test
    void untexturedModelHasEmptyMaterialsButStillExposesThePalette() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(5, (byte) 0xCC)));
        // geometry without tex coords + no matching mat2 -> no textured material
        res.layers.add(new Layer("vbuf2", vbuf(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})));
        res.layers.add(new Layer("mesh", mesh(0, 1, 2)));

        ModelGeometry g = ModelGeometry.from(res);
        assertTrue(g != null);
        assertFalse(g.hasTextures());
        assertTrue(g.materials.isEmpty());
        assertEquals(1, g.localTextures.size());   // palette still lists the resource's tex layer
        for(int m : g.triMat)
            assertEquals(-1, m);
    }

    @Test
    void staticViewOfSkinnedModelDoesNotRetainOrCopyInfluences() {
        ResContainer res = new ResContainer(1);
        res.layers.add(new Layer("vbuf2", vbufSkinned()));
        res.layers.add(new Layer("mesh", mesh(0, 1, 2)));

        ModelGeometry geometry = ModelGeometry.from(res);

        assertTrue(geometry != null);
        assertTrue(geometry.boneNames.isEmpty());
        assertEquals(0, geometry.joints.length);
        assertEquals(0, geometry.weights.length);
    }
}
