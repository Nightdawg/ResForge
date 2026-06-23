package resforge;

import resforge.io.MessageWriter;
import resforge.model.ModelGeometry;
import resforge.res.Layer;
import resforge.res.ResContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
