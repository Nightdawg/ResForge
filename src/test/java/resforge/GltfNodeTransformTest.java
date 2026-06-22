package resforge;

import resforge.io.MessageWriter;
import resforge.model.GltfImport;
import resforge.model.Vbuf2Data;
import resforge.res.Layer;
import resforge.res.ResContainer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A node-level transform left un-applied in Blender must be baked into rebuilt geometry. */
class GltfNodeTransformTest {

    private static byte[] vbufF4(int n) {
        MessageWriter w = new MessageWriter();
        w.uint8(0).uint16(n);
        w.string("pos2").uint8(1).string("f4");
        for(int i = 0; i < n; i++) w.float32(i).float32(i).float32(i);
        w.string("nrm2").uint8(1).string("f4");
        for(int i = 0; i < n; i++) w.float32(0).float32(0).float32(1);
        w.string("tex2").uint8(1).string("f4");
        for(int i = 0; i < n; i++) w.float32(0).float32(0);
        return w.toByteArray();
    }

    private static byte[] mesh(int matid) {
        MessageWriter w = new MessageWriter();
        w.uint8(16).uint16(1).int16(matid).int16(0);
        w.uint16(0).uint16(1).uint16(2);
        return w.toByteArray();
    }

    private static byte[] origRes() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", new byte[]{1, 2, 3}));
        res.layers.add(new Layer("vbuf2", vbufF4(3)));
        res.layers.add(new Layer("mesh", mesh(5)));
        return res.serialize();
    }

    private static byte[] pad(byte[] b, byte fill) {
        int pad = (4 - (b.length & 3)) & 3;
        byte[] out = new byte[b.length + pad];
        System.arraycopy(b, 0, out, 0, b.length);
        for(int i = b.length; i < out.length; i++) out[i] = fill;
        return out;
    }

    /** One triangle, with an optional node JSON (e.g. a translation) referencing the mesh. */
    private static byte[] glb(String extraNodes) {
        float[] pos = {0, 0, 0,  1, 0, 0,  0, 1, 0};
        float[] nrm = {0, 0, 1,  0, 0, 1,  0, 0, 1};
        float[] tex = {0, 0,  1, 0,  0, 1};
        int[] idx = {0, 1, 2};
        int m = 3;
        int posLen = m * 12, nrmLen = m * 12, texLen = m * 8, idxLen = idx.length * 2;
        MessageWriter bin = new MessageWriter();
        for(float v : pos) bin.float32(v);
        for(float v : nrm) bin.float32(v);
        for(float v : tex) bin.float32(v);
        for(int v : idx) bin.uint16(v);
        if((idxLen & 3) != 0) bin.uint16(0);
        int po = 0, no = posLen, to = posLen + nrmLen, io = posLen + nrmLen + texLen;
        int total = io + ((idxLen + 3) & ~3);
        String json = "{\"asset\":{\"version\":\"2.0\"},"
                + "\"buffers\":[{\"byteLength\":" + total + "}],"
                + "\"bufferViews\":["
                + "{\"buffer\":0,\"byteOffset\":" + po + ",\"byteLength\":" + posLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + no + ",\"byteLength\":" + nrmLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + to + ",\"byteLength\":" + texLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + io + ",\"byteLength\":" + idxLen + "}],"
                + "\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC3\"},"
                + "{\"bufferView\":2,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC2\"},"
                + "{\"bufferView\":3,\"componentType\":5123,\"count\":" + idx.length + ",\"type\":\"SCALAR\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},"
                + "\"indices\":3}]}]"
                + (extraNodes == null ? "" : "," + extraNodes)
                + "}";
        byte[] jpad = pad(json.getBytes(StandardCharsets.UTF_8), (byte) 0x20);
        byte[] bpad = pad(bin.toByteArray(), (byte) 0x00);
        MessageWriter w = new MessageWriter();
        w.int32(0x46546C67).int32(2).int32(12 + 8 + jpad.length + 8 + bpad.length);
        w.int32(jpad.length).int32(0x4E4F534A).bytes(jpad);
        w.int32(bpad.length).int32(0x004E4942).bytes(bpad);
        return w.toByteArray();
    }

    private static float[] rebuiltPositions(byte[] glb) {
        ResContainer out = ResContainer.parse(GltfImport.rebuild(origRes(), glb).res);
        byte[] vbuf = out.layers.stream().filter(l -> l.name.equals("vbuf2")).findFirst().get().data;
        return Vbuf2Data.parse(vbuf).get("pos");
    }

    @Test
    void identityNodeLeavesGeometryUnchanged() {
        // No nodes at all vs an explicit identity node: both yield the raw glTF coords.
        float[] noNode = rebuiltPositions(glb(null));
        float[] idNode = rebuiltPositions(glb("\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],\"scene\":0"));
        assertEquals(0f, noNode[0], 1e-4f);   // vertex0 x
        assertEquals(1f, noNode[3], 1e-4f);   // vertex1 x
        for(int i = 0; i < noNode.length; i++)
            assertEquals(noNode[i], idNode[i], 1e-4f, "identity node must not move vertices");
    }

    @Test
    void unappliedNodeTranslationIsBaked() {
        // A node translation of +10 along glTF X. axisInvert maps glTF X -> Haven X,
        // so every vertex's Haven X must shift by +10.
        float[] moved = rebuiltPositions(glb(
                "\"nodes\":[{\"mesh\":0,\"translation\":[10,0,0]}],\"scenes\":[{\"nodes\":[0]}],\"scene\":0"));
        assertEquals(10f, moved[0], 1e-4f);   // vertex0 x: 0 + 10
        assertEquals(11f, moved[3], 1e-4f);   // vertex1 x: 1 + 10
        assertEquals(10f, moved[6], 1e-4f);   // vertex2 x: 0 + 10
    }

    @Test
    void truncatedGlbIsRejectedCleanly() {
        byte[] bad = java.util.Arrays.copyOf(glb(null), 18);   // shorter than the 20-byte header
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> GltfImport.rebuild(origRes(), bad));
    }
}
