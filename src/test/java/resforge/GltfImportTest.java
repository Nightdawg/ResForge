package resforge;

import resforge.io.Json;
import resforge.io.MessageWriter;
import resforge.layers.MeshAnimInfo;
import resforge.layers.MeshInfo;
import resforge.layers.SkelInfo;
import resforge.model.GltfExport;
import resforge.model.GltfImport;
import resforge.model.Vbuf2Codec;
import resforge.model.Vbuf2Data;
import resforge.res.Layer;
import resforge.res.ResContainer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfImportTest {

    /** vbuf2 ver0 with f4 pos/nrm/tex over {@code n} vertices (first 3 form a tri). */
    private static byte[] vbufF4(int n) {
        MessageWriter w = new MessageWriter();
        w.uint8(0);
        w.uint16(n);
        w.string("pos2").uint8(1).string("f4");
        for(int i = 0; i < n; i++) {
            w.float32(i).float32(i * 0.5f).float32(-i);
        }
        w.string("nrm2").uint8(1).string("f4");
        for(int i = 0; i < n; i++)
            w.float32(0).float32(0).float32(1);
        w.string("tex2").uint8(1).string("f4");
        for(int i = 0; i < n; i++)
            w.float32(0.25f).float32(0.75f);
        return w.toByteArray();
    }

    /**
     * vbuf2 ver0, 3 vertices, using the quantised on-wire formats real models use:
     * pos=sn2, tex=un2, nrm=uvec1 — each chosen at full octahedral/normalised scale
     * so a decode→re-encode is byte-exact.
     */
    private static byte[] vbufQuant() {
        MessageWriter w = new MessageWriter();
        w.uint8(0);
        w.uint16(3);
        // pos2 sn2: max=1.0, ints reach ±32767 so re-quant returns identical ints
        w.string("pos2").uint8(1).string("sn2");
        w.float32(1.0f);
        short[] pos = {32767, 0, 0,  0, 32767, 0,  0, 0, -32767};
        for(short s : pos) w.int16(s);
        // tex2 un2: max=1.0, full-scale uints
        w.string("tex2").uint8(1).string("un2");
        w.float32(1.0f);
        int[] tex = {65535, 0,  0, 65535,  0, 0};
        for(int u : tex) w.uint16(u);
        // nrm2 uvec1: octahedral int8 pairs for three unit normals
        w.string("nrm2").uint8(1).string("uvec1");
        byte[][] octs = {oct(0, 0, 1), oct(1, 0, 0), oct(0, 1, 0)};
        for(byte[] o : octs) w.int8(o[0]).int8(o[1]);
        return w.toByteArray();
    }

    /** Octahedral-encode a unit vector to two int8s (mirrors the codec's quantiser). */
    private static byte[] oct(float x, float y, float z) {
        float m = 1.0f / (Math.abs(x) + Math.abs(y) + Math.abs(z));
        float hx = x * m, hy = y * m, ox, oy;
        if(z >= 0) {
            ox = hx; oy = hy;
        } else {
            ox = (1 - Math.abs(hy)) * Math.copySign(1, hx);
            oy = (1 - Math.abs(hx)) * Math.copySign(1, hy);
        }
        return new byte[]{(byte) Math.round(ox * 127), (byte) Math.round(oy * 127)};
    }

    private static byte[] vbufBones() {
        MessageWriter w = new MessageWriter();
        w.uint8(0);
        w.uint16(3);
        w.string("pos2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})
            w.float32(v);
        w.string("bones2").uint8(1).string("f4").uint8(1);
        w.string("root").uint16(3).uint16(0)
                .float32(1f).float32(1f).float32(1f)
                .uint16(0).uint16(0);
        w.string("");
        return w.toByteArray();
    }

    private static byte[] skel() {
        MessageWriter w = new MessageWriter();
        w.string("\u0001");
        w.string("root").string("");
        w.float32(0).float32(0).float32(0);
        w.uint16(0).int16(0).int16(0);
        return w.toByteArray();
    }

    /** skel with two bones: "root" and its child "tip". */
    private static byte[] skel2() {
        MessageWriter w = new MessageWriter();
        w.string("\u0001");
        w.string("root").string("");
        w.float32(0).float32(0).float32(0);
        w.uint16(0).int16(0).int16(0);
        w.string("tip").string("root");
        w.float32(0).float32(0).float32(1);
        w.uint16(0).int16(0).int16(0);
        return w.toByteArray();
    }

    /**
     * vbuf2 with two bones in {@code wfmt} weight format: vert0 root .7/tip .3,
     * vert1 root .4/tip .6, vert2 root 1 (so each vertex has a distinct dominant).
     */
    private static byte[] vbufBones2(String wfmt) {
        MessageWriter w = new MessageWriter();
        w.uint8(0).uint16(3);
        w.string("pos2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})
            w.float32(v);
        w.string("bones2").uint8(1).string(wfmt).uint8(2);
        w.string("root").uint16(3).uint16(0);
        wt(w, wfmt, 0.7f); wt(w, wfmt, 0.4f); wt(w, wfmt, 1.0f);
        w.uint16(0).uint16(0);
        w.string("tip").uint16(2).uint16(0);
        wt(w, wfmt, 0.3f); wt(w, wfmt, 0.6f);
        w.uint16(0).uint16(0);
        w.string("");
        return w.toByteArray();
    }

    private static void wt(MessageWriter w, String fmt, float v) {
        switch(fmt) {
            case "f4": w.float32(v); break;
            case "un2": w.uint16(Math.round(v * 65535)); break;
            case "un1": w.uint8(Math.round(v * 255)); break;
            default: throw new IllegalArgumentException(fmt);
        }
    }

    /** Rewrites every WEIGHTS_0 vec4 in the glb to fully bind the first joint ([1,0,0,0]). */
    @SuppressWarnings("unchecked")
    private static byte[] bindFirstJoint(byte[] glb) {
        int jlen = le32(glb, 12);
        Map<String, Object> root =
                (Map<String, Object>) Json.parse(new String(glb, 20, jlen, StandardCharsets.UTF_8));
        Map<String, Object> mesh0 = (Map<String, Object>) ((List<Object>) root.get("meshes")).get(0);
        Map<String, Object> prim = (Map<String, Object>) ((List<Object>) mesh0.get("primitives")).get(0);
        int accIdx = ((Number) ((Map<String, Object>) prim.get("attributes")).get("WEIGHTS_0")).intValue();
        Map<String, Object> acc = (Map<String, Object>) ((List<Object>) root.get("accessors")).get(accIdx);
        int count = ((Number) acc.get("count")).intValue();
        int bvIdx = ((Number) acc.get("bufferView")).intValue();
        Map<String, Object> bv = (Map<String, Object>) ((List<Object>) root.get("bufferViews")).get(bvIdx);
        int bvOff = bv.get("byteOffset") == null ? 0 : ((Number) bv.get("byteOffset")).intValue();
        int accOff = acc.get("byteOffset") == null ? 0 : ((Number) acc.get("byteOffset")).intValue();
        int base = 20 + jlen + 8 + bvOff + accOff;
        byte[] out = glb.clone();
        for(int v = 0; v < count; v++)
            for(int c = 0; c < 4; c++) {
                int p = base + (v * 4 + c) * 4;
                int b = Float.floatToIntBits(c == 0 ? 1f : 0f);
                out[p] = (byte) b;
                out[p + 1] = (byte) (b >>> 8);
                out[p + 2] = (byte) (b >>> 16);
                out[p + 3] = (byte) (b >>> 24);
            }
        return out;
    }

    private static byte[] mesh(int matid) {
        MessageWriter w = new MessageWriter();
        w.uint8(16);
        w.uint16(1);
        w.int16(matid);
        w.int16(0);
        w.uint16(0).uint16(1).uint16(2);
        return w.toByteArray();
    }

    /** manim (fmt 3 float16), 2 frames each morphing vertex 0 over the vbuf. */
    private static byte[] manim(int id, float len) {
        MessageWriter w = new MessageWriter();
        w.uint8(1).int16(id).uint8(0).float32(len);
        w.uint8(3).float32(0f).uint16(1).uint16(0).uint16(1).float16(0.1f).float16(0.2f).float16(0.3f);
        w.uint8(3).float32(len / 2).uint16(1).uint16(0).uint16(1).float16(-0.1f).float16(0f).float16(0f);
        w.uint8(0);
        return w.toByteArray();
    }

    /** manim with a single fmt-3 frame morphing vertex 1 by (0.1,0.2,0.3). */
    private static byte[] manim1(int id, float len) {
        MessageWriter w = new MessageWriter();
        w.uint8(1).int16(id).uint8(0).float32(len);
        w.uint8(3).float32(0f).uint16(1).uint16(1).uint16(1).float16(0.1f).float16(0.2f).float16(0.3f);
        w.uint8(0);
        return w.toByteArray();
    }

    /**
     * Builds a glb with dense POSITION+_VID and one morph target stored as a SPARSE
     * accessor (what Blender's exporter emits for shape keys).
     */
    private static byte[] sparseMorphGlb(float[] gpos, float[] gvid, int sidx, float[] sval) {
        int m = gvid.length;
        int posLen = m * 12, vidLen = m * 4, idxLen = 4 /*ushort + pad*/, valLen = 12;
        MessageWriter bin = new MessageWriter();
        for(float v : gpos) bin.float32(v);
        for(float v : gvid) bin.float32(v);
        bin.uint16(sidx).uint16(0);                      // sparse index (+2 pad bytes)
        for(float v : sval) bin.float32(v);
        int total = posLen + vidLen + idxLen + valLen;
        String json = "{\"asset\":{\"version\":\"2.0\"},"
                + "\"buffers\":[{\"byteLength\":" + total + "}],"
                + "\"bufferViews\":["
                + "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":" + posLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + posLen + ",\"byteLength\":" + vidLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + (posLen + vidLen) + ",\"byteLength\":2},"
                + "{\"buffer\":0,\"byteOffset\":" + (posLen + vidLen + idxLen) + ",\"byteLength\":" + valLen + "}],"
                + "\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":" + m + ",\"type\":\"SCALAR\"},"
                + "{\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC3\",\"sparse\":{\"count\":1,"
                + "\"indices\":{\"bufferView\":2,\"componentType\":5123},\"values\":{\"bufferView\":3}}}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"_VID\":1},"
                + "\"targets\":[{\"POSITION\":2}]}]}]}";
        byte[] jb = json.getBytes(StandardCharsets.UTF_8);
        byte[] jpad = pad(jb, (byte) 0x20);
        byte[] bb = bin.toByteArray();
        byte[] bpad = pad(bb, (byte) 0x00);
        int len = 12 + 8 + jpad.length + 8 + bpad.length;
        MessageWriter w = new MessageWriter();
        w.int32(0x46546C67).int32(2).int32(len);
        w.int32(jpad.length).int32(0x4E4F534A).bytes(jpad);
        w.int32(bpad.length).int32(0x004E4942).bytes(bpad);
        return w.toByteArray();
    }

    /** Scales the deltas of one morph target's POSITION accessor in the glb. */
    @SuppressWarnings("unchecked")
    private static byte[] scaleMorphTarget(byte[] glb, int target, float factor) {
        int jlen = le32(glb, 12);
        Map<String, Object> root =
                (Map<String, Object>) Json.parse(new String(glb, 20, jlen, StandardCharsets.UTF_8));
        Map<String, Object> mesh0 = (Map<String, Object>) ((List<Object>) root.get("meshes")).get(0);
        Map<String, Object> prim = (Map<String, Object>) ((List<Object>) mesh0.get("primitives")).get(0);
        List<Object> targets = (List<Object>) prim.get("targets");
        int accIdx = ((Number) ((Map<String, Object>) targets.get(target)).get("POSITION")).intValue();
        Map<String, Object> acc = (Map<String, Object>) ((List<Object>) root.get("accessors")).get(accIdx);
        int count = ((Number) acc.get("count")).intValue();
        int bvIdx = ((Number) acc.get("bufferView")).intValue();
        Map<String, Object> bv = (Map<String, Object>) ((List<Object>) root.get("bufferViews")).get(bvIdx);
        int bvOff = bv.get("byteOffset") == null ? 0 : ((Number) bv.get("byteOffset")).intValue();
        int accOff = acc.get("byteOffset") == null ? 0 : ((Number) acc.get("byteOffset")).intValue();
        int base = 20 + jlen + 8 + bvOff + accOff;
        byte[] out = glb.clone();
        for(int i = 0; i < count * 3; i++) {
            int p = base + i * 4;
            float v = Float.intBitsToFloat(le32(out, p)) * factor;
            int b = Float.floatToIntBits(v);
            out[p] = (byte) b;
            out[p + 1] = (byte) (b >>> 8);
            out[p + 2] = (byte) (b >>> 16);
            out[p + 3] = (byte) (b >>> 24);
        }
        return out;
    }

    private static byte[] vbufLayer(ResContainer res) {
        for(Layer l : res.layers)
            if(l.name.equals("vbuf2"))
                return l.data;
        throw new AssertionError("no vbuf2");    }

    private static float[] decoded(byte[] resBytes, String attr) {
        return Vbuf2Data.parse(vbufLayer(ResContainer.parse(resBytes))).get(attr);
    }

    /* ---------------- glb position editing ---------------- */

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    @SuppressWarnings("unchecked")
    private static byte[] scalePositions(byte[] glb, float factor) {
        int jlen = le32(glb, 12);
        Map<String, Object> root =
                (Map<String, Object>) Json.parse(new String(glb, 20, jlen, StandardCharsets.UTF_8));
        Map<String, Object> mesh0 = (Map<String, Object>) ((List<Object>) root.get("meshes")).get(0);
        Map<String, Object> prim = (Map<String, Object>) ((List<Object>) mesh0.get("primitives")).get(0);
        int accIdx = ((Number) ((Map<String, Object>) prim.get("attributes")).get("POSITION")).intValue();
        Map<String, Object> acc = (Map<String, Object>) ((List<Object>) root.get("accessors")).get(accIdx);
        int count = ((Number) acc.get("count")).intValue();
        int bvIdx = ((Number) acc.get("bufferView")).intValue();
        Map<String, Object> bv = (Map<String, Object>) ((List<Object>) root.get("bufferViews")).get(bvIdx);
        int bvOff = bv.get("byteOffset") == null ? 0 : ((Number) bv.get("byteOffset")).intValue();
        int accOff = acc.get("byteOffset") == null ? 0 : ((Number) acc.get("byteOffset")).intValue();
        int base = 20 + jlen + 8 + bvOff + accOff;
        byte[] out = glb.clone();
        for(int i = 0; i < count * 3; i++) {
            int p = base + i * 4;
            float v = Float.intBitsToFloat(le32(out, p)) * factor;
            int b = Float.floatToIntBits(v);
            out[p] = (byte) b;
            out[p + 1] = (byte) (b >>> 8);
            out[p + 2] = (byte) (b >>> 16);
            out[p + 3] = (byte) (b >>> 24);
        }
        return out;
    }

    /** Builds a minimal .glb with one primitive carrying FLOAT POSITION + _VID accessors. */
    private static byte[] miniGlb(float[] gpos, float[] gvid) {
        int m = gvid.length;
        int posLen = m * 12, vidLen = m * 4, total = posLen + vidLen;
        MessageWriter bin = new MessageWriter();
        for(float v : gpos) bin.float32(v);
        for(float v : gvid) bin.float32(v);
        String json = "{\"asset\":{\"version\":\"2.0\"},"
                + "\"buffers\":[{\"byteLength\":" + total + "}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":" + posLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + posLen + ",\"byteLength\":" + vidLen + "}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":" + m + ",\"type\":\"SCALAR\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"_VID\":1}}]}]}";
        byte[] jb = json.getBytes(StandardCharsets.UTF_8);
        byte[] jpad = pad(jb, (byte) 0x20);
        byte[] bb = bin.toByteArray();
        byte[] bpad = pad(bb, (byte) 0x00);
        int len = 12 + 8 + jpad.length + 8 + bpad.length;
        MessageWriter w = new MessageWriter();
        w.int32(0x46546C67).int32(2).int32(len);
        w.int32(jpad.length).int32(0x4E4F534A).bytes(jpad);
        w.int32(bpad.length).int32(0x004E4942).bytes(bpad);
        return w.toByteArray();
    }

    /** Like {@link #miniGlb} but with no {@code _VID} (POSITION only). */
    private static byte[] miniGlbPosOnly(float[] gpos) {
        int m = gpos.length / 3;
        int posLen = m * 12;
        MessageWriter bin = new MessageWriter();
        for(float v : gpos) bin.float32(v);
        String json = "{\"asset\":{\"version\":\"2.0\"},"
                + "\"buffers\":[{\"byteLength\":" + posLen + "}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":" + posLen + "}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC3\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}]}";
        byte[] jb = json.getBytes(StandardCharsets.UTF_8);
        byte[] jpad = pad(jb, (byte) 0x20);
        byte[] bb = bin.toByteArray();
        byte[] bpad = pad(bb, (byte) 0x00);
        int len = 12 + 8 + jpad.length + 8 + bpad.length;
        MessageWriter w = new MessageWriter();
        w.int32(0x46546C67).int32(2).int32(len);
        w.int32(jpad.length).int32(0x4E4F534A).bytes(jpad);
        w.int32(bpad.length).int32(0x004E4942).bytes(bpad);
        return w.toByteArray();
    }

    private static byte[] pad(byte[] b, byte fill) {
        int rem = b.length % 4;
        if(rem == 0) return b;
        byte[] out = java.util.Arrays.copyOf(b, b.length + (4 - rem));
        for(int i = b.length; i < out.length; i++) out[i] = fill;
        return out;
    }

    @Test
    void missingIdsWithChangedCountSuggestsEnablingAttributes() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(4)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        // a glTF with no _VID and a different vertex count (Blender re-split)
        byte[] glb = miniGlbPosOnly(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1, 2, 2, 2});
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GltfImport.apply(res.serialize(), glb));
        assertTrue(ex.getMessage().contains("Attributes"), ex.getMessage());
    }

    @Test
    void unmatchedCoincidentVertexIsFilledFromSibling() {
        // vert 2 shares vert 0's position (a seam duplicate). The glTF only carries
        // ids 0 and 1 (Blender merged vert 2 into vert 0), so vert 2 must inherit
        // vert 0's new position via the coincident fallback.
        MessageWriter w = new MessageWriter();
        w.uint8(0).uint16(3);
        w.string("pos2").uint8(1).string("f4");
        for(float v : new float[]{5, 0, 0,  1, 2, 3,  5, 0, 0})
            w.float32(v);
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", w.toByteArray()));
        res.layers.add(new Layer("mesh", mesh(-1)));

        float[][] newHaven = {{50, 0, 0}, {1, 2, 3}};
        int[] order = {0, 1};
        float[] gpos = new float[order.length * 3];
        float[] gvid = new float[order.length];
        for(int j = 0; j < order.length; j++) {
            gvid[j] = order[j];
            gpos[j * 3] = newHaven[order[j]][0];
            gpos[j * 3 + 1] = newHaven[order[j]][2];
            gpos[j * 3 + 2] = -newHaven[order[j]][1];
        }
        GltfImport.Result r = GltfImport.apply(res.serialize(), miniGlb(gpos, gvid));
        assertEquals(2, r.matched);
        float[] after = decoded(r.res, "pos");
        assertEquals(50f, after[0], 1e-4f);
        assertEquals(50f, after[6], 1e-4f, "coincident vert 2 should inherit vert 0's new position");
        assertEquals(0f, after[7], 1e-4f);
    }

    /* ---------------------------------------------------------------- tests */

    @Test
    void skinnedNoOpKeepsBonesByteIdentical() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("skel", skel2()));
        res.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();

        GltfImport.Result r = GltfImport.apply(orig, GltfExport.toGlb(res, "rig.res").glb);
        assertFalse(r.bones, "unchanged weights must not re-encode bones2");
        assertArrayEquals(orig, r.res, "a skinned no-op must round-trip byte-for-byte");
    }

    @Test
    void editedWeightsAreReEncoded() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("skel", skel2()));
        res.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();
        Vbuf2Data d0 = Vbuf2Data.parse(vbufLayer(res));

        // fully bind every vertex to its (originally dominant) first joint
        byte[] glb = bindFirstJoint(GltfExport.toGlb(res, "rig.res").glb);
        GltfImport.Result r = GltfImport.apply(orig, glb);
        assertTrue(r.bones, "changed weights must re-encode bones2");

        Vbuf2Data d1 = Vbuf2Data.parse(vbufLayer(ResContainer.parse(r.res)));
        for(int v = 0; v < d1.num; v++) {
            int nz = 0;
            for(int k = 0; k < 4; k++)
                if(d1.vWeights[v * 4 + k] > 0)
                    nz++;
            assertEquals(1, nz, "vertex " + v + " should now have a single influence");
            assertEquals(d0.boneNames[d0.vJoints[v * 4]], d1.boneNames[d1.vJoints[v * 4]],
                    "vertex " + v + " should bind to its originally dominant bone");
            assertEquals(1f, d1.vWeights[v * 4], 1e-3f);
        }
    }

    @Test
    void setBones2EncodesUnormWeights() {        // re-encode a un1 bones2 directly: bind all vertices fully to bone "tip"
        Vbuf2Codec codec = Vbuf2Codec.parse(vbufBones2("un1"));
        int[] joints = new int[codec.num * 4];
        float[] weights = new float[codec.num * 4];
        java.util.Arrays.fill(joints, -1);
        for(int v = 0; v < codec.num; v++) {
            joints[v * 4] = 1;        // "tip"
            weights[v * 4] = 1f;
        }
        codec.setBones2(new String[]{"root", "tip"}, joints, weights);

        Vbuf2Data d = Vbuf2Data.parse(codec.encode());
        for(int v = 0; v < d.num; v++) {
            assertEquals("tip", d.boneNames[d.vJoints[v * 4]]);
            assertEquals(1f, d.vWeights[v * 4], 1e-2f);
        }
    }

    @Test
    void morphNoOpKeepsManimByteIdentical() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(4)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        res.layers.add(new Layer("manim", manim(0, 1.0f)));
        byte[] orig = res.serialize();

        GltfImport.Result r = GltfImport.apply(orig, GltfExport.toGlb(res, "morph.res").glb);
        assertFalse(r.morphs, "unchanged morph shapes must not re-encode manim");
        assertArrayEquals(orig, r.res, "an unchanged morph model must round-trip byte-for-byte");
    }

    @Test
    void editedMorphShapeIsReEncoded() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(4)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        res.layers.add(new Layer("manim", manim(0, 1.0f)));
        byte[] orig = res.serialize();
        MeshAnimInfo m0 = MeshAnimInfo.parse(manimLayer(ResContainer.parse(orig)));

        // double frame 0's deltas (target 0 = frame 0 of the single manim)
        byte[] glb = scaleMorphTarget(GltfExport.toGlb(res, "morph.res").glb, 0, 2.0f);
        GltfImport.Result r = GltfImport.apply(orig, glb);
        assertTrue(r.morphs, "changed morph shapes must re-encode manim");

        MeshAnimInfo m1 = MeshAnimInfo.parse(manimLayer(ResContainer.parse(r.res)));
        MeshAnimInfo.Frame f0o = m0.frames.get(0), f0n = m1.frames.get(0);
        for(int c = 0; c < 3; c++)
            assertEquals(f0o.pos[c] * 2f, f0n.pos[c], 1e-2f, "frame 0 delta " + c + " should be doubled");
        // frame 1 (untouched target) stays the same
        assertEquals(m0.frames.get(1).pos[0], m1.frames.get(1).pos[0], 1e-3f);
    }

    @Test
    void meshAnimEncodeWithRoundTrips() {
        MeshAnimInfo mi = MeshAnimInfo.parse(manim(7, 2.0f));
        float[][] nd = new float[2][12];                 // num = 4 vertices
        nd[0][0] = 0.2f; nd[0][1] = 0.4f; nd[0][2] = 0.6f;
        nd[1][0] = -0.1f;
        MeshAnimInfo back = MeshAnimInfo.parse(mi.encodeWith(nd, 4, 1e-6f));
        assertEquals(7, back.id);
        assertEquals(2, back.frames.size());
        assertEquals(0.2f, back.frames.get(0).pos[0], 1e-2f);
        assertEquals(0.4f, back.frames.get(0).pos[1], 1e-2f);
        assertEquals(-0.1f, back.frames.get(1).pos[0], 1e-2f);
    }

    private static byte[] manimLayer(ResContainer res) {
        for(Layer l : res.layers)
            if(l.name.equals("manim"))
                return l.data;
        throw new AssertionError("no manim");
    }

    /** Builds a dense glb with POSITION/NORMAL/TEXCOORD_0 + indices (for rebuild tests). */
    private static byte[] geomGlb(float[] pos, float[] nrm, float[] tex, int[] indices) {
        int m = pos.length / 3;
        int posLen = m * 12, nrmLen = m * 12, texLen = m * 8, idxLen = indices.length * 2;
        MessageWriter bin = new MessageWriter();
        for(float v : pos) bin.float32(v);
        for(float v : nrm) bin.float32(v);
        for(float v : tex) bin.float32(v);
        for(int v : indices) bin.uint16(v);
        if((idxLen & 3) != 0) bin.uint16(0);             // pad to 4
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
                + "{\"bufferView\":3,\"componentType\":5123,\"count\":" + indices.length + ",\"type\":\"SCALAR\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},"
                + "\"indices\":3}]}]}";
        byte[] jb = json.getBytes(StandardCharsets.UTF_8);
        byte[] jpad = pad(jb, (byte) 0x20);
        byte[] bb = bin.toByteArray();
        byte[] bpad = pad(bb, (byte) 0x00);
        MessageWriter w = new MessageWriter();
        w.int32(0x46546C67).int32(2).int32(12 + 8 + jpad.length + 8 + bpad.length);
        w.int32(jpad.length).int32(0x4E4F534A).bytes(jpad);
        w.int32(bpad.length).int32(0x004E4942).bytes(bpad);
        return w.toByteArray();
    }

    @Test
    void rebuildAcceptsAddedVertices() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", new byte[]{1, 2, 3}));
        res.layers.add(new Layer("vbuf2", vbufF4(3)));
        res.layers.add(new Layer("mesh", mesh(5)));
        byte[] orig = res.serialize();

        // a glb with FOUR vertices and two triangles (one more vertex than the original)
        float[] pos = {0, 0, 0,  1, 0, 0,  0, 1, 0,  1, 1, 0};
        float[] nrm = {0, 0, 1,  0, 0, 1,  0, 0, 1,  0, 0, 1};
        float[] tex = {0, 0,  1, 0,  0, 1,  1, 1};
        int[] indices = {0, 1, 2,  1, 3, 2};
        GltfImport.RebuildResult r = GltfImport.rebuild(orig, geomGlb(pos, nrm, tex, indices));

        assertEquals(4, r.vertices, "rebuild should accept the new vertex count");
        assertEquals(2, r.triangles);
        ResContainer out = ResContainer.parse(r.res);
        Vbuf2Data d = Vbuf2Data.parse(vbufLayer(out));
        assertEquals(4, d.num);
        // positions are axis-inverted: glTF (gx,gy,gz) -> Haven (gx,-gz,gy)
        assertEquals(1f, d.get("pos")[3], 1e-4f);        // vertex 1 x
        MeshInfo m = MeshInfo.parse(meshLayerBytes(out));
        assertEquals(2, m.numTris);
        assertEquals(5, m.matid, "original matid is preserved");
        // other layers kept
        assertEquals(3, out.layers.size());
        assertEquals("tex", out.layers.get(0).name);
    }

    /**
     * Builds a glb with two primitives, each its OWN vertex block (as Blender emits
     * per material) and a material named {@code rfmat_<matid>}, for multi-submesh
     * rebuild tests.
     */
    private static byte[] twoSubmeshGlb(int matidA, int matidB) {
        // submesh A: 3 verts / 1 tri; submesh B: 4 verts / 2 tris
        float[] pa = {0, 0, 0,  1, 0, 0,  0, 1, 0};
        float[] na = {0, 0, 1,  0, 0, 1,  0, 0, 1};
        float[] ta = {0, 0,  1, 0,  0, 1};
        int[] ia = {0, 1, 2};
        float[] pb = {2, 0, 0,  3, 0, 0,  2, 1, 0,  3, 1, 0};
        float[] nb = {0, 0, 1,  0, 0, 1,  0, 0, 1,  0, 0, 1};
        float[] tb = {0, 0,  1, 0,  0, 1,  1, 1};
        int[] ib = {0, 1, 2,  1, 3, 2};

        MessageWriter bin = new MessageWriter();
        StringBuilder bvs = new StringBuilder(), accs = new StringBuilder();
        StringBuilder prims = new StringBuilder();
        int off = 0, accN = 0;
        float[][] poss = {pa, pb}, nrms = {na, nb}, texs = {ta, tb};
        int[][] idxs = {ia, ib};
        int[] matids = {matidA, matidB};
        for(int s = 0; s < 2; s++) {
            int m = poss[s].length / 3;
            int posBv = bv(bvs, off, m * 12); for(float v : poss[s]) bin.float32(v); off += m * 12;
            int nrmBv = bv(bvs, off, m * 12); for(float v : nrms[s]) bin.float32(v); off += m * 12;
            int texBv = bv(bvs, off, m * 8);  for(float v : texs[s]) bin.float32(v); off += m * 8;
            int idxBv = bv(bvs, off, idxs[s].length * 2);
            for(int v : idxs[s]) bin.uint16(v);
            off += idxs[s].length * 2;
            while((off & 3) != 0) { bin.uint16(0); off += 2; }
            int pA = accN++, nA = accN++, tA = accN++, iA = accN++;
            acc(accs, posBv, 5126, m, "VEC3");
            acc(accs, nrmBv, 5126, m, "VEC3");
            acc(accs, texBv, 5126, m, "VEC2");
            acc(accs, idxBv, 5123, idxs[s].length, "SCALAR");
            if(prims.length() > 0) prims.append(",");
            prims.append("{\"attributes\":{\"POSITION\":").append(pA).append(",\"NORMAL\":").append(nA)
                    .append(",\"TEXCOORD_0\":").append(tA).append("},\"indices\":").append(iA)
                    .append(",\"material\":").append(s).append("}");
        }
        String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":" + off + "}],"
                + "\"bufferViews\":[" + bvs + "],\"accessors\":[" + accs + "],"
                + "\"materials\":[{\"name\":\"rfmat_" + matids[0] + "\"},{\"name\":\"rfmat_" + matids[1] + "\"}],"
                + "\"meshes\":[{\"primitives\":[" + prims + "]}]}";
        byte[] jb = json.getBytes(StandardCharsets.UTF_8);
        byte[] jpad = pad(jb, (byte) 0x20);
        byte[] bb = bin.toByteArray();
        byte[] bpad = pad(bb, (byte) 0x00);
        MessageWriter w = new MessageWriter();
        w.int32(0x46546C67).int32(2).int32(12 + 8 + jpad.length + 8 + bpad.length);
        w.int32(jpad.length).int32(0x4E4F534A).bytes(jpad);
        w.int32(bpad.length).int32(0x004E4942).bytes(bpad);
        return w.toByteArray();
    }

    private static int bv(StringBuilder b, int off, int len) {
        if(b.length() > 0) b.append(",");
        b.append("{\"buffer\":0,\"byteOffset\":").append(off).append(",\"byteLength\":").append(len).append("}");
        return b.toString().split("\\},\\{").length - 1;   // index = current count - 1
    }

    private static void acc(StringBuilder a, int bvIdx, int ct, int count, String type) {
        if(a.length() > 0) a.append(",");
        a.append("{\"bufferView\":").append(bvIdx).append(",\"componentType\":").append(ct)
                .append(",\"count\":").append(count).append(",\"type\":\"").append(type).append("\"}");
    }

    @Test
    void rebuildMergesMultipleSubmeshes() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(3)));
        res.layers.add(new Layer("mesh", mesh(1)));
        res.layers.add(new Layer("mesh", mesh(2)));
        byte[] orig = res.serialize();

        // two separate-block submeshes (3 + 4 verts), matids 1 and 2 by material name
        GltfImport.RebuildResult r = GltfImport.rebuild(orig, twoSubmeshGlb(1, 2));
        assertEquals(7, r.vertices, "the two blocks should concatenate (3+4)");
        assertEquals(3, r.triangles);

        ResContainer out = ResContainer.parse(r.res);
        Vbuf2Data d = Vbuf2Data.parse(vbufLayer(out));
        assertEquals(7, d.num);
        List<MeshInfo> ms = new java.util.ArrayList<>();
        for(Layer l : out.layers)
            if(l.name.equals("mesh"))
                ms.add(MeshInfo.parse(l.data));
        assertEquals(2, ms.size(), "two submeshes preserved");
        assertEquals(1, ms.get(0).matid, "first submesh matid recovered from rfmat_1");
        assertEquals(2, ms.get(1).matid, "second submesh matid recovered from rfmat_2");
        // submesh B's indices must be offset by submesh A's 3 vertices
        short maxA = 0;
        for(short s : ms.get(0).indices) maxA = (short) Math.max(maxA, s);
        short minB = Short.MAX_VALUE;
        for(short s : ms.get(1).indices) minB = (short) Math.min(minB, s);
        assertTrue(minB > maxA, "second submesh indices are offset past the first block");
    }

    /** Like {@link #geomGlb} but adds one dense morph target with the given deltas. */
    /** vbuf2 ver0 with pos/nrm/tex/tan/bit all f4 (tan/bit values are placeholders, recomputed on rebuild). */
    private static byte[] vbufTangents(int n) {
        MessageWriter w = new MessageWriter();
        w.uint8(0).uint16(n);
        w.string("pos2").uint8(1).string("f4");
        for(int i = 0; i < n; i++) w.float32(i).float32(0).float32(0);
        w.string("nrm2").uint8(1).string("f4");
        for(int i = 0; i < n; i++) w.float32(0).float32(0).float32(1);
        w.string("tex2").uint8(1).string("f4");
        for(int i = 0; i < n; i++) w.float32(0).float32(0);
        w.string("tan2").uint8(1).string("f4");
        for(int i = 0; i < n; i++) w.float32(1).float32(0).float32(0);
        w.string("bit2").uint8(1).string("f4");
        for(int i = 0; i < n; i++) w.float32(1).float32(0).float32(0);
        return w.toByteArray();
    }

    @Test
    void rebuildRecomputesTangents() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufTangents(3)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();

        float[] pos = {0, 0, 0,  1, 0, 0,  0, 1, 0,  1, 1, 0};
        float[] nrm = {0, 0, 1,  0, 0, 1,  0, 0, 1,  0, 0, 1};
        float[] tex = {0, 0,  1, 0,  0, 1,  1, 1};
        int[] indices = {0, 1, 2,  1, 3, 2};
        GltfImport.RebuildResult r = GltfImport.rebuild(orig, geomGlb(pos, nrm, tex, indices));
        assertEquals(4, r.vertices);

        ResContainer out = ResContainer.parse(r.res);
        Vbuf2Codec c = Vbuf2Codec.parse(vbufLayer(out));
        assertArrayEquals(c.attr("tan").data, c.attr("bit").data, "Haven stores bit identical to tan");
        Vbuf2Data d = Vbuf2Data.parse(vbufLayer(out));
        float[] tan = d.get("tan");
        for(int v = 0; v < 4; v++) {
            double len = Math.sqrt(tan[v * 3] * tan[v * 3] + tan[v * 3 + 1] * tan[v * 3 + 1] + tan[v * 3 + 2] * tan[v * 3 + 2]);
            assertEquals(1.0, len, 1e-3, "recomputed tangent should be unit length");
        }
    }

    /** A glb with one primitive that has NO indices (POSITION/NORMAL/TEXCOORD only). */
    private static byte[] nonIndexedGlb(float[] pos, float[] nrm, float[] tex) {
        int m = pos.length / 3;
        int posLen = m * 12, nrmLen = m * 12, texLen = m * 8;
        MessageWriter bin = new MessageWriter();
        for(float v : pos) bin.float32(v);
        for(float v : nrm) bin.float32(v);
        for(float v : tex) bin.float32(v);
        int total = posLen + nrmLen + texLen;
        String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":" + total + "}],"
                + "\"bufferViews\":["
                + "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":" + posLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + posLen + ",\"byteLength\":" + nrmLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + (posLen + nrmLen) + ",\"byteLength\":" + texLen + "}],"
                + "\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC3\"},"
                + "{\"bufferView\":2,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC2\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2}}]}]}";
        byte[] jb = json.getBytes(StandardCharsets.UTF_8);
        byte[] jpad = pad(jb, (byte) 0x20);
        byte[] bb = bin.toByteArray();
        byte[] bpad = pad(bb, (byte) 0x00);
        MessageWriter w = new MessageWriter();
        w.int32(0x46546C67).int32(2).int32(12 + 8 + jpad.length + 8 + bpad.length);
        w.int32(jpad.length).int32(0x4E4F534A).bytes(jpad);
        w.int32(bpad.length).int32(0x004E4942).bytes(bpad);
        return w.toByteArray();
    }

    @Test
    void rebuildHandlesNonIndexedPrimitive() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(3)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();
        // 6 vertices, two triangles, NO index buffer -> indices should be 0..5
        float[] pos = {0, 0, 0,  1, 0, 0,  0, 1, 0,  1, 1, 0,  2, 0, 0,  2, 1, 0};
        float[] nrm = new float[18];
        for(int i = 0; i < 6; i++) nrm[i * 3 + 2] = 1;
        float[] tex = new float[12];
        GltfImport.RebuildResult r = GltfImport.rebuild(orig, nonIndexedGlb(pos, nrm, tex));
        assertEquals(6, r.vertices);
        assertEquals(2, r.triangles, "non-indexed 6 verts -> 2 triangles over its own vertices");
        MeshInfo m = MeshInfo.parse(meshLayerBytes(ResContainer.parse(r.res)));
        assertEquals(2, m.numTris);
        for(short s : m.indices)
            assertTrue(s >= 0 && s < 6, "indices stay within the primitive's own vertices");
    }

    private static byte[] geomGlbMorph(float[] pos, float[] nrm, float[] tex, int[] indices, float[] morph) {
        int m = pos.length / 3;
        int posLen = m * 12, nrmLen = m * 12, texLen = m * 8, idxLen = indices.length * 2, mLen = m * 12;
        MessageWriter bin = new MessageWriter();
        for(float v : pos) bin.float32(v);
        for(float v : nrm) bin.float32(v);
        for(float v : tex) bin.float32(v);
        for(int v : indices) bin.uint16(v);
        if((idxLen & 3) != 0) bin.uint16(0);
        for(float v : morph) bin.float32(v);
        int po = 0, no = posLen, to = posLen + nrmLen, io = posLen + nrmLen + texLen;
        int mo = io + ((idxLen + 3) & ~3);
        int total = mo + mLen;
        String json = "{\"asset\":{\"version\":\"2.0\"},"
                + "\"buffers\":[{\"byteLength\":" + total + "}],"
                + "\"bufferViews\":["
                + "{\"buffer\":0,\"byteOffset\":" + po + ",\"byteLength\":" + posLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + no + ",\"byteLength\":" + nrmLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + to + ",\"byteLength\":" + texLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + io + ",\"byteLength\":" + idxLen + "},"
                + "{\"buffer\":0,\"byteOffset\":" + mo + ",\"byteLength\":" + mLen + "}],"
                + "\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC3\"},"
                + "{\"bufferView\":2,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC2\"},"
                + "{\"bufferView\":3,\"componentType\":5123,\"count\":" + indices.length + ",\"type\":\"SCALAR\"},"
                + "{\"bufferView\":4,\"componentType\":5126,\"count\":" + m + ",\"type\":\"VEC3\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},"
                + "\"indices\":3,\"targets\":[{\"POSITION\":4}]}]}]}";
        byte[] jb = json.getBytes(StandardCharsets.UTF_8);
        byte[] jpad = pad(jb, (byte) 0x20);
        byte[] bb = bin.toByteArray();
        byte[] bpad = pad(bb, (byte) 0x00);
        MessageWriter w = new MessageWriter();
        w.int32(0x46546C67).int32(2).int32(12 + 8 + jpad.length + 8 + bpad.length);
        w.int32(jpad.length).int32(0x4E4F534A).bytes(jpad);
        w.int32(bpad.length).int32(0x004E4942).bytes(bpad);
        return w.toByteArray();
    }

    @Test
    void rebuildReEncodesMorphShapes() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(3)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        res.layers.add(new Layer("manim", manim1(0, 1.0f)));   // 1 frame morphing vertex 1
        byte[] orig = res.serialize();

        // glb: 3 verts, one morph target whose vertex 1 delta is glTF (0.5,0.5,-0.5) -> Haven (0.5,0.5,0.5)
        float[] pos = {0, 0, 0,  1, 0, 0,  0, 1, 0};
        float[] nrm = {0, 0, 1,  0, 0, 1,  0, 0, 1};
        float[] tex = {0, 0,  1, 0,  0, 1};
        int[] indices = {0, 1, 2};
        float[] morph = {0, 0, 0,  0.5f, 0.5f, -0.5f,  0, 0, 0};
        GltfImport.RebuildResult r = GltfImport.rebuild(orig, geomGlbMorph(pos, nrm, tex, indices, morph));

        assertEquals(3, r.vertices);
        MeshAnimInfo m = MeshAnimInfo.parse(manimLayer(ResContainer.parse(r.res)));
        MeshAnimInfo.Frame f0 = m.frames.get(0);
        // vertex 1 should carry the re-encoded Haven delta (0.5,0.5,0.5)
        int q = -1;
        for(int i = 0; i < f0.idx.length; i++)
            if(f0.idx[i] == 1) q = i;
        assertTrue(q >= 0, "vertex 1 should be in the rebuilt morph frame");
        assertEquals(0.5f, f0.pos[q * 3], 1e-2f);
        assertEquals(0.5f, f0.pos[q * 3 + 1], 1e-2f);
        assertEquals(0.5f, f0.pos[q * 3 + 2], 1e-2f);
    }

    private static byte[] meshLayerBytes(ResContainer res) {
        for(Layer l : res.layers)
            if(l.name.equals("mesh"))
                return l.data;
        throw new AssertionError("no mesh");
    }

    private static byte[] skelLayer(ResContainer res) {
        for(Layer l : res.layers)
            if(l.name.equals("skel"))
                return l.data;
        throw new AssertionError("no skel");
    }


    private static SkelInfo.Bone boneByName(SkelInfo s, String n) {
        for(SkelInfo.Bone b : s.bones)
            if(b.name.equals(n))
                return b;
        throw new AssertionError("no bone " + n);
    }

    /** Adds (dx,dy,dz) to a bone node's translation in the glb (parse JSON, edit, reassemble). */
    @SuppressWarnings("unchecked")
    private static byte[] moveBoneInGlb(byte[] glb, String bone, double dx, double dy, double dz) {
        int jlen = le32(glb, 12);
        Map<String, Object> root =
                (Map<String, Object>) Json.parse(new String(glb, 20, jlen, StandardCharsets.UTF_8));
        for(Object no : (List<Object>) root.get("nodes")) {
            Map<String, Object> n = (Map<String, Object>) no;
            if(bone.equals(String.valueOf(n.get("name")))) {
                List<Object> tl = (List<Object>) n.get("translation");
                double tx = tl == null ? 0 : ((Number) tl.get(0)).doubleValue();
                double ty = tl == null ? 0 : ((Number) tl.get(1)).doubleValue();
                double tz = tl == null ? 0 : ((Number) tl.get(2)).doubleValue();
                n.put("translation", List.of(tx + dx, ty + dy, tz + dz));
            }
        }
        byte[] jb = Json.write(root).getBytes(StandardCharsets.UTF_8);
        byte[] jpad = pad(jb, (byte) 0x20);
        int binStart = 20 + jlen;
        int binLen = le32(glb, binStart);
        byte[] bin = java.util.Arrays.copyOfRange(glb, binStart + 8, binStart + 8 + binLen);
        MessageWriter w = new MessageWriter();
        w.int32(0x46546C67).int32(2).int32(12 + 8 + jpad.length + 8 + bin.length);
        w.int32(jpad.length).int32(0x4E4F534A).bytes(jpad);
        w.int32(bin.length).int32(0x004E4942).bytes(bin);
        return w.toByteArray();
    }

    @Test
    void skelEncodeVer1RoundTrips() {
        List<SkelInfo.Bone> bones = List.of(
                new SkelInfo.Bone("root", "", 1f, 2f, 3f, 0f, 0f, 1f, 0f),
                new SkelInfo.Bone("tip", "root", 0f, 0f, 1f, 0.57735f, 0.57735f, 0.57735f, 1.2f));
        SkelInfo back = SkelInfo.parse(SkelInfo.encodeVer1(bones));
        assertTrue(back.recognized);
        assertEquals(2, back.bones.size());
        SkelInfo.Bone r = back.bones.get(0), t = back.bones.get(1);
        assertEquals("root", r.name);
        assertEquals("", r.parent);
        assertEquals(1f, r.px, 1e-4f);
        assertEquals(2f, r.py, 1e-4f);
        assertEquals(3f, r.pz, 1e-4f);
        assertEquals("tip", t.name);
        assertEquals("root", t.parent);
        assertEquals(1f, t.pz, 1e-4f);
        assertEquals(1.2f, t.ang, 2e-3f);                // mnorm16 angle precision
        assertEquals(0.577f, t.ax, 1e-2f);               // octahedral axis precision
        assertEquals(0.577f, t.ay, 1e-2f);
        assertEquals(0.577f, t.az, 1e-2f);
    }

    @Test
    void skelNoOpKeepsSkelByteIdentical() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("skel", skel2()));
        res.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();

        GltfImport.Result r = GltfImport.apply(orig, GltfExport.toGlb(res, "rig.res").glb);
        assertFalse(r.skel, "an unchanged skeleton must not be re-encoded");
        assertArrayEquals(orig, r.res, "a skeleton no-op must round-trip byte-for-byte");
    }

    @Test
    void editedSkelBoneIsReEncoded() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("skel", skel2()));
        res.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();

        byte[] glb = moveBoneInGlb(GltfExport.toGlb(res, "rig.res").glb, "tip", 3, 0, 0);
        GltfImport.Result r = GltfImport.apply(orig, glb);
        assertTrue(r.skel, "moving a bone must re-encode the skeleton");
        SkelInfo.Bone tip = boneByName(SkelInfo.parse(skelLayer(ResContainer.parse(r.res))), "tip");
        assertEquals(3f, tip.px, 1e-3f, "tip (orig x=0) should move to x=3");
        assertEquals(1f, tip.pz, 1e-3f, "tip's other coords stay");
    }

    @Test
    void sparseMorphTargetIsReadAndReEncoded() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(4)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        res.layers.add(new Layer("manim", manim1(0, 1.0f)));
        byte[] orig = res.serialize();

        // dense POSITION = axis-converted original positions; one sparse morph target
        // overrides vertex 1 with a glTF delta of (0.5, 0.5, -0.5) -> Haven (0.5,0.5,0.5)
        float[] gpos = new float[12];
        for(int v = 0; v < 4; v++) {
            gpos[v * 3] = v;
            gpos[v * 3 + 1] = -v;
            gpos[v * 3 + 2] = -v * 0.5f;
        }
        float[] gvid = {0, 1, 2, 3};
        byte[] glb = sparseMorphGlb(gpos, gvid, 1, new float[]{0.5f, 0.5f, -0.5f});

        GltfImport.Result r = GltfImport.apply(orig, glb);
        assertTrue(r.morphs, "a sparse morph target must be read and re-encoded");
        MeshAnimInfo m = MeshAnimInfo.parse(manimLayer(ResContainer.parse(r.res)));
        MeshAnimInfo.Frame f0 = m.frames.get(0);
        assertEquals(0.5f, f0.pos[0], 1e-2f);
        assertEquals(0.5f, f0.pos[1], 1e-2f);
        assertEquals(0.5f, f0.pos[2], 1e-2f);
    }

    @Test
    void unchangedRoundTripIsByteIdentical() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(5)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();

        byte[] glb = GltfExport.toGlb(res, "t.res").glb;
        GltfImport.Result r = GltfImport.apply(orig, glb);

        assertEquals(5, r.vertices);
        assertArrayEquals(orig, r.res, "unchanged f4 geometry must round-trip byte-for-byte");
    }

    @Test
    void quantizedRoundTripIsByteIdentical() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufQuant()));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();

        byte[] glb = GltfExport.toGlb(res, "t.res").glb;
        GltfImport.Result r = GltfImport.apply(orig, glb);

        Vbuf2Codec a = Vbuf2Codec.parse(vbufLayer(res));
        Vbuf2Codec b = Vbuf2Codec.parse(vbufLayer(ResContainer.parse(r.res)));
        assertArrayEquals(a.attr("pos").data, b.attr("pos").data, "sn2 positions re-quantise exactly");
        assertArrayEquals(a.attr("tex").data, b.attr("tex").data, "un2 UVs re-quantise exactly");
        assertArrayEquals(a.attr("nrm").data, b.attr("nrm").data, "uvec1 normals re-quantise exactly");
    }

    @Test
    void editedPositionsAreReEncoded() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(4)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();
        float[] before = decoded(orig, "pos");

        byte[] glb = scalePositions(GltfExport.toGlb(res, "t.res").glb, 2.0f);
        GltfImport.Result r = GltfImport.apply(orig, glb);
        float[] after = decoded(r.res, "pos");

        assertEquals(before.length, after.length);
        for(int i = 0; i < before.length; i++)
            assertEquals(before[i] * 2.0f, after[i], 1e-4f, "position " + i + " should be doubled");
    }

    @Test
    void vertexCountMismatchIsRejected() {
        ResContainer small = new ResContainer(7);
        small.layers.add(new Layer("vbuf2", vbufF4(3)));
        small.layers.add(new Layer("mesh", mesh(-1)));

        ResContainer big = new ResContainer(7);
        big.layers.add(new Layer("vbuf2", vbufF4(6)));
        big.layers.add(new Layer("mesh", mesh(-1)));
        byte[] glb6 = GltfExport.toGlb(big, "big.res").glb;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GltfImport.apply(small.serialize(), glb6));
        assertTrue(ex.getMessage().contains("beyond this model"), ex.getMessage());
    }

    @Test
    void reorderedAndDuplicatedVerticesMapByIdRegardlessOfCount() {
        // Simulate Blender: same geometry re-exported with vertices reordered and
        // one seam vertex duplicated, so the glTF vertex count differs (5 vs 4).
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(4)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();

        // new Haven positions we "edited" to (x scaled by 10)
        float[][] newHaven = new float[4][];
        for(int v = 0; v < 4; v++)
            newHaven[v] = new float[]{v * 10f, v * 0.5f, -v};
        // glTF vertices in reversed order, plus a duplicate of vid 0 (count = 5)
        int[] order = {3, 2, 1, 0, 0};
        float[] gpos = new float[order.length * 3];
        float[] gvid = new float[order.length];
        for(int j = 0; j < order.length; j++) {
            int v = order[j];
            gvid[j] = v;
            // Haven -> glTF: (hx,hy,hz) -> (hx, hz, -hy)
            gpos[j * 3] = newHaven[v][0];
            gpos[j * 3 + 1] = newHaven[v][2];
            gpos[j * 3 + 2] = -newHaven[v][1];
        }
        byte[] glb = miniGlb(gpos, gvid);

        GltfImport.Result r = GltfImport.apply(orig, glb);
        assertEquals(4, r.matched, "all four original vertices should be matched by id");
        float[] after = decoded(r.res, "pos");
        for(int v = 0; v < 4; v++) {
            assertEquals(newHaven[v][0], after[v * 3], 1e-4f);
            assertEquals(newHaven[v][1], after[v * 3 + 1], 1e-4f);
            assertEquals(newHaven[v][2], after[v * 3 + 2], 1e-4f);
        }
    }

    @Test
    void keepsBonesAndOtherLayersByteForByte() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("skel", skel()));
        res.layers.add(new Layer("vbuf2", vbufBones()));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();

        byte[] glb = GltfExport.toGlb(res, "rig.res").glb;
        GltfImport.Result r = GltfImport.apply(orig, glb);

        // f4 positions round-trip exactly and bones/skel/mesh are carried over verbatim
        assertArrayEquals(orig, r.res, "skinned model with f4 positions must round-trip byte-for-byte");
    }

    @Test
    void resWithoutGeometryIsRejected() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("image", new byte[]{1, 2, 3}));
        byte[] anyGlb = GltfExport.toGlb(withGeometry(), "g.res").glb;
        assertThrows(IllegalArgumentException.class,
                () -> GltfImport.apply(res.serialize(), anyGlb));
    }

    @Test
    void nonGlbInputIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GltfImport.apply(withGeometry().serialize(), new byte[]{0, 1, 2, 3, 4, 5}));
    }

    private static ResContainer withGeometry() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(3)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        return res;
    }
}
