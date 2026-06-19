package hafen.resedit;

import hafen.resedit.io.MessageWriter;
import hafen.resedit.model.ObjExport;
import hafen.resedit.res.Layer;
import hafen.resedit.res.ResContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjExportTest {
    /** vbuf2 ver=0 with a single pos2(f4) attribute, 3 vertices forming one triangle. */
    private static byte[] vbuf(float[] xyz) {
        MessageWriter w = new MessageWriter();
        w.uint8(0);                 // fl -> ver 0
        w.uint16(3);                // 3 vertices
        w.string("pos2").uint8(1).string("f4");
        for(float v : xyz)
            w.float32(v);
        return w.toByteArray();
    }

    /** mesh old-form, raw indices, with vbufid: fl=16, 1 triangle (0,1,2). */
    private static byte[] mesh() {
        MessageWriter w = new MessageWriter();
        w.uint8(16);                // fl: has vbufid, not stripped
        w.uint16(1);                // 1 triangle
        w.int16(-1);                // matid
        w.int16(0);                 // vbufid
        w.uint16(0).uint16(1).uint16(2);
        return w.toByteArray();
    }

    @Test
    void exportsGeometryToObj() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbuf(new float[]{
                0, 0, 0,
                1, 0, 0,
                0, 1, 0})));
        res.layers.add(new Layer("mesh", mesh()));

        ObjExport.Result r = ObjExport.toObj(res, "test.res");
        assertEquals(3, r.vertices);
        assertEquals(1, r.triangles);
        assertEquals(1, r.submeshes);

        long vlines = r.obj.lines().filter(l -> l.startsWith("v ")).count();
        long flines = r.obj.lines().filter(l -> l.startsWith("f ")).count();
        assertEquals(3, vlines);
        assertEquals(1, flines);
        // Face indices are 1-based.
        assertTrue(r.obj.contains("f 1 2 3"));
    }
}
