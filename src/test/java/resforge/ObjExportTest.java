package resforge;

import resforge.io.MessageWriter;
import resforge.model.ObjExport;
import resforge.res.Layer;
import resforge.res.ResContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjExportTest {
    /** vbuf2 ver=0 with pos2(f4) + tex2(f4) attributes, 3 vertices forming one triangle. */
    private static byte[] vbuf(float[] xyz) {
        MessageWriter w = new MessageWriter();
        w.uint8(0);                 // fl -> ver 0
        w.uint16(3);                // 3 vertices
        w.string("pos2").uint8(1).string("f4");
        for(float v : xyz)
            w.float32(v);
        w.string("tex2").uint8(1).string("f4");
        for(int i = 0; i < 6; i++)  // 3 verts * 2 uv
            w.float32(0.5f);
        return w.toByteArray();
    }

    /** mesh old-form, raw indices, with vbufid: fl=16, 1 triangle (0,1,2), matid. */
    private static byte[] mesh(int matid) {
        MessageWriter w = new MessageWriter();
        w.uint8(16);                // fl: has vbufid, not stripped
        w.uint16(1);                // 1 triangle
        w.int16(matid);             // matid
        w.int16(0);                 // vbufid
        w.uint16(0).uint16(1).uint16(2);
        return w.toByteArray();
    }

    /** A tex layer: id, off, sz, then a t==0 color-image part with a PNG. */
    private static byte[] tex(int id) {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
        MessageWriter w = new MessageWriter();
        w.int16(id).uint16(0).uint16(0).uint16(64).uint16(64);
        w.uint8(0).int32(png.length).bytes(png);
        return w.toByteArray();
    }

    /** A mat2 layer: id, with a local tex command referencing tex ordinal `texIdx`. */
    private static byte[] mat2(int id, int texIdx) {
        MessageWriter w = new MessageWriter();
        w.uint16(id);
        w.string("tex").uint8(4).uint8(texIdx).uint8(0);   // tex -> [u8 texIdx]
        return w.toByteArray();
    }

    @Test
    void exportsGeometryToObj() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbuf(new float[]{
                0, 0, 0,
                1, 0, 0,
                0, 1, 0})));
        res.layers.add(new Layer("mesh", mesh(-1)));

        ObjExport.Result r = ObjExport.toObj(res, "test.res");
        assertEquals(3, r.vertices);
        assertEquals(1, r.triangles);
        assertEquals(1, r.submeshes);

        long vlines = r.obj.lines().filter(l -> l.startsWith("v ")).count();
        long flines = r.obj.lines().filter(l -> l.startsWith("f ")).count();
        assertEquals(3, vlines);
        assertEquals(1, flines);
        // No texture -> geometry-only, no material library.
        assertNull(r.mtl);
        assertTrue(r.textures.isEmpty());
    }

    @Test
    void exportsTexturedObjWithMtlAndImage() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(0)));
        res.layers.add(new Layer("mat2", mat2(1, 0)));
        res.layers.add(new Layer("vbuf2", vbuf(new float[]{
                0, 0, 0,
                1, 0, 0,
                0, 1, 0})));
        res.layers.add(new Layer("mesh", mesh(1)));

        ObjExport.Result r = ObjExport.toObj(res, "horse.res");
        assertEquals(3, r.vertices);
        assertEquals(1, r.triangles);

        // A material library + one texture image are produced.
        assertNotNull(r.mtl);
        assertEquals("horse", r.baseName);
        assertEquals(1, r.textures.size());
        assertEquals("horse_tex0.png", r.textures.get(0).name);

        // The OBJ references the mtl and uses the material on the submesh.
        assertTrue(r.obj.contains("mtllib horse.mtl"));
        assertTrue(r.obj.contains("usemtl tex0"));
        // UVs are present (vt lines) so the texture maps.
        assertTrue(r.obj.lines().anyMatch(l -> l.startsWith("vt ")));
        // The mtl points at the exported image.
        assertTrue(r.mtl.contains("newmtl tex0"));
        assertTrue(r.mtl.contains("map_Kd horse_tex0.png"));
    }
}

