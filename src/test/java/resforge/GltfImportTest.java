package resforge;

import resforge.io.Json;
import resforge.io.MessageReader;
import resforge.io.MessageWriter;
import resforge.layers.MeshAnimInfo;
import resforge.layers.MeshInfo;
import resforge.layers.SkanInfo;
import resforge.layers.SkelInfo;
import resforge.model.GltfExport;
import resforge.model.GltfImport;
import resforge.vbuf.Vbuf2Codec;
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

    private static byte[] mesh(int matid) {
        MessageWriter w = new MessageWriter();
        w.uint8(16);
        w.uint16(1);
        w.int16(matid);
        w.int16(0);
        w.uint16(0).uint16(1).uint16(2);
        return w.toByteArray();
    }

    private static byte[] modernMesh(int matid, int ref) {
        MessageWriter w = new MessageWriter();
        w.uint8(0x81).int16(-1).int16(0);
        w.string("mat").uint8(4).uint8(matid);
        w.string("ref").uint8(4).uint8(ref);
        w.string("").string("").uint16(1);
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

    private static byte[] skanRootWithEffect(int id) {
        MessageWriter w = new MessageWriter();
        w.int16(id).uint8(2).uint8(1).float32(1);
        w.string("root").uint16(1);
        w.uint16(0).float16(0).float16(0).float16(0);
        w.uint16(0).int16(0).int16(0);
        w.string("{ctl}").uint16(1);
        w.uint16(0).uint8(1).string("trigger");
        return w.toByteArray();
    }

    private static byte[] zeroLengthSkan(int id) {
        MessageWriter w = new MessageWriter();
        w.int16(id).uint8(2).uint8(1).float32(0);
        w.string("root").uint16(1);
        w.uint16(0).float16(0).float16(0).float16(0);
        w.uint16(0).int16(0).int16(0);
        return w.toByteArray();
    }

    private static byte[] skanRootTwoFrames(int id, float secondX) {
        return skanRootTwoFrames(id, secondX, 1, 0xffff);
    }

    private static byte[] skanRootTwoFrames(int id, float secondX, float length, int secondTime) {
        return skanBoneTwoFrames(id, "root", secondX, length, secondTime);
    }

    private static byte[] skanBoneTwoFrames(int id, String bone, float secondX) {
        return skanBoneTwoFrames(id, bone, secondX, 1, 0xffff);
    }

    private static byte[] skanBoneTwoFrames(int id, String bone, float secondX,
                                            float length, int secondTime) {
        MessageWriter w = new MessageWriter();
        w.int16(id).uint8(2).uint8(1).float32(length);
        w.string(bone).uint16(2);
        w.uint16(0).float16(0).float16(0).float16(0);
        w.uint16(0).int16(0).int16(0);
        w.uint16(secondTime).float16(secondX).float16(0).float16(0);
        w.uint16(0).int16(0).int16(0);
        return w.toByteArray();
    }

    private static byte[] skanRootFormatZero(int id) {
        MessageWriter w = new MessageWriter();
        w.int16(id).uint8(0).uint8(1).cpfloat(1);
        w.string("root").uint16(2);
        w.cpfloat(0).cpfloat(0).cpfloat(0).cpfloat(0);
        w.cpfloat(0).cpfloat(0).cpfloat(0).cpfloat(1);
        w.cpfloat(1).cpfloat(1).cpfloat(0).cpfloat(0);
        w.cpfloat(0).cpfloat(0).cpfloat(0).cpfloat(1);
        return w.toByteArray();
    }

    private static byte[] skanRootFormatZeroNearUnitAxis(int id) {
        MessageWriter w = new MessageWriter();
        w.int16(id).uint8(0).uint8(1).cpfloat(1);
        w.string("root").uint16(1);
        w.cpfloat(0).cpfloat(0).cpfloat(0).cpfloat(0);
        w.cpfloat(Math.PI).cpfloat(0.707106f).cpfloat(0.707106f).cpfloat(0);
        return w.toByteArray();
    }

    private static byte[] skanTwoBones(int id) {
        MessageWriter w = new MessageWriter();
        w.int16(id).uint8(2).uint8(1).float32(1);
        w.string("root").uint16(2);
        w.uint16(0).float16(0).float16(0).float16(0);
        w.uint16(0).int16(0).int16(0);
        w.uint16(0x8000).float16(1).float16(0).float16(0);
        w.uint16(0).int16(0).int16(0);
        w.string("tip").uint16(2);
        w.uint16(0).float16(0).float16(0).float16(0);
        w.uint16(0).int16(0).int16(0);
        w.uint16(0xffff).float16(0).float16(1).float16(0);
        w.uint16(0).int16(0).int16(0);
        return w.toByteArray();
    }

    private static byte[] vbufLayer(ResContainer res) {
        for(Layer l : res.layers)
            if(l.name.equals("vbuf2"))
                return l.data;
        throw new AssertionError("no vbuf2");    }

    /* ---------------- glb position editing ---------------- */

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static byte[] pad(byte[] b, byte fill) {
        int rem = b.length % 4;
        if(rem == 0) return b;
        byte[] out = java.util.Arrays.copyOf(b, b.length + (4 - rem));
        for(int i = b.length; i < out.length; i++) out[i] = fill;
        return out;
    }

    @SuppressWarnings("unchecked")
    private static byte[] moveAnimationTranslation(byte[] glb, String action, String bone, float dx) {
        byte[] out = glb.clone();
        int jsonLen = le32(out, 12);
        Map<String, Object> root = (Map<String, Object>) Json.parse(
                new String(out, 20, jsonLen, StandardCharsets.UTF_8));
        List<Object> nodes = (List<Object>) root.get("nodes");
        int nodeIndex = -1;
        for(int i = 0; i < nodes.size(); i++)
            if(bone.equals(((Map<String, Object>) nodes.get(i)).get("name")))
                nodeIndex = i;
        assertTrue(nodeIndex >= 0);
        Map<String, Object> selected = null;
        for(Object value : (List<Object>) root.get("animations")) {
            Map<String, Object> candidate = (Map<String, Object>) value;
            if(action.equals(candidate.get("name")))
                selected = candidate;
        }
        assertTrue(selected != null);
        List<Object> samplers = (List<Object>) selected.get("samplers");
        int output = -1;
        for(Object value : (List<Object>) selected.get("channels")) {
            Map<String, Object> channel = (Map<String, Object>) value;
            Map<String, Object> target = (Map<String, Object>) channel.get("target");
            if(((Number) target.get("node")).intValue() == nodeIndex
                    && "translation".equals(target.get("path"))) {
                Map<String, Object> sampler =
                        (Map<String, Object>) samplers.get(((Number) channel.get("sampler")).intValue());
                output = ((Number) sampler.get("output")).intValue();
            }
        }
        assertTrue(output >= 0);
        Map<String, Object> accessor =
                (Map<String, Object>) ((List<Object>) root.get("accessors")).get(output);
        Map<String, Object> view = (Map<String, Object>) ((List<Object>) root.get("bufferViews"))
                .get(((Number) accessor.get("bufferView")).intValue());
        int binStart = 20 + jsonLen + 8;
        int offset = binStart + ((Number) view.getOrDefault("byteOffset", 0)).intValue()
                + ((Number) accessor.getOrDefault("byteOffset", 0)).intValue();
        float value = Float.intBitsToFloat(le32(out, offset)) + dx;
        int bits = Float.floatToIntBits(value);
        for(int i = 0; i < 4; i++)
            out[offset + i] = (byte) (bits >>> (i * 8));
        return out;
    }

    /** Makes Blender-style constant STEP channels and optionally adds a sampled scale channel. */
    @SuppressWarnings("unchecked")
    private static byte[] stepAnimation(byte[] glb, Float scale) {
        return stepAnimation(glb, scale, 1f);
    }

    @SuppressWarnings("unchecked")
    private static byte[] stepAnimation(byte[] glb, Float scale, float scaleEndTime) {
        int jsonLen = le32(glb, 12);
        Map<String, Object> root = (Map<String, Object>) Json.parse(
                new String(glb, 20, jsonLen, StandardCharsets.UTF_8));
        Map<String, Object> animation =
                (Map<String, Object>) ((List<Object>) root.get("animations")).get(0);
        List<Object> samplers = (List<Object>) animation.get("samplers");
        for(Object value : samplers)
            ((Map<String, Object>) value).put("interpolation", "STEP");

        int binHeader = 20 + jsonLen;
        int binLen = le32(glb, binHeader);
        byte[] bin = java.util.Arrays.copyOfRange(glb, binHeader + 8, binHeader + 8 + binLen);
        if(scale != null) {
            Map<String, Object> inputSampler = (Map<String, Object>) samplers.get(0);
            int input = ((Number) inputSampler.get("input")).intValue();
            List<Object> accessors = (List<Object>) root.get("accessors");
            int count = ((Number) ((Map<String, Object>) accessors.get(input)).get("count")).intValue();
            int timeLength = count * Float.BYTES;
            int scaleLength = count * 3 * Float.BYTES;
            int timeOffset = (((Number) ((Map<String, Object>)
                    ((List<Object>) root.get("buffers")).get(0)).get("byteLength")).intValue() + 3) & ~3;
            int scaleOffset = (timeOffset + timeLength + 3) & ~3;
            bin = java.util.Arrays.copyOf(bin, scaleOffset + scaleLength);
            for(int i = 0; i < count; i++) {
                float time = count == 1 ? 0 : scaleEndTime * i / (count - 1);
                int bits = Float.floatToIntBits(time);
                for(int b = 0; b < 4; b++)
                    bin[timeOffset + i * 4 + b] = (byte) (bits >>> (b * 8));
            }
            for(int i = 0; i < count * 3; i++) {
                int bits = Float.floatToIntBits(scale);
                for(int b = 0; b < 4; b++)
                    bin[scaleOffset + i * 4 + b] = (byte) (bits >>> (b * 8));
            }
            List<Object> views = (List<Object>) root.get("bufferViews");
            int timeView = views.size();
            views.add(new java.util.LinkedHashMap<>(Map.of(
                    "buffer", 0, "byteOffset", timeOffset, "byteLength", timeLength)));
            int scaleView = views.size();
            views.add(new java.util.LinkedHashMap<>(Map.of(
                    "buffer", 0, "byteOffset", scaleOffset, "byteLength", scaleLength)));
            int timeAccessor = accessors.size();
            accessors.add(new java.util.LinkedHashMap<>(Map.of(
                    "bufferView", timeView, "componentType", 5126, "count", count, "type", "SCALAR")));
            int scaleAccessor = accessors.size();
            accessors.add(new java.util.LinkedHashMap<>(Map.of(
                    "bufferView", scaleView, "componentType", 5126, "count", count, "type", "VEC3")));
            int sampler = samplers.size();
            samplers.add(new java.util.LinkedHashMap<>(Map.of(
                    "input", timeAccessor, "output", scaleAccessor, "interpolation", "STEP")));
            int rootNode = -1;
            List<Object> nodes = (List<Object>) root.get("nodes");
            for(int i = 0; i < nodes.size(); i++)
                if("root".equals(((Map<String, Object>) nodes.get(i)).get("name")))
                    rootNode = i;
            assertTrue(rootNode >= 0);
            ((List<Object>) animation.get("channels")).add(new java.util.LinkedHashMap<>(Map.of(
                    "sampler", sampler, "target", new java.util.LinkedHashMap<>(Map.of(
                            "node", rootNode, "path", "scale")))));
            ((Map<String, Object>) ((List<Object>) root.get("buffers")).get(0))
                    .put("byteLength", scaleOffset + scaleLength);
        }

        byte[] json = pad(Json.write(root).getBytes(StandardCharsets.UTF_8), (byte) 0x20);
        byte[] binPadded = pad(bin, (byte) 0);
        MessageWriter w = new MessageWriter();
        w.int32(0x46546C67).int32(2).int32(12 + 8 + json.length + 8 + binPadded.length);
        w.int32(json.length).int32(0x4E4F534A).bytes(json);
        w.int32(binPadded.length).int32(0x004E4942).bytes(binPadded);
        return w.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static byte[] moveAnimationEndTime(byte[] glb, float time) {
        byte[] out = glb.clone();
        int jsonLen = le32(out, 12);
        Map<String, Object> root = (Map<String, Object>) Json.parse(
                new String(out, 20, jsonLen, StandardCharsets.UTF_8));
        Map<String, Object> animation =
                (Map<String, Object>) ((List<Object>) root.get("animations")).get(0);
        List<Object> samplers = (List<Object>) animation.get("samplers");
        List<Object> accessors = (List<Object>) root.get("accessors");
        List<Object> views = (List<Object>) root.get("bufferViews");
        java.util.Set<Integer> changed = new java.util.HashSet<>();
        int binStart = 20 + jsonLen + 8;
        for(Object value : samplers) {
            int input = ((Number) ((Map<String, Object>) value).get("input")).intValue();
            if(!changed.add(input))
                continue;
            Map<String, Object> accessor = (Map<String, Object>) accessors.get(input);
            int count = ((Number) accessor.get("count")).intValue();
            Map<String, Object> view =
                    (Map<String, Object>) views.get(((Number) accessor.get("bufferView")).intValue());
            int offset = binStart + ((Number) view.getOrDefault("byteOffset", 0)).intValue()
                    + ((Number) accessor.getOrDefault("byteOffset", 0)).intValue()
                    + (count - 1) * Float.BYTES;
            int bits = Float.floatToIntBits(time);
            for(int b = 0; b < 4; b++)
                out[offset + b] = (byte) (bits >>> (b * 8));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static byte[] removeBoneChannels(byte[] glb, String bone) {
        int jsonLen = le32(glb, 12);
        Map<String, Object> root = (Map<String, Object>) Json.parse(
                new String(glb, 20, jsonLen, StandardCharsets.UTF_8));
        List<Object> nodes = (List<Object>) root.get("nodes");
        for(Object animationValue : (List<Object>) root.get("animations")) {
            Map<String, Object> animation = (Map<String, Object>) animationValue;
            List<Object> channels = (List<Object>) animation.get("channels");
            channels.removeIf(value -> {
                Map<String, Object> target =
                        (Map<String, Object>) ((Map<String, Object>) value).get("target");
                int node = ((Number) target.get("node")).intValue();
                return bone.equals(((Map<String, Object>) nodes.get(node)).get("name"));
            });
        }
        byte[] json = pad(Json.write(root).getBytes(StandardCharsets.UTF_8), (byte) 0x20);
        int binHeader = 20 + jsonLen;
        int binLen = le32(glb, binHeader);
        byte[] bin = java.util.Arrays.copyOfRange(glb, binHeader + 8, binHeader + 8 + binLen);
        MessageWriter w = new MessageWriter();
        w.int32(0x46546C67).int32(2).int32(12 + 8 + json.length + 8 + bin.length);
        w.int32(json.length).int32(0x4E4F534A).bytes(json);
        w.int32(bin.length).int32(0x004E4942).bytes(bin);
        return w.toByteArray();
    }

    /* ---------------------------------------------------------------- tests */

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
    void unchangedSkanImportKeepsOriginalResourceBytes() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootWithEffect(3)));
        animation.layers.add(new Layer("plparts", new byte[]{1, 2, 3}));
        byte[] original = animation.serialize();
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result = GltfImport.rebuildSkan(original, glb);

        assertEquals(0, result.changed);
        assertEquals(1, result.unchanged);
        assertArrayEquals(original, result.res);
    }

    @Test
    void nearUnitQuaternionDoesNotCreateFalseSkanEdit() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootFormatZeroNearUnitAxis(-1)));
        byte[] original = animation.serialize();
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result = GltfImport.rebuildSkan(original, glb);

        assertEquals(0, result.changed);
        assertArrayEquals(original, result.res);
    }

    @Test
    void duplicateSkanIdsRoundTripAndEditByLayer() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootTwoFrames(0, 1)));
        animation.layers.add(new Layer("skan", skanRootTwoFrames(0, 2)));
        byte[] firstLayer = animation.layers.get(0).data.clone();
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;
        glb = moveAnimationTranslation(glb, "skan_0_layer_1", "root", 1.25f);

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(animation.serialize(), glb);
        ResContainer rebuilt = ResContainer.parse(result.res);
        SkanInfo second = SkanInfo.parse(rebuilt.layers.get(1).data);

        assertEquals(1, result.changed);
        assertEquals(1, result.unchanged);
        assertArrayEquals(firstLayer, rebuilt.layers.get(0).data);
        assertEquals(1.25f, second.tracks.get(0).trans[0][0], 1e-3);
    }

    @Test
    void changedSkanImportUpdatesTrackAndPreservesEffectPayload() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        byte[] originalSkan = skanRootWithEffect(3);
        animation.layers.add(new Layer("skan", originalSkan));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;
        glb = moveAnimationTranslation(glb, "skan_3", "root", 1.25f);

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(animation.serialize(), glb);
        SkanInfo before = SkanInfo.parse(originalSkan);
        SkanInfo after = SkanInfo.parse(ResContainer.parse(result.res).layers.get(0).data);

        assertEquals(1, result.changed);
        assertEquals(0, result.unchanged);
        assertEquals(1.25f, after.tracks.get(0).trans[0][0], 1e-3);
        assertArrayEquals(before.fxTracks.get(0).rawPayload, after.fxTracks.get(0).rawPayload);
    }

    @Test
    void changedCombinedSkanSplitsTracksBackIntoOwningLayers() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanBoneTwoFrames(0, "root", 1)));
        animation.layers.add(new Layer("skan", skanBoneTwoFrames(1, "tip", 2)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;
        glb = moveAnimationTranslation(glb, "skan_combined", "tip", 1.25f);

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(animation.serialize(), glb);
        ResContainer rebuilt = ResContainer.parse(result.res);
        SkanInfo root = SkanInfo.parse(rebuilt.layers.get(0).data);
        SkanInfo tip = SkanInfo.parse(rebuilt.layers.get(1).data);

        assertEquals(1, result.changed);
        assertEquals(1, result.unchanged);
        assertEquals("root", root.tracks.get(0).bone);
        assertEquals(0f, root.tracks.get(0).trans[0][0], 1e-4);
        assertEquals("tip", tip.tracks.get(0).bone);
        assertEquals(1.25f, tip.tracks.get(0).trans[0][0], 1e-3);
    }

    @Test
    void untouchedCombinedSkanDoesNotOverrideIndividualEdit() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanBoneTwoFrames(0, "root", 1)));
        animation.layers.add(new Layer("skan", skanBoneTwoFrames(1, "tip", 2)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;
        glb = moveAnimationTranslation(glb, "skan_1", "tip", 1.25f);

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(animation.serialize(), glb);
        SkanInfo tip = SkanInfo.parse(ResContainer.parse(result.res).layers.get(1).data);

        assertEquals(1, result.changed);
        assertEquals(1.25f, tip.tracks.get(0).trans[0][0], 1e-3);
    }

    @Test
    void editingCombinedAndIndividualSkanActionsIsRejected() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanBoneTwoFrames(0, "root", 1)));
        animation.layers.add(new Layer("skan", skanBoneTwoFrames(1, "tip", 2)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;
        glb = moveAnimationTranslation(glb, "skan_combined", "root", 1);
        byte[] editedGlb = moveAnimationTranslation(glb, "skan_1", "tip", 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GltfImport.rebuildSkan(animation.serialize(), editedGlb));

        assertTrue(error.getMessage().contains("both skan_combined and an individual"));
    }

    @Test
    void combinedDurationEditWithAnyEffectTrackIsRejected() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootWithEffect(0)));
        animation.layers.add(new Layer("skan", skanBoneTwoFrames(1, "tip", 2)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;
        byte[] editedGlb = moveAnimationEndTime(glb, 2);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GltfImport.rebuildSkan(animation.serialize(), editedGlb));

        assertTrue(error.getMessage().contains("control/effect tracks"));
    }

    @Test
    void constantStepChannelsAndIdentityScaleAreAccepted() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootTwoFrames(3, 0)));
        byte[] original = animation.serialize();
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(original, stepAnimation(glb, 1f));

        assertEquals(0, result.changed);
        assertArrayEquals(original, result.res);
    }

    @Test
    void identityScaleTimelineDoesNotChangeClipLength() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootTwoFrames(3, 0)));
        byte[] original = animation.serialize();
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(original, stepAnimation(glb, 1f, 2f));

        assertEquals(0, result.changed);
        assertArrayEquals(original, result.res);
    }

    @Test
    void nonconstantStepMotionIsRejected() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootTwoFrames(3, 1)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GltfImport.rebuildSkan(animation.serialize(), stepAnimation(glb, null)));

        assertTrue(error.getMessage().contains("nonconstant STEP"));
    }

    @Test
    void nonidentityScaleChannelIsRejected() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootTwoFrames(3, 0)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GltfImport.rebuildSkan(animation.serialize(), stepAnimation(glb, 1.1f)));

        assertTrue(error.getMessage().contains("scale edits"));
    }

    @Test
    void extendingLatestKeyframeChangesClipLength() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootTwoFrames(3, 1)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(animation.serialize(), moveAnimationEndTime(glb, 2));
        SkanInfo rebuilt = SkanInfo.parse(ResContainer.parse(result.res).layers.get(0).data);

        assertEquals(1, result.changed);
        assertEquals(2f, rebuilt.len, 1e-6);
        assertEquals(2f, rebuilt.tracks.get(0).times[1], 1e-4);
    }

    @Test
    void shorteningLatestKeyframeChangesClipLength() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootTwoFrames(3, 1)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(animation.serialize(), moveAnimationEndTime(glb, 0.5f));
        SkanInfo rebuilt = SkanInfo.parse(ResContainer.parse(result.res).layers.get(0).data);

        assertEquals(0.5f, rebuilt.len, 1e-6);
        assertEquals(0.5f, rebuilt.tracks.get(0).times[1], 1e-4);
    }

    @Test
    void formatZeroClipLengthCanBeExtended() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootFormatZero(3)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(animation.serialize(), moveAnimationEndTime(glb, 2));
        SkanInfo rebuilt = SkanInfo.parse(ResContainer.parse(result.res).layers.get(0).data);

        assertEquals(0, rebuilt.fmt);
        assertEquals(2f, rebuilt.len, 1e-6);
        assertEquals(2f, rebuilt.tracks.get(0).times[1], 1e-6);
    }

    @Test
    void unchangedTrailingDurationRemainsByteIdentical() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootTwoFrames(3, 1, 2, 0x8000)));
        byte[] original = animation.serialize();
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result = GltfImport.rebuildSkan(original, glb);

        assertEquals(0, result.changed);
        assertArrayEquals(original, result.res);
    }

    @Test
    void syntheticStaticEndKeyRemainsByteIdentical() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootWithEffect(3)));
        byte[] original = animation.serialize();
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result = GltfImport.rebuildSkan(original, glb);

        assertEquals(0, result.changed);
        assertArrayEquals(original, result.res);
    }

    @Test
    void zeroLengthStaticEditKeepsZeroDuration() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", zeroLengthSkan(3)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;
        glb = moveAnimationTranslation(glb, "skan_3", "root", 1.25f);

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(animation.serialize(), glb);
        SkanInfo rebuilt = SkanInfo.parse(ResContainer.parse(result.res).layers.get(0).data);

        assertEquals(1, result.changed);
        assertEquals(0f, rebuilt.len);
        assertEquals(1, rebuilt.tracks.get(0).frames);
        assertEquals(1.25f, rebuilt.tracks.get(0).trans[0][0], 1e-3);
    }

    @Test
    void untouchedZeroLengthSkanRemainsByteIdentical() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", zeroLengthSkan(3)));
        byte[] original = animation.serialize();
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result = GltfImport.rebuildSkan(original, glb);

        assertEquals(0, result.changed);
        assertArrayEquals(original, result.res);
    }

    @Test
    void minorBlenderFrameRoundingDoesNotChangeClipLength() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootTwoFrames(3, 1)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(animation.serialize(), moveAnimationEndTime(glb, 0.99f));
        SkanInfo rebuilt = SkanInfo.parse(ResContainer.parse(result.res).layers.get(0).data);

        assertEquals(1f, rebuilt.len, 1e-6);
        assertEquals(0.99f, rebuilt.tracks.get(0).times[1], 1e-4);
    }

    @Test
    void durationChangeWithEffectTrackIsRejected() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanRootWithEffect(3)));
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GltfImport.rebuildSkan(animation.serialize(), moveAnimationEndTime(glb, 2)));

        assertTrue(error.getMessage().contains("control/effect tracks"));
    }

    @Test
    void omittedBoneChannelsDoNotShortenClip() {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        model.layers.add(new Layer("mesh", mesh(-1)));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skel2()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", skanTwoBones(3)));
        byte[] original = animation.serialize();
        byte[] glb = GltfExport.toGlb(model, skeleton, animation, "anim.res").glb;

        GltfImport.AnimationRebuildResult result =
                GltfImport.rebuildSkan(original, removeBoneChannels(glb, "tip"));

        assertEquals(0, result.changed);
        assertArrayEquals(original, result.res);
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
        return geomGlb(pos, nrm, tex, indices, null);
    }

    private static byte[] geomGlb(float[] pos, float[] nrm, float[] tex, int[] indices,
                                  String materialName) {
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
        String material = materialName == null ? ""
                : "\"materials\":[{\"name\":\"" + materialName + "\"}],";
        String primitiveMaterial = materialName == null ? "" : ",\"material\":0";
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
                + material
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},"
                + "\"indices\":3" + primitiveMaterial + "}]}]}";
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

    private static byte[] vbufFormats(String posFormat, String nrmFormat, String texFormat) {
        MessageWriter w = new MessageWriter();
        w.uint8(0).uint16(3);
        formattedZeros(w, "pos2", posFormat, 9);
        formattedZeros(w, "nrm2", nrmFormat, 9);
        formattedZeros(w, "tex2", texFormat, 6);
        return w.toByteArray();
    }

    private static void formattedZeros(MessageWriter w, String name, String format, int count) {
        w.string(name).uint8(1).string(format);
        switch(format) {
            case "f4": for(int i = 0; i < count; i++) w.float32(0); break;
            case "f1": for(int i = 0; i < count; i++) w.int8(0); break;
            case "sf9995": for(int i = 0; i < count / 3; i++) w.int32(0); break;
            case "rn4":
                w.float32(0).float32(0);
                for(int i = 0; i < count; i++) w.int32(0);
                break;
            case "rn2":
                w.float32(0).float32(0);
                for(int i = 0; i < count; i++) w.uint16(0);
                break;
            case "rn1":
                w.float32(0).float32(0);
                for(int i = 0; i < count; i++) w.uint8(0);
                break;
            case "uvech": for(int i = 0; i < count / 3; i++) w.uint8(0); break;
            case "uvec1": for(int i = 0; i < count / 3; i++) w.int8(0).int8(0); break;
            default: throw new AssertionError(format);
        }
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

    @Test
    void rebuildSupportsAllPreviouslyReadOnlyVertexFormats() {
        String[][] formats = {
                {"f1", "uvech", "rn1"},
                {"sf9995", "uvec1", "rn2"},
                {"f4", "uvec1", "rn4"}
        };
        float[] pos = {0, 0, 0,  1, 0, 0,  0, 1, 0};
        float[] nrm = {0, 0, 1,  0, 0, 1,  0, 0, 1};
        float[] tex = {0, 0,  1, 0,  0, 1};

        for(String[] format : formats) {
            ResContainer res = new ResContainer(7);
            res.layers.add(new Layer("vbuf2",
                    vbufFormats(format[0], format[1], format[2])));
            res.layers.add(new Layer("mesh", mesh(-1)));

            GltfImport.RebuildResult rebuilt = GltfImport.rebuild(res.serialize(),
                    geomGlb(pos, nrm, tex, new int[]{0, 1, 2}));
            Vbuf2Codec codec = Vbuf2Codec.parse(
                    vbufLayer(ResContainer.parse(rebuilt.res)));

            assertEquals(format[0], attrFormat(codec.attr("pos")));
            assertEquals(format[1], attrFormat(codec.attr("nrm")));
            assertEquals(format[2], attrFormat(codec.attr("tex")));
            assertEquals(1.0f, codec.decodeAttr("pos")[3],
                    format[0].equals("f1") ? 0.13f : 0.01f);
            assertEquals(-1.0f, codec.decodeAttr("nrm")[1], 0.01f);
            assertEquals(1.0f, codec.decodeAttr("tex")[2],
                    format[2].equals("rn1") ? 0.01f : 0.001f);
        }
    }

    private static String attrFormat(Vbuf2Codec.Attr attr) {
        MessageReader reader = new MessageReader(attr.data);
        reader.uint8();
        return reader.string();
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

    @Test
    void rebuildRecoversModernHeadersFromOlderMergedExport() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(3)));
        byte[] meshA = modernMesh(2, 0);
        byte[] meshB = modernMesh(0, 2);
        res.layers.add(new Layer("mesh", meshA));
        res.layers.add(new Layer("mesh", meshB));

        float[] pos = {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0};
        float[] nrm = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        float[] tex = {0, 0, 1, 0, 0, 1, 1, 1};
        int[] indices = {0, 1, 2, 1, 3, 2};
        byte[] oldExport = geomGlb(pos, nrm, tex, indices, "rfmat_-1");

        ResContainer out = ResContainer.parse(GltfImport.rebuild(res.serialize(), oldExport).res);
        List<MeshInfo> meshes = new java.util.ArrayList<>();
        for(Layer layer : out.layers)
            if(layer.name.equals("mesh"))
                meshes.add(MeshInfo.parse(layer.data));

        assertEquals(2, meshes.size());
        assertTrue(meshes.get(0).modern);
        assertEquals(2, meshes.get(0).matid);
        assertEquals(0, meshes.get(0).ref);
        assertEquals(0, meshes.get(1).matid);
        assertEquals(2, meshes.get(1).ref);
        assertArrayEquals(MeshInfo.parse(meshA).modernInfo, meshes.get(0).modernInfo);
        assertArrayEquals(MeshInfo.parse(meshB).modernInfo, meshes.get(1).modernInfo);
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
        return geomGlbMorph(pos, nrm, tex, indices, morph, null);
    }

    private static byte[] geomGlbMorph(float[] pos, float[] nrm, float[] tex, int[] indices,
                                       float[] morph, String extraJson) {
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
                + "\"indices\":3,\"targets\":[{\"POSITION\":4}]}]}]"
                + (extraJson == null ? "" : "," + extraJson) + "}";
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

    @Test
    void rebuildAppliesNodeRotationAndScaleToMorphDeltasWithoutTranslation() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("vbuf2", vbufF4(3)));
        res.layers.add(new Layer("mesh", mesh(-1)));
        res.layers.add(new Layer("manim", manim1(0, 1.0f)));

        float[] pos = {0, 0, 0,  1, 0, 0,  0, 1, 0};
        float[] nrm = {0, 0, 1,  0, 0, 1,  0, 0, 1};
        float[] tex = {0, 0,  1, 0,  0, 1};
        int[] indices = {0, 1, 2};
        float[] morph = {0, 0, 0,  0.5f, 1.0f, -0.5f,  0, 0, 0};
        String nodes = "\"nodes\":[{\"mesh\":0,\"translation\":[10,20,30],"
                + "\"rotation\":[0,0,0.7071067811865476,0.7071067811865476],"
                + "\"scale\":[2,3,4]}],\"scenes\":[{\"nodes\":[0]}],\"scene\":0";

        GltfImport.RebuildResult rebuilt = GltfImport.rebuild(res.serialize(),
                geomGlbMorph(pos, nrm, tex, indices, morph, nodes));
        MeshAnimInfo.Frame frame = MeshAnimInfo.parse(
                manimLayer(ResContainer.parse(rebuilt.res))).frames.get(0);
        int vertex = -1;
        for(int i = 0; i < frame.idx.length; i++)
            if(frame.idx[i] == 1)
                vertex = i;

        assertTrue(vertex >= 0);
        // Scale -> (1,3,-2), rotate +90 degrees around glTF Z -> (-3,1,-2),
        // then glTF Y-up -> Haven Z-up gives (-3,2,1). Translation must not apply.
        assertEquals(-3f, frame.pos[vertex * 3], 1e-2f);
        assertEquals(2f, frame.pos[vertex * 3 + 1], 1e-2f);
        assertEquals(1f, frame.pos[vertex * 3 + 2], 1e-2f);
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
    void rebuildKeepsSkelByteIdenticalWhenUnchanged() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("skel", skel2()));
        res.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();

        GltfImport.RebuildResult r = GltfImport.rebuild(orig, GltfExport.toGlb(res, "rig.res").glb);
        assertFalse(r.skel, "an unchanged skeleton must not be re-encoded on rebuild");
        assertArrayEquals(skelLayer(res), skelLayer(ResContainer.parse(r.res)),
                "a rebuild that leaves the skeleton untouched keeps the skel layer byte-identical");
    }

    @Test
    void rebuildReposesEditedSkelBone() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("skel", skel2()));
        res.layers.add(new Layer("vbuf2", vbufBones2("f4")));
        res.layers.add(new Layer("mesh", mesh(-1)));
        byte[] orig = res.serialize();

        byte[] glb = moveBoneInGlb(GltfExport.toGlb(res, "rig.res").glb, "tip", 3, 0, 0);
        GltfImport.RebuildResult r = GltfImport.rebuild(orig, glb);
        assertTrue(r.skel, "moving a bone must re-pose the skeleton on rebuild");
        SkelInfo.Bone tip = boneByName(SkelInfo.parse(skelLayer(ResContainer.parse(r.res))), "tip");
        assertEquals(3f, tip.px, 1e-3f, "tip (orig x=0) should move to x=3 after rebuild re-posing");
        assertEquals(1f, tip.pz, 1e-3f, "tip's other coords stay");
    }

}
