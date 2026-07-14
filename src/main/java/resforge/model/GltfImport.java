package resforge.model;

import resforge.io.Json;
import resforge.io.MessageWriter;
import resforge.layers.MeshAnimInfo;
import resforge.layers.MeshInfo;
import resforge.layers.SkanInfo;
import resforge.layers.SkelInfo;
import resforge.res.Layer;
import resforge.res.ResContainer;
import resforge.vbuf.Vbuf2Codec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds a resource's geometry from an edited binary glTF ({@code .glb}) — the
 * "edit in Blender, then bring it back" half of the 3D round-trip. Unlike a
 * byte-exact patch, this <em>regenerates</em> the geometry, so you can add, remove
 * or re-topologize vertices, faces and whole parts.
 *
 * <p>It re-encodes the {@code vbuf2} at the glTF's own vertex count — positions,
 * normals and both UV sets re-quantised into each attribute's original on-wire
 * format, Y-up glTF coordinates converted back to Haven's Z-up — writes a fresh
 * triangle {@code mesh} (one submesh per glTF primitive, its part id recovered from
 * the {@code rfmat_<matid>} material name), rebuilds {@code bones2}/{@code bones}
 * skinning weights and {@code manim} morph shapes, recomputes the tangent basis for
 * normal-mapped models, and re-poses the {@code skel} skeleton if a bone moved. Every
 * other layer (materials, textures, code, …) is carried over unchanged.
 *
 * <p>Because it regenerates rather than patches, it needs no per-vertex ids and is
 * not byte-lossless, so edits are validated in-game.
 */
public final class GltfImport {
    private static final float DURATION_EDIT_TOLERANCE = 0.02f;
    private static final float STATIC_EDIT_DURATION = 1f;

    private GltfImport() {
    }

    /** A decoded glTF document (JSON tree + the binary chunk). */
    private static final class Glb {
        final Map<String, Object> root;
        final byte[] data;
        final int binStart;

        Glb(Map<String, Object> root, byte[] data, int binStart) {
            this.root = root;
            this.data = data;
            this.binStart = binStart;
        }
    }

    public static final class RebuildResult {
        public final byte[] res;
        public final int vertices, triangles;
        public final boolean skinned, skel;

        RebuildResult(byte[] res, int vertices, int triangles, boolean skinned, boolean skel) {
            this.res = res;
            this.vertices = vertices;
            this.triangles = triangles;
            this.skinned = skinned;
            this.skel = skel;
        }
    }

    public static final class AnimationRebuildResult {
        public final byte[] res;
        public final int changed, unchanged;

        AnimationRebuildResult(byte[] res, int changed, int unchanged) {
            this.res = res;
            this.changed = changed;
            this.unchanged = unchanged;
        }
    }

    /**
     * Rebuilds skeletal-animation layers from named glTF actions. Missing actions
     * leave their original layer untouched; actions that are present must provide
     * paired translation/rotation channels for every edited bone.
     */
    @SuppressWarnings("unchecked")
    public static AnimationRebuildResult rebuildSkan(byte[] origRes, byte[] glb) {
        Glb g = parseGlb(glb);
        ResContainer res = ResContainer.parse(origRes);
        List<Object> animations = (List<Object>) g.root.get("animations");
        if(animations == null || animations.isEmpty())
            throw new IllegalArgumentException("the glTF contains no animation actions");

        Map<SkanKey, Map<String, Object>> byLayer = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> legacyById = new LinkedHashMap<>();
        for(Object value : animations) {
            Map<String, Object> animation = (Map<String, Object>) value;
            Integer id = skanId(animation);
            if(id == null)
                continue;
            Integer layer = skanLayer(animation);
            if(layer != null) {
                SkanKey key = new SkanKey(id, layer);
                if(byLayer.putIfAbsent(key, animation) != null)
                    throw new IllegalArgumentException("the glTF contains multiple actions for skan id "
                            + id + " at layer " + layer);
            } else if(legacyById.putIfAbsent(id, animation) != null) {
                throw new IllegalArgumentException("the glTF contains multiple legacy actions for skan id "
                        + id + " without layer metadata");
            }
        }
        if(byLayer.isEmpty() && legacyById.isEmpty())
            throw new IllegalArgumentException("the glTF contains no actions named skan_<id>");

        int changed = 0, unchanged = 0, layers = 0, skanLayer = 0;
        for(int i = 0; i < res.layers.size(); i++) {
            Layer layer = res.layers.get(i);
            if(!layer.name.equals("skan"))
                continue;
            layers++;
            SkanInfo original = SkanInfo.parse(layer.data);
            if(!original.recognized)
                throw new IllegalArgumentException("couldn't decode skan layer " + i);
            Map<String, Object> animation = byLayer.get(new SkanKey(original.id, skanLayer++));
            if(animation == null)
                animation = legacyById.get(original.id);
            if(animation == null) {
                unchanged++;
                continue;
            }
            SkanEdit edit = readSkanTracks(g, animation, original);
            boolean lengthChanged = Math.abs(edit.length - original.len) > 1e-4f;
            if(lengthChanged && !original.fxTracks.isEmpty())
                throw new IllegalArgumentException("cannot change skan_" + original.id
                        + " duration while it has control/effect tracks");
            if(!lengthChanged && sameTracks(original.tracks, edit.tracks)) {
                unchanged++;
                continue;
            }
            SkanInfo edited = new SkanInfo();
            edited.id = original.id;
            edited.fmt = original.fmt;
            edited.mode = original.mode;
            edited.len = edit.length;
            edited.nspeed = original.nspeed;
            edited.tracks.addAll(edit.tracks);
            edited.fxTracks.addAll(original.fxTracks);
            res.layers.set(i, new Layer("skan", SkanInfo.encode(edited)));
            changed++;
        }
        if(layers == 0)
            throw new IllegalArgumentException("the resource contains no skan layers");
        return new AnimationRebuildResult(res.serialize(), changed, unchanged);
    }

    private record SkanKey(int id, int layer) {
    }

    @SuppressWarnings("unchecked")
    private static Integer skanId(Map<String, Object> animation) {
        Object extrasValue = animation.get("extras");
        if(extrasValue instanceof Map) {
            Object id = ((Map<String, Object>) extrasValue).get("resforgeSkanId");
            if(id instanceof Number)
                return ((Number) id).intValue();
        }
        Object nameValue = animation.get("name");
        if(!(nameValue instanceof String))
            return null;
        String name = (String) nameValue;
        if(!name.startsWith("skan_"))
            return null;
        String value = name.substring(5);
        int marker = value.indexOf("_layer_");
        if(marker >= 0)
            value = value.substring(0, marker);
        try {
            return Integer.parseInt(value);
        } catch(NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Integer skanLayer(Map<String, Object> animation) {
        Object extrasValue = animation.get("extras");
        if(extrasValue instanceof Map) {
            Object layer = ((Map<String, Object>) extrasValue).get("resforgeSkanLayer");
            if(layer instanceof Number)
                return ((Number) layer).intValue();
        }
        Object nameValue = animation.get("name");
        if(!(nameValue instanceof String))
            return null;
        String name = (String) nameValue;
        int marker = name.lastIndexOf("_layer_");
        if(marker < 0)
            return null;
        try {
            return Integer.parseInt(name.substring(marker + 7));
        } catch(NumberFormatException e) {
            return null;
        }
    }

    private static final class AnimChannel {
        final float[] times, values;
        final int components;

        AnimChannel(float[] times, float[] values, int components) {
            this.times = times;
            this.values = values;
            this.components = components;
        }
    }

    private static final class BoneChannels {
        final int node;
        AnimChannel translation, rotation;

        BoneChannels(int node) {
            this.node = node;
        }
    }

    private static final class SkanEdit {
        final List<SkanInfo.Track> tracks;
        final float length;

        SkanEdit(List<SkanInfo.Track> tracks, float length) {
            this.tracks = tracks;
            this.length = length;
        }
    }

    @SuppressWarnings("unchecked")
    private static SkanEdit readSkanTracks(Glb g, Map<String, Object> animation,
                                           SkanInfo original) {
        List<Object> nodes = (List<Object>) g.root.get("nodes");
        List<Object> samplers = (List<Object>) animation.get("samplers");
        List<Object> channels = (List<Object>) animation.get("channels");
        if(nodes == null || samplers == null || channels == null)
            throw new IllegalArgumentException("skan action is missing nodes, samplers, or channels");

        Map<String, BoneChannels> byBone = new LinkedHashMap<>();
        float editedMax = 0;
        for(Object value : channels) {
            Map<String, Object> channel = (Map<String, Object>) value;
            Map<String, Object> target = (Map<String, Object>) channel.get("target");
            if(target == null || !(target.get("node") instanceof Number))
                throw new IllegalArgumentException("skan action has a channel without a target bone");
            int nodeIndex = idx(target.get("node"));
            if(nodeIndex < 0 || nodeIndex >= nodes.size())
                throw new IllegalArgumentException("skan action targets missing node " + nodeIndex);
            Map<String, Object> node = (Map<String, Object>) nodes.get(nodeIndex);
            Object nameValue = node.get("name");
            if(!(nameValue instanceof String) || ((String) nameValue).isEmpty())
                throw new IllegalArgumentException("skan action targets an unnamed bone");
            String path = String.valueOf(target.get("path"));
            if(!path.equals("translation") && !path.equals("rotation") && !path.equals("scale"))
                throw new IllegalArgumentException("unsupported skan animation channel " + path);
            int samplerIndex = idx(channel.get("sampler"));
            if(samplerIndex < 0 || samplerIndex >= samplers.size())
                throw new IllegalArgumentException("skan action references missing sampler " + samplerIndex);
            Map<String, Object> sampler = (Map<String, Object>) samplers.get(samplerIndex);
            String interpolation = String.valueOf(sampler.getOrDefault("interpolation", "LINEAR"));
            if(!interpolation.equals("LINEAR") && !interpolation.equals("STEP"))
                throw new IllegalArgumentException("skan import supports only LINEAR or constant STEP "
                        + "interpolation, not " + interpolation);
            int components = path.equals("rotation") ? 4 : 3;
            float[] times = readAccessor(g, idx(sampler.get("input")), 1);
            float[] values = readAccessor(g, idx(sampler.get("output")), components);
            validateKeyframes(times, values, components, Float.POSITIVE_INFINITY, (String) nameValue);
            if(path.equals("scale")) {
                if(!identityScale(values))
                    throw new IllegalArgumentException("skan does not support bone scale edits ("
                            + nameValue + ")");
                continue;
            }
            editedMax = Math.max(editedMax, times[times.length - 1]);
            if(interpolation.equals("STEP") && !constantChannel(values, components,
                    path.equals("rotation")))
                throw new IllegalArgumentException("skan cannot represent nonconstant STEP "
                        + path + " for bone " + nameValue);
            BoneChannels pair = byBone.computeIfAbsent((String) nameValue,
                    key -> new BoneChannels(nodeIndex));
            if(pair.node != nodeIndex)
                throw new IllegalArgumentException("multiple nodes are named " + nameValue);
            AnimChannel decoded = new AnimChannel(times, values, components);
            if(path.equals("translation")) {
                if(pair.translation != null)
                    throw new IllegalArgumentException("duplicate translation channel for " + nameValue);
                pair.translation = decoded;
            } else {
                if(pair.rotation != null)
                    throw new IllegalArgumentException("duplicate rotation channel for " + nameValue);
                pair.rotation = decoded;
            }
        }

        java.util.Set<String> editedBones = new java.util.HashSet<>(byBone.keySet());
        List<SkanInfo.Track> out = new ArrayList<>();
        for(SkanInfo.Track track : original.tracks) {
            BoneChannels pair = byBone.remove(track.bone);
            SkanInfo.Track decoded = pair == null ? track : decodeTrack(nodes, track.bone, pair);
            out.add(collapseLoopClose(track, decoded, original.len));
        }
        for(Map.Entry<String, BoneChannels> entry : byBone.entrySet()) {
            SkanInfo.Track added = decodeTrack(nodes, entry.getKey(), entry.getValue());
            if(!identityTrack(added))
                out.add(added);
        }
        float originalMax = 0;
        for(SkanInfo.Track track : original.tracks)
            if(editedBones.contains(track.bone) && track.frames > 0)
                originalMax = Math.max(originalMax, track.times[track.frames - 1]);
        float length;
        if(original.len == 0) {
            length = 0;
            for(SkanInfo.Track track : out)
                if(track.frames > 0)
                    length = Math.max(length, track.times[track.frames - 1]);
        } else {
            length = (editedMax > 1e-6f
                    && Math.abs(editedMax - originalMax) > DURATION_EDIT_TOLERANCE)
                    ? editedMax : original.len;
        }
        return new SkanEdit(out, length);
    }

    private static SkanInfo.Track collapseLoopClose(SkanInfo.Track original,
                                                     SkanInfo.Track decoded,
                                                     float length) {
        float closingTime = length > 0 ? length : STATIC_EDIT_DURATION;
        if(decoded.frames != original.frames + 1
                || Math.abs(decoded.times[decoded.frames - 1] - closingTime) > 1e-4f)
            return decoded;
        int last = decoded.frames - 1;
        if(length == 0 && original.frames == 1) {
            boolean firstOriginal = sameFrame(decoded, 0, original, 0);
            boolean lastOriginal = sameFrame(decoded, last, original, 0);
            if(firstOriginal && !lastOriginal)
                return singleFrame(decoded, last);
            if(!firstOriginal && lastOriginal)
                return singleFrame(decoded, 0);
        }
        if(!sameFrame(decoded, 0, last))
            return decoded;
        return new SkanInfo.Track(decoded.bone, java.util.Arrays.copyOf(decoded.times, last),
                java.util.Arrays.copyOf(decoded.trans, last), java.util.Arrays.copyOf(decoded.rot, last));
    }

    private static SkanInfo.Track singleFrame(SkanInfo.Track track, int frame) {
        return new SkanInfo.Track(track.bone, new float[]{0},
                new float[][]{track.trans[frame]}, new float[][]{track.rot[frame]});
    }

    private static boolean sameFrame(SkanInfo.Track track, int a, int b) {
        return sameFrame(track, a, track, b);
    }

    private static boolean sameFrame(SkanInfo.Track a, int ai, SkanInfo.Track b, int bi) {
        for(int component = 0; component < 3; component++)
            if(Math.abs(a.trans[ai][component] - b.trans[bi][component]) > 1e-5f)
                return false;
        boolean same = true, negated = true;
        for(int component = 0; component < 4; component++) {
            same &= Math.abs(a.rot[ai][component] - b.rot[bi][component]) <= 1e-5f;
            negated &= Math.abs(a.rot[ai][component] + b.rot[bi][component]) <= 1e-5f;
        }
        return same || negated;
    }

    private static boolean identityScale(float[] values) {
        for(float value : values)
            if(Math.abs(value - 1f) > 1e-5f)
                return false;
        return true;
    }

    private static boolean constantChannel(float[] values, int components, boolean quaternion) {
        if(quaternion) {
            double[] first = {values[0], values[1], values[2], values[3]};
            normalizeQuat(first);
            for(int offset = components; offset < values.length; offset += components) {
                double[] frame = {values[offset], values[offset + 1],
                        values[offset + 2], values[offset + 3]};
                normalizeQuat(frame);
                double dot = Math.abs(first[0] * frame[0] + first[1] * frame[1]
                        + first[2] * frame[2] + first[3] * frame[3]);
                if(Math.toDegrees(2 * Math.acos(Math.min(1, dot))) > 1e-4)
                    return false;
            }
            return true;
        }
        for(int offset = components; offset < values.length; offset += components)
            for(int component = 0; component < components; component++)
                if(Math.abs(values[offset + component] - values[component]) > 1e-6f)
                    return false;
        return true;
    }

    private static void validateKeyframes(float[] times, float[] values, int components,
                                          float length, String bone) {
        if(times.length == 0 || values.length != times.length * components)
            throw new IllegalArgumentException("invalid animation keyframes for bone " + bone);
        float previous = -Float.MAX_VALUE;
        for(float time : times) {
            if(!Float.isFinite(time) || time < -1e-5f || time > length + 1e-4f)
                throw new IllegalArgumentException("animation keyframe for " + bone
                        + " is outside the original clip length " + length);
            if(time <= previous)
                throw new IllegalArgumentException("animation keyframe times for " + bone
                        + " are not strictly increasing");
            previous = time;
        }
        for(float value : values)
            if(!Float.isFinite(value))
                throw new IllegalArgumentException("animation for " + bone + " contains a non-finite value");
    }

    @SuppressWarnings("unchecked")
    private static SkanInfo.Track decodeTrack(List<Object> nodes, String bone, BoneChannels pair) {
        if(pair.translation == null || pair.rotation == null)
            throw new IllegalArgumentException("bone " + bone
                    + " must have both translation and rotation channels");
        Map<String, Object> node = (Map<String, Object>) nodes.get(pair.node);
        if(node.containsKey("matrix"))
            throw new IllegalArgumentException("animated bone " + bone + " uses a matrix instead of TRS");
        double[] scale = vecd(node.get("scale"), 3, 1);
        if(Math.abs(scale[0] - 1) > 1e-5 || Math.abs(scale[1] - 1) > 1e-5
                || Math.abs(scale[2] - 1) > 1e-5)
            throw new IllegalArgumentException("skan does not support bone scale edits (" + bone + ")");
        double[] bindT = vecd(node.get("translation"), 3, 0);
        double[] bindQ = vecd(node.get("rotation"), 4, Double.NaN);
        if(Double.isNaN(bindQ[3]))
            bindQ = new double[]{0, 0, 0, 1};
        normalizeQuat(bindQ);

        float[] times = unionTimes(pair.translation.times, pair.rotation.times);
        float[][] trans = new float[times.length][3];
        float[][] rot = new float[times.length][4];
        for(int i = 0; i < times.length; i++) {
            float[] tv = sample(pair.translation, times[i], false);
            for(int c = 0; c < 3; c++)
                trans[i][c] = (float) (tv[c] - bindT[c]);
            float[] rv = sample(pair.rotation, times[i], true);
            double[] absolute = {rv[0], rv[1], rv[2], rv[3]};
            normalizeQuat(absolute);
            double[] delta = quatMul(quatConjugate(bindQ), absolute);
            normalizeQuat(delta);
            rot[i][0] = (float) delta[3];
            rot[i][1] = (float) delta[0];
            rot[i][2] = (float) delta[1];
            rot[i][3] = (float) delta[2];
        }
        return new SkanInfo.Track(bone, times, trans, rot);
    }

    private static float[] unionTimes(float[] a, float[] b) {
        List<Float> values = new ArrayList<>();
        int ai = 0, bi = 0;
        while(ai < a.length || bi < b.length) {
            float value;
            if(bi >= b.length || (ai < a.length && a[ai] < b[bi] - 1e-6f))
                value = a[ai++];
            else if(ai >= a.length || b[bi] < a[ai] - 1e-6f)
                value = b[bi++];
            else {
                value = (a[ai++] + b[bi++]) * 0.5f;
            }
            values.add(value);
        }
        float[] out = new float[values.size()];
        for(int i = 0; i < out.length; i++)
            out[i] = values.get(i);
        return out;
    }

    private static float[] sample(AnimChannel channel, float time, boolean quaternion) {
        int i = 0;
        while(i + 1 < channel.times.length && channel.times[i + 1] < time)
            i++;
        if(i + 1 >= channel.times.length || time <= channel.times[i] + 1e-7f)
            return frame(channel, i);
        float alpha = (time - channel.times[i]) / (channel.times[i + 1] - channel.times[i]);
        float[] a = frame(channel, i), b = frame(channel, i + 1);
        return quaternion ? slerp(a, b, alpha) : lerp(a, b, alpha);
    }

    private static float[] frame(AnimChannel channel, int index) {
        float[] out = new float[channel.components];
        System.arraycopy(channel.values, index * channel.components, out, 0, channel.components);
        return out;
    }

    private static float[] lerp(float[] a, float[] b, float alpha) {
        float[] out = new float[a.length];
        for(int i = 0; i < out.length; i++)
            out[i] = a[i] + (b[i] - a[i]) * alpha;
        return out;
    }

    /** glTF quaternion interpolation, input/output order xyzw. */
    private static float[] slerp(float[] a, float[] b, float alpha) {
        double dot = 0;
        for(int i = 0; i < 4; i++)
            dot += (double) a[i] * b[i];
        float[] end = b.clone();
        if(dot < 0) {
            dot = -dot;
            for(int i = 0; i < 4; i++)
                end[i] = -end[i];
        }
        double wa, wb;
        if(dot > 0.9995) {
            wa = 1 - alpha;
            wb = alpha;
        } else {
            double theta = Math.acos(Math.max(-1, Math.min(1, dot)));
            double sin = Math.sin(theta);
            wa = Math.sin((1 - alpha) * theta) / sin;
            wb = Math.sin(alpha * theta) / sin;
        }
        float[] out = new float[4];
        double n = 0;
        for(int i = 0; i < 4; i++) {
            out[i] = (float) (wa * a[i] + wb * end[i]);
            n += (double) out[i] * out[i];
        }
        n = Math.sqrt(n);
        for(int i = 0; i < 4; i++)
            out[i] /= (float) n;
        return out;
    }

    private static double[] quatConjugate(double[] q) {
        return new double[]{-q[0], -q[1], -q[2], q[3]};
    }

    /** Quaternion product in xyzw order. */
    private static double[] quatMul(double[] a, double[] b) {
        return new double[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        };
    }

    private static void normalizeQuat(double[] q) {
        double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if(!Double.isFinite(n) || n == 0)
            throw new IllegalArgumentException("animation contains an invalid quaternion");
        for(int i = 0; i < 4; i++)
            q[i] /= n;
    }

    private static boolean identityTrack(SkanInfo.Track track) {
        for(int i = 0; i < track.frames; i++) {
            if(Math.abs(track.trans[i][0]) > 1e-4 || Math.abs(track.trans[i][1]) > 1e-4
                    || Math.abs(track.trans[i][2]) > 1e-4)
                return false;
            if(Math.abs(track.rot[i][0]) < 0.999999
                    || Math.abs(track.rot[i][1]) > 1e-4 || Math.abs(track.rot[i][2]) > 1e-4
                    || Math.abs(track.rot[i][3]) > 1e-4)
                return false;
        }
        return true;
    }

    private static boolean sameTracks(List<SkanInfo.Track> a, List<SkanInfo.Track> b) {
        if(a.size() != b.size())
            return false;
        for(int i = 0; i < a.size(); i++) {
            SkanInfo.Track x = a.get(i), y = b.get(i);
            if(!x.bone.equals(y.bone) || x.frames != y.frames)
                return false;
            for(int f = 0; f < x.frames; f++) {
                if(Math.abs(x.times[f] - y.times[f]) > 1e-4)
                    return false;
                for(int c = 0; c < 3; c++)
                    if(Math.abs(x.trans[f][c] - y.trans[f][c]) > 1e-4)
                        return false;
                double dot = 0, xn = 0, yn = 0;
                for(int c = 0; c < 4; c++) {
                    dot += (double) x.rot[f][c] * y.rot[f][c];
                    xn += (double) x.rot[f][c] * x.rot[f][c];
                    yn += (double) y.rot[f][c] * y.rot[f][c];
                }
                if(xn == 0 || yn == 0)
                    return false;
                dot = Math.abs(dot) / Math.sqrt(xn * yn);
                if(Math.toDegrees(2 * Math.acos(Math.min(1, dot))) > 0.1)
                    return false;
            }
        }
        return true;
    }

    /**
     * Rebuilds a model's geometry from the glTF instead of patching it — this is the
     * path that allows <em>adding or removing</em> vertices and triangles (Blender
     * reshaping, subdividing, deleting faces…). It regenerates the {@code vbuf2}
     * (positions/normals/UVs re-quantised into the original formats), the {@code mesh}
     * triangle list, and — for skinned models — the {@code bones2}/{@code bones}
     * weights, all at the
     * glTF's vertex count, while keeping every other layer (textures, materials,
     * code…) and re-posing the {@code skel} skeleton if a bone moved. It does not
     * need per-vertex ids and gives
     * up byte-exactness, so it relies on in-game validation.
     *
     * <p>This version targets models with a single shared {@code vbuf2} and one or more
     * {@code mesh} submeshes, whose vertex attributes are positions/normals/UVs/
     * bone-weights. Each glTF primitive becomes a submesh, its part id recovered from
     * the material name ({@code rfmat_<matid>}); primitives are concatenated into the
     * shared vertex buffer.
     */
    public static RebuildResult rebuild(byte[] origRes, byte[] glb) {
        Glb g = parseGlb(glb);
        ResContainer res = ResContainer.parse(origRes);

        int vbufIdx = -1, vbufN = 0;
        List<Integer> meshIdxs = new ArrayList<>();
        for(int i = 0; i < res.layers.size(); i++) {
            String nm = res.layers.get(i).name;
            if(nm.equals("vbuf2")) { vbufN++; vbufIdx = i; }
            else if(nm.equals("mesh")) meshIdxs.add(i);
        }
        if(vbufN != 1 || meshIdxs.isEmpty())
            throw new IllegalArgumentException(
                    "rebuild needs one shared vbuf2 and at least one mesh; this resource has "
                            + vbufN + " vbuf2 and " + meshIdxs.size() + " mesh layers.");
        // morph layers re-encode at the new vertex count (shapes from the glTF targets,
        // timing kept from the original); the frame count must be unchanged.
        List<MeshAnimInfo> manims = new ArrayList<>();
        List<Integer> manimIdxs = new ArrayList<>();
        int totalFrames = 0;
        for(int i = 0; i < res.layers.size(); i++)
            if(res.layers.get(i).name.equals("manim")) {
                MeshAnimInfo mai = MeshAnimInfo.parse(res.layers.get(i).data);
                if(!mai.recognized)
                    throw new IllegalArgumentException("couldn't decode a manim layer to rebuild it.");
                manims.add(mai);
                manimIdxs.add(i);
                totalFrames += mai.frames.size();
            }
        boolean hasManim = !manims.isEmpty();

        byte[] origVbuf = res.layers.get(vbufIdx).data;
        Vbuf2Codec codec = Vbuf2Codec.parse(origVbuf);
        for(Vbuf2Codec.Attr at : codec.attrs) {
            String base = at.name.endsWith("2") ? at.name.substring(0, at.name.length() - 1) : at.name;
            if(!base.equals("pos") && !base.equals("nrm") && !base.equals("tex") && !base.equals("otex")
                    && !base.equals("tan") && !base.equals("bit")
                    && !at.name.equals("bones2") && !at.name.equals("bones"))
                throw new IllegalArgumentException("rebuild doesn't support the '" + at.name + "' vertex attribute yet.");
        }
        boolean hasNrm = codec.attr("nrm") != null, hasTex = codec.attr("tex") != null;
        boolean hasOtex = codec.attr("otex") != null, hasBones = codec.attr("bones") != null;
        boolean hasTan = codec.attr("tan") != null || codec.attr("bit") != null;

        // matid -> a template original mesh layer (for its id/vbufid).
        Map<Integer, MeshInfo> matidToMesh = new LinkedHashMap<>();
        int vbufId = 0;
        for(int mi : meshIdxs) {
            MeshInfo m = MeshInfo.parse(res.layers.get(mi).data);
            if(m.recognized) {
                matidToMesh.putIfAbsent(m.matid, m);
                vbufId = m.vbufid;
            }
        }
        List<Object> materials = materialsOf(g.root);

        // Concatenate each distinct vertex block (Blender splits per material; our own
        // export shares one), de-duplicating by POSITION accessor so a shared buffer
        // isn't copied per primitive.
        List<float[]> cPos = new ArrayList<>(), cNrm = new ArrayList<>(), cTex = new ArrayList<>(),
                cOtex = new ArrayList<>(), cJoints = new ArrayList<>(), cWeights = new ArrayList<>();
        Map<Integer, Integer> posOffset = new HashMap<>();
        int total = 0;
        List<int[]> meshTris = new ArrayList<>();
        List<Integer> meshMatids = new ArrayList<>();
        List<List<float[]>> tChunks = new ArrayList<>();     // morph target deltas, per target -> chunks
        int[] targetN = {-1};

        IdentityHashMap<Map<String, Object>, double[]> primMatrix = primitiveMatrices(g.root);
        for(Map<String, Object> prim : allPrimitives(g.root)) {
            Map<String, Object> a = attributesOf(prim);
            if(a == null || !a.containsKey("POSITION"))
                continue;
            int posAcc = idx(a.get("POSITION"));
            int offset;
            if(posOffset.containsKey(posAcc)) {
                offset = posOffset.get(posAcc);
            } else {
                float[] p = readAccessor(g, posAcc, 3);
                double[] m = primMatrix.get(prim);
                if(m != null)
                    applyMatrix(p, m);
                int cnt = p.length / 3;
                cPos.add(axisInvert(p));
                if(hasNrm) {
                    float[] nrm = readVec(g, a, "NORMAL", 3, "normals");
                    if(m != null)
                        applyNormalMatrix(nrm, m);
                    cNrm.add(axisInvert(nrm));
                }
                if(hasTex) cTex.add(readVec(g, a, "TEXCOORD_0", 2, "UVs"));
                if(hasOtex) cOtex.add(readVec(g, a, "TEXCOORD_1", 2, "a second UV set"));
                if(hasBones) {
                    cJoints.add(readVec(g, a, "JOINTS_0", 4, "skinning joints"));
                    cWeights.add(readVec(g, a, "WEIGHTS_0", 4, "skinning weights"));
                }
                if(hasManim)
                    readMorphChunks(g, prim, cnt, tChunks, targetN, m);
                offset = total;
                posOffset.put(posAcc, offset);
                total += cnt;
            }
            int[] tris = readIndices(g, prim);
            for(int i = 0; i < tris.length; i++)
                tris[i] += offset;
            meshTris.add(tris);
            meshMatids.add(recoverMatid(prim, materials, matidToMesh));
        }
        if(total == 0)
            throw new IllegalArgumentException("the glTF has no mesh positions to rebuild from.");
        if(total > 0xffff)
            throw new IllegalArgumentException("rebuilt mesh has " + total + " vertices, over the 65535 limit.");

        codec.num = total;
        float[] allPos = concat(cPos);
        float[] allNrm = hasNrm ? concat(cNrm) : null;
        float[] allTex = hasTex ? concat(cTex) : null;
        codec.setAttr("pos", allPos);
        if(hasNrm) codec.setAttr("nrm", allNrm);
        if(hasTex) codec.setAttr("tex", allTex);
        if(hasOtex) codec.setAttr("otex", concat(cOtex));
        if(hasBones)
            rebuildWeights(g, concat(cJoints), concat(cWeights), codec, origVbuf, total);
        if(hasTan) {
            // Haven stores tan and bit identically (verified across all sampled
            // normal-mapped models), so recompute one tangent and write it to both.
            if(allNrm == null || allTex == null)
                throw new IllegalArgumentException("rebuilding tangents needs normals and UVs, which the glTF lacks.");
            float[] tangents = computeTangents(allPos, allNrm, allTex, total, meshTris);
            if(codec.attr("tan") != null) codec.setAttr("tan", tangents);
            if(codec.attr("bit") != null) codec.setAttr("bit", tangents);
        }
        byte[] newVbuf = codec.encode();

        // Re-encode each manim at the new vertex count (shapes from the glTF targets).
        Map<Integer, byte[]> manimReplace = new HashMap<>();
        if(hasManim) {
            if(targetN[0] != totalFrames)
                throw new IllegalArgumentException(
                        "the glTF has " + targetN[0] + " shape keys but the model's morph animation has "
                                + totalFrames + " frames; rebuild can't add or remove morph frames yet.");
            float[][] combined = new float[totalFrames][];
            for(int t = 0; t < totalFrames; t++)
                combined[t] = concat(tChunks.get(t));
            int gi = 0;
            for(int m = 0; m < manims.size(); m++) {
                MeshAnimInfo mai = manims.get(m);
                int cntF = mai.frames.size();
                float[][] fd = new float[cntF][];
                for(int f = 0; f < cntF; f++)
                    fd[f] = combined[gi + f];
                manimReplace.put(manimIdxs.get(m), mai.encodeWith(fd, total, 1e-6f));
                gi += cntF;
            }
        }

        // Rebuild the layer list: vbuf2 in place, all old mesh layers replaced by the
        // new submeshes at the first mesh position, manim layers re-encoded, others kept.
        List<Layer> outLayers = new ArrayList<>();
        java.util.Set<Integer> meshSet = new java.util.HashSet<>(meshIdxs);
        boolean meshEmitted = false;
        int totalTris = 0;
        for(int i = 0; i < res.layers.size(); i++) {
            if(i == vbufIdx) {
                outLayers.add(new Layer("vbuf2", newVbuf));
            } else if(meshSet.contains(i)) {
                if(!meshEmitted) {
                    for(int s = 0; s < meshTris.size(); s++) {
                        int matid = meshMatids.get(s);
                        MeshInfo tmpl = matidToMesh.get(matid);
                        outLayers.add(new Layer("mesh", encodeMeshRaw(matid,
                                tmpl != null ? tmpl.id : -1, vbufId, meshTris.get(s))));
                        totalTris += meshTris.get(s).length / 3;
                    }
                    meshEmitted = true;
                }
            } else if(manimReplace.containsKey(i)) {
                outLayers.add(new Layer("manim", manimReplace.get(i)));
            } else {
                outLayers.add(res.layers.get(i));
            }
        }
        res.layers.clear();
        res.layers.addAll(outLayers);
        // Re-pose the skeleton too, if a bone moved (independent of the geometry rebuild).
        boolean skelChanged = applySkel(g, res);
        return new RebuildResult(res.serialize(), total, totalTris, hasBones, skelChanged);
    }

    /** Reads a required vertex attribute, with a clear error if the glTF is missing it. */
    private static float[] readVec(Glb g, Map<String, Object> a, String key, int comps, String what) {
        if(!a.containsKey(key))
            throw new IllegalArgumentException("the glTF is missing " + what + ", which this model needs.");
        return readAccessor(g, idx(a.get(key)), comps);
    }

    private static float[] concat(List<float[]> chunks) {
        int n = 0;
        for(float[] c : chunks) n += c.length;
        float[] out = new float[n];
        int o = 0;
        for(float[] c : chunks) { System.arraycopy(c, 0, out, o, c.length); o += c.length; }
        return out;
    }

    /** The matid for a primitive, recovered from its material name "rfmat_<matid>". */
    @SuppressWarnings("unchecked")
    private static int recoverMatid(Map<String, Object> prim, List<Object> materials,
                                    Map<Integer, MeshInfo> matidToMesh) {
        Object mo = prim.get("material");
        if(mo != null && materials != null) {
            int mIdx = ((Number) mo).intValue();
            if(mIdx >= 0 && mIdx < materials.size()) {
                Object nm = ((Map<String, Object>) materials.get(mIdx)).get("name");
                Integer matid = parseMatid(nm == null ? null : nm.toString());
                if(matid != null)
                    return matid;
            }
        }
        // fall back to the only original matid if there's just one
        return matidToMesh.size() == 1 ? matidToMesh.keySet().iterator().next() : -1;
    }

    /** Parses the matid out of a "rfmat_<int>" material name (tolerating Blender's ".001" suffixes). */
    private static Integer parseMatid(String name) {
        if(name == null)
            return null;
        int p = name.indexOf("rfmat_");
        if(p < 0)
            return null;
        int i = p + 6;
        int start = i;
        if(i < name.length() && name.charAt(i) == '-') i++;
        while(i < name.length() && Character.isDigit(name.charAt(i))) i++;
        if(i == start || (i == start + 1 && name.charAt(start) == '-'))
            return null;
        try {
            return Integer.parseInt(name.substring(start, i));
        } catch(NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> materialsOf(Map<String, Object> root) {
        return (List<Object>) root.get("materials");
    }

    /**
     * Recomputes per-vertex tangents from positions, normals, UVs and triangles
     * (Lengyel's method: accumulate the UV-gradient tangent over each triangle, then
     * Gram-Schmidt orthogonalise against the normal and normalise). Used for normal-
     * mapped models; Haven stores {@code bit} identical to {@code tan}, so the caller
     * writes this to both.
     */
    private static float[] computeTangents(float[] pos, float[] nrm, float[] tex, int num, List<int[]> meshTris) {
        float[] acc = new float[num * 3];
        for(int[] tris : meshTris)
            for(int i = 0; i + 2 < tris.length; i += 3)
                accumTangent(pos, tex, tris[i], tris[i + 1], tris[i + 2], acc);
        float[] out = new float[num * 3];
        for(int v = 0; v < num; v++) {
            double nx = nrm[v * 3], ny = nrm[v * 3 + 1], nz = nrm[v * 3 + 2];
            double tx = acc[v * 3], ty = acc[v * 3 + 1], tz = acc[v * 3 + 2];
            double d = tx * nx + ty * ny + tz * nz;          // Gram-Schmidt vs normal
            tx -= nx * d; ty -= ny * d; tz -= nz * d;
            double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if(len < 1e-8) {                                  // degenerate: any perpendicular to the normal
                double[] p = perp(nx, ny, nz);
                tx = p[0]; ty = p[1]; tz = p[2];
                len = 1;
            }
            out[v * 3] = (float) (tx / len);
            out[v * 3 + 1] = (float) (ty / len);
            out[v * 3 + 2] = (float) (tz / len);
        }
        return out;
    }

    private static void accumTangent(float[] pos, float[] tex, int i0, int i1, int i2, float[] acc) {
        float e1x = pos[i1 * 3] - pos[i0 * 3], e1y = pos[i1 * 3 + 1] - pos[i0 * 3 + 1], e1z = pos[i1 * 3 + 2] - pos[i0 * 3 + 2];
        float e2x = pos[i2 * 3] - pos[i0 * 3], e2y = pos[i2 * 3 + 1] - pos[i0 * 3 + 1], e2z = pos[i2 * 3 + 2] - pos[i0 * 3 + 2];
        float du1 = tex[i1 * 2] - tex[i0 * 2], dv1 = tex[i1 * 2 + 1] - tex[i0 * 2 + 1];
        float du2 = tex[i2 * 2] - tex[i0 * 2], dv2 = tex[i2 * 2 + 1] - tex[i0 * 2 + 1];
        float den = du1 * dv2 - du2 * dv1;
        if(Math.abs(den) < 1e-12f)
            return;
        float r = 1f / den;
        float tx = r * (e1x * dv2 - e2x * dv1), ty = r * (e1y * dv2 - e2y * dv1), tz = r * (e1z * dv2 - e2z * dv1);
        for(int j : new int[]{i0, i1, i2}) {
            acc[j * 3] += tx; acc[j * 3 + 1] += ty; acc[j * 3 + 2] += tz;
        }
    }

    /** Some unit vector perpendicular to (x,y,z). */
    private static double[] perp(double x, double y, double z) {
        double[] r = (Math.abs(x) <= Math.abs(y) && Math.abs(x) <= Math.abs(z))
                ? new double[]{0, -z, y} : (Math.abs(y) <= Math.abs(z))
                ? new double[]{-z, 0, x} : new double[]{-y, x, 0};
        double l = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
        if(l < 1e-12) return new double[]{1, 0, 0};
        return new double[]{r[0] / l, r[1] / l, r[2] / l};
    }

    /** Reads this primitive's morph-target POSITION deltas, applying the node's
     *  rotation/scale without translation, then axis-inverting into Haven space. */
    private static void readMorphChunks(Glb g, Map<String, Object> prim, int cnt,
                                        List<List<float[]>> tChunks, int[] targetN,
                                        double[] matrix) {
        Object tg = prim.get("targets");
        if(tg == null)
            throw new IllegalArgumentException(
                    "this model has morph animation but the glTF has no shape keys to rebuild it from.");
        List<?> targets = (List<?>) tg;
        if(targetN[0] < 0) {
            targetN[0] = targets.size();
            for(int t = 0; t < targetN[0]; t++)
                tChunks.add(new ArrayList<>());
        }
        if(targets.size() != targetN[0])
            throw new IllegalArgumentException("the glTF's parts have inconsistent shape-key counts.");
        for(int t = 0; t < targetN[0]; t++) {
            Object pa = ((Map<?, ?>) targets.get(t)).get("POSITION");
            float[] td = (pa == null) ? new float[cnt * 3]
                    : readAccessor(g, ((Number) pa).intValue(), 3);
            if(matrix != null)
                applyVectorMatrix(td, matrix);
            td = axisInvert(td);
            if(td.length != cnt * 3)
                td = java.util.Arrays.copyOf(td, cnt * 3);
            tChunks.get(t).add(td);
        }
    }

    /** Builds bones2 for a rebuilt vbuf from concatenated glTF JOINTS_0/WEIGHTS_0 (joints mapped by name). */
    private static void rebuildWeights(Glb g, float[] gj, float[] gw, Vbuf2Codec codec, byte[] origVbuf, int num) {
        Vbuf2Data sd = Vbuf2Data.parse(origVbuf);
        String[] jointNames = skinJointNames(g.root);
        if(sd == null || sd.boneNames == null || jointNames == null)
            throw new IllegalArgumentException("couldn't recover the skeleton's bone names for rebuild.");
        Map<String, Integer> nameToIdx = new HashMap<>();
        for(int i = 0; i < sd.boneNames.length; i++)
            nameToIdx.putIfAbsent(sd.boneNames[i], i);
        int[] vJoints = new int[num * 4];
        float[] vWeights = new float[num * 4];
        java.util.Arrays.fill(vJoints, -1);
        for(int v = 0; v < num; v++)
            for(int k = 0; k < 4; k++) {
                int ji = Math.round(gj[v * 4 + k]);
                float wt = gw[v * 4 + k];
                if(wt > 0 && ji >= 0 && ji < jointNames.length && jointNames[ji] != null) {
                    Integer bi = nameToIdx.get(jointNames[ji]);
                    if(bi != null) {
                        vJoints[v * 4 + k] = bi;
                        vWeights[v * 4 + k] = wt;
                    }
                }
            }
        // Renormalize each vertex's surviving weights: dropping an unmapped joint
        // (above) would otherwise leave the influences summing to < 1, skewing the
        // skin deformation. Vertices with no mapped influence are left as-is.
        for(int v = 0; v < num; v++) {
            float sum = 0;
            for(int k = 0; k < 4; k++)
                sum += vWeights[v * 4 + k];
            if(sum > 0)
                for(int k = 0; k < 4; k++)
                    vWeights[v * 4 + k] /= sum;
        }
        codec.setBones2(sd.boneNames, vJoints, vWeights);
    }

    /** Reads the primitive's triangle indices (or generates a trivial list over its own vertices if non-indexed). */
    @SuppressWarnings("unchecked")
    private static int[] readIndices(Glb g, Map<String, Object> prim) {
        Object mode = prim.get("mode");
        if(mode != null && ((Number) mode).intValue() != 4)
            throw new IllegalArgumentException("rebuild only supports triangle-list primitives (glTF mode 4), got mode "
                    + ((Number) mode).intValue());
        int pc = accessorCount(g, idx(((Map<String, Object>) prim.get("attributes")).get("POSITION")));
        Object idxAcc = prim.get("indices");
        if(idxAcc == null) {
            int[] t = new int[pc - (pc % 3)];
            for(int i = 0; i < t.length; i++)
                t[i] = i;
            return t;
        }
        float[] raw = readAccessor(g, ((Number) idxAcc).intValue(), 1);
        if(raw.length % 3 != 0)
            throw new IllegalArgumentException("primitive has " + raw.length
                    + " indices, not a multiple of 3 (not a triangle list)");
        int[] tris = new int[raw.length];
        for(int i = 0; i < raw.length; i++) {
            int v = Math.round(raw[i]);
            if(v < 0 || v >= pc)
                throw new IllegalArgumentException("triangle index " + v + " out of range [0, " + pc + ")");
            tris[i] = v;
        }
        return tris;
    }

    @SuppressWarnings("unchecked")
    private static int accessorCount(Glb g, int index) {
        Map<String, Object> acc = (Map<String, Object>) ((List<Object>) g.root.get("accessors")).get(index);
        return ((Number) acc.get("count")).intValue();
    }

    private static byte[] encodeMeshRaw(int matid, int id, int vbufid, int[] tris) {
        MessageWriter w = new MessageWriter();
        boolean hasId = id != -1;
        int fl = 16 | (hasId ? 2 : 0);
        w.uint8(fl).uint16(tris.length / 3).int16(matid);
        if(hasId)
            w.int16(id);
        w.int16(vbufid);
        for(int t : tris)
            w.uint16(t);
        return w.toByteArray();
    }

    /** glTF joint index → bone name, read from the mesh's skin (handles Blender's joint reorder). */
    @SuppressWarnings("unchecked")
    private static String[] skinJointNames(Map<String, Object> root) {
        List<Object> skins = (List<Object>) root.get("skins");
        List<Object> nodes = (List<Object>) root.get("nodes");
        if(skins == null || skins.isEmpty() || nodes == null)
            return null;
        Map<String, Object> skin = (Map<String, Object>) skins.get(0);
        for(Object no : nodes) {                         // prefer the skin actually used by a mesh node
            Map<String, Object> n = (Map<String, Object>) no;
            if(n.containsKey("mesh") && n.containsKey("skin")) {
                skin = (Map<String, Object>) skins.get(idx(n.get("skin")));
                break;
            }
        }
        List<Object> joints = (List<Object>) skin.get("joints");
        if(joints == null)
            return null;
        String[] names = new String[joints.size()];
        for(int i = 0; i < joints.size(); i++) {
            Map<String, Object> node = (Map<String, Object>) nodes.get(idx(joints.get(i)));
            Object nm = node.get("name");
            names[i] = (nm == null) ? null : nm.toString();
        }
        return names;
    }

    /* ----------------------------------------------------- skeleton (skel) re-import */

    /**
     * Re-imports an edited skeleton rest pose (Phase 2c). Each skel bone's new local
     * transform is read from its glTF joint node by name (Blender preserves bone
     * names and node-local translation/rotation), compared to the original, and — if
     * any bone moved beyond a small tolerance — the whole skeleton is re-encoded as a
     * version-1 {@code skel} via {@link SkelInfo#encodeVer1}. Unchanged skeletons are
     * left byte-identical (a plain Blender round-trip drifts only ~0.04°).
     */
    @SuppressWarnings("unchecked")
    private static boolean applySkel(Glb g, ResContainer res) {
        int skelIdx = -1;
        for(int i = 0; i < res.layers.size(); i++)
            if(res.layers.get(i).name.equals("skel")) {
                skelIdx = i;
                break;
            }
        if(skelIdx < 0)
            return false;
        SkelInfo si = SkelInfo.parse(res.layers.get(skelIdx).data);
        if(!si.recognized || si.bones.isEmpty())
            return false;
        Map<String, double[]> nodeXf = skelNodeTransforms(g.root);
        if(nodeXf.isEmpty())
            return false;

        boolean changed = false;
        List<SkelInfo.Bone> out = new ArrayList<>();
        for(SkelInfo.Bone b : si.bones) {
            double[] xf = nodeXf.get(b.name);
            if(xf == null) {                             // bone not in the glTF: keep as-is
                out.add(b);
                continue;
            }
            double dPos = Math.sqrt(sq(xf[0] - b.px) + sq(xf[1] - b.py) + sq(xf[2] - b.pz));
            double[] oq = axisAngleToQuat(b.ax, b.ay, b.az, b.ang);
            double dot = Math.abs(oq[0] * xf[3] + oq[1] * xf[4] + oq[2] * xf[5] + oq[3] * xf[6]);
            double dDeg = Math.toDegrees(2 * Math.acos(Math.min(1, dot)));
            if(dPos > 1e-3 || dDeg > 0.5)
                changed = true;
            double[] aa = quatToAxisAngle(xf[3], xf[4], xf[5], xf[6]);
            out.add(new SkelInfo.Bone(b.name, b.parent, (float) xf[0], (float) xf[1], (float) xf[2],
                    (float) aa[0], (float) aa[1], (float) aa[2], (float) aa[3]));
        }
        if(!changed)
            return false;
        res.layers.set(skelIdx, new Layer("skel", SkelInfo.encodeVer1(out)));
        return true;
    }

    /** Skeleton joint node name -> {tx,ty,tz, qx,qy,qz,qw} local transform. */
    @SuppressWarnings("unchecked")
    private static Map<String, double[]> skelNodeTransforms(Map<String, Object> root) {
        Map<String, double[]> out = new HashMap<>();
        List<Object> nodes = (List<Object>) root.get("nodes");
        List<Object> skins = (List<Object>) root.get("skins");
        if(nodes == null || skins == null)
            return out;
        for(Object so : skins) {
            List<Object> joints = (List<Object>) ((Map<String, Object>) so).get("joints");
            if(joints == null)
                continue;
            for(Object jo : joints) {
                Map<String, Object> n = (Map<String, Object>) nodes.get(((Number) jo).intValue());
                Object nm = n.get("name");
                if(nm == null)
                    continue;
                double[] xf = {0, 0, 0, 0, 0, 0, 1};
                List<Object> tl = (List<Object>) n.get("translation");
                if(tl != null)
                    for(int k = 0; k < 3; k++) xf[k] = ((Number) tl.get(k)).doubleValue();
                List<Object> rl = (List<Object>) n.get("rotation");
                if(rl != null)
                    for(int k = 0; k < 4; k++) xf[3 + k] = ((Number) rl.get(k)).doubleValue();
                out.putIfAbsent(nm.toString(), xf);
            }
        }
        return out;
    }

    private static double sq(double x) {
        return x * x;
    }

    /** Axis-angle (normalized axis + radians) -> quaternion [x,y,z,w]. */
    private static double[] axisAngleToQuat(double ax, double ay, double az, double ang) {
        double s = Math.sin(ang / 2), w = Math.cos(ang / 2);
        return new double[]{ax * s, ay * s, az * s, w};
    }

    /** Quaternion [x,y,z,w] -> {axisX, axisY, axisZ, angle(radians)} with a normalized axis. */
    private static double[] quatToAxisAngle(double x, double y, double z, double w) {
        double n = Math.sqrt(x * x + y * y + z * z + w * w);
        if(n > 0) { x /= n; y /= n; z /= n; w /= n; }
        w = Math.max(-1, Math.min(1, w));
        double ang = 2 * Math.acos(w);
        double s = Math.sqrt(1 - w * w);
        if(s < 1e-6)
            return new double[]{0, 0, 1, 0};
        return new double[]{x / s, y / s, z / s, ang};
    }

    /* ----------------------------------------------------------- glb / accessors */

    @SuppressWarnings("unchecked")
    private static Glb parseGlb(byte[] glb) {
        if(glb.length < 20 || le32(glb, 0) != 0x46546C67)
            throw new IllegalArgumentException("not a binary glTF (.glb) file");
        int jsonLen = le32(glb, 12);
        if(jsonLen < 0 || 20L + jsonLen + 8 > glb.length)
            throw new IllegalArgumentException("malformed glTF: JSON chunk length out of bounds");
        if(le32(glb, 16) != 0x4E4F534A)
            throw new IllegalArgumentException("malformed glTF: missing JSON chunk");
        Object parsed = Json.parse(new String(glb, 20, jsonLen, StandardCharsets.UTF_8));
        int binHeader = 20 + jsonLen;
        if(binHeader + 8 > glb.length || le32(glb, binHeader + 4) != 0x004E4942)
            throw new IllegalArgumentException("malformed glTF: missing BIN chunk");
        int binLen = le32(glb, binHeader);
        if(binHeader + 8L + binLen > glb.length)
            throw new IllegalArgumentException("malformed glTF: BIN chunk length out of bounds");
        return new Glb((Map<String, Object>) parsed, glb, binHeader + 8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> allPrimitives(Map<String, Object> root) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> meshes = (List<Object>) root.get("meshes");
        if(meshes == null)
            return out;
        for(Object mo : meshes) {
            List<Object> prims = (List<Object>) ((Map<String, Object>) mo).get("primitives");
            if(prims == null)
                continue;
            for(Object po : prims)
                out.add((Map<String, Object>) po);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributesOf(Map<String, Object> prim) {
        return (Map<String, Object>) prim.get("attributes");
    }

    private static int idx(Object o) {
        return ((Number) o).intValue();
    }

    @SuppressWarnings("unchecked")
    private static float[] readAccessor(Glb g, int index, int comps) {
        List<Object> accessors = (List<Object>) g.root.get("accessors");
        if(accessors == null || index < 0 || index >= accessors.size())
            throw new IllegalArgumentException("glTF references missing accessor " + index);
        Map<String, Object> acc = (Map<String, Object>) accessors.get(index);
        int ct = ((Number) acc.get("componentType")).intValue();
        boolean normalized = Boolean.TRUE.equals(acc.get("normalized"));
        int count = ((Number) acc.get("count")).intValue();
        if(count < 0)
            throw new IllegalArgumentException("glTF accessor " + index + " has a negative count");
        List<Object> bufferViews = (List<Object>) g.root.get("bufferViews");
        float[] out = new float[count * comps];

        // Dense base values (absent for an all-zero accessor or a sparse morph target).
        Object bvObj = acc.get("bufferView");
        if(bvObj != null) {
            Map<String, Object> bv = (Map<String, Object>) bufferViews.get(((Number) bvObj).intValue());
            int bvOff = num(bv.get("byteOffset"));
            int accOff = num(acc.get("byteOffset"));
            int compSize = compSize(ct);
            int stride = bv.get("byteStride") == null ? comps * compSize : ((Number) bv.get("byteStride")).intValue();
            int base = g.binStart + bvOff + accOff;
            if(count > 0) {
                long last = (long) base + (long) (count - 1) * stride + (long) (comps - 1) * compSize + compSize;
                if(base < g.binStart || last > g.data.length)
                    throw new IllegalArgumentException("glTF accessor " + index + " reads past the binary chunk");
            }
            for(int i = 0; i < count; i++)
                for(int c = 0; c < comps; c++)
                    out[i * comps + c] = decodeComp(g.data, base + i * stride + c * compSize, ct, normalized);
        }

        // Sparse overrides (Blender exports shape-key morph targets this way).
        Map<String, Object> sparse = (Map<String, Object>) acc.get("sparse");
        if(sparse != null) {
            int sc = ((Number) sparse.get("count")).intValue();
            Map<String, Object> sIdx = (Map<String, Object>) sparse.get("indices");
            Map<String, Object> sVal = (Map<String, Object>) sparse.get("values");
            int idxCt = ((Number) sIdx.get("componentType")).intValue();
            int idxSize = compSize(idxCt);
            Map<String, Object> idxBv = (Map<String, Object>) bufferViews.get(((Number) sIdx.get("bufferView")).intValue());
            int idxBase = g.binStart + num(idxBv.get("byteOffset")) + num(sIdx.get("byteOffset"));
            int valSize = compSize(ct);
            Map<String, Object> valBv = (Map<String, Object>) bufferViews.get(((Number) sVal.get("bufferView")).intValue());
            int valBase = g.binStart + num(valBv.get("byteOffset")) + num(sVal.get("byteOffset"));
            for(int s = 0; s < sc; s++) {
                int ei = (int) leUint(g.data, idxBase + s * idxSize, idxSize);
                if(ei < 0 || ei >= count)
                    throw new IllegalArgumentException("glTF sparse accessor " + index + " index " + ei + " out of range");
                for(int c = 0; c < comps; c++)
                    out[ei * comps + c] = decodeComp(g.data, valBase + (s * comps + c) * valSize, ct, normalized);
            }
        }
        return out;
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static int compSize(int componentType) {
        switch(componentType) {
            case 5126: case 5125: return 4;     // FLOAT, UNSIGNED_INT
            case 5123: case 5122: return 2;     // (UNSIGNED_)SHORT
            case 5121: case 5120: return 1;     // (UNSIGNED_)BYTE
            default: throw new IllegalArgumentException("unsupported accessor componentType: " + componentType);
        }
    }

    /** Decodes one component at {@code off}, honouring componentType and the normalized flag. */
    private static float decodeComp(byte[] data, int off, int ct, boolean normalized) {
        switch(ct) {
            case 5126: return Float.intBitsToFloat(le32(data, off));                          // FLOAT
            case 5125: return leUint(data, off, 4);                                           // UNSIGNED_INT
            case 5123: return normalized ? leUint(data, off, 2) / 65535f : leUint(data, off, 2);
            case 5121: return normalized ? leUint(data, off, 1) / 255f : leUint(data, off, 1);
            case 5122: return normalized ? Math.max(-1f, signExtend(leUint(data, off, 2), 2) / 32767f)
                    : signExtend(leUint(data, off, 2), 2);
            case 5120: return normalized ? Math.max(-1f, signExtend(leUint(data, off, 1), 1) / 127f)
                    : signExtend(leUint(data, off, 1), 1);
            default: throw new IllegalArgumentException("unsupported accessor componentType: " + ct);
        }
    }

    private static long leUint(byte[] b, int off, int size) {
        long v = 0;
        for(int i = 0; i < size; i++)
            v |= (long) (b[off + i] & 0xff) << (8 * i);
        return v;
    }

    private static long signExtend(long v, int size) {
        int bits = size * 8;
        long sign = 1L << (bits - 1);
        return (v ^ sign) - sign;
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

    /* --------------------------------------------------------- node transforms */

    /**
     * Maps each glTF primitive to the world matrix of the (non-skinned) node that
     * instances it, so object-level transforms a user left un-applied in Blender
     * (move/rotate/scale without "Apply Transform") are baked into the rebuilt
     * geometry instead of being silently dropped. Only non-identity transforms are
     * recorded — identity nodes (and resources our own exporter wrote) map to
     * nothing, leaving the existing behaviour untouched. Skinned meshes are skipped
     * because glTF ignores their node transform (vertices live in skin space).
     */
    @SuppressWarnings("unchecked")
    private static IdentityHashMap<Map<String, Object>, double[]> primitiveMatrices(Map<String, Object> root) {
        IdentityHashMap<Map<String, Object>, double[]> out = new IdentityHashMap<>();
        List<Object> nodes = (List<Object>) root.get("nodes");
        List<Object> meshes = (List<Object>) root.get("meshes");
        if(nodes == null || meshes == null)
            return out;
        boolean[] isChild = new boolean[nodes.size()];
        for(Object no : nodes) {
            List<Object> ch = (List<Object>) ((Map<String, Object>) no).get("children");
            if(ch != null)
                for(Object c : ch) {
                    int ci = idx(c);
                    if(ci >= 0 && ci < isChild.length)
                        isChild[ci] = true;
                }
        }
        List<Integer> roots = new ArrayList<>();
        List<Object> scenes = (List<Object>) root.get("scenes");
        if(scenes != null && !scenes.isEmpty()) {
            Object si = root.get("scene");
            Map<String, Object> scene = (Map<String, Object>) scenes.get(si == null ? 0 : idx(si));
            List<Object> sn = (List<Object>) scene.get("nodes");
            if(sn != null)
                for(Object o : sn)
                    roots.add(idx(o));
        }
        if(roots.isEmpty())
            for(int i = 0; i < nodes.size(); i++)
                if(!isChild[i])
                    roots.add(i);
        for(int r : roots)
            accumulate(nodes, meshes, r, identity(), out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void accumulate(List<Object> nodes, List<Object> meshes, int ni, double[] parent,
                                   IdentityHashMap<Map<String, Object>, double[]> out) {
        if(ni < 0 || ni >= nodes.size())
            return;
        Map<String, Object> n = (Map<String, Object>) nodes.get(ni);
        double[] world = mul(parent, localMatrix(n));
        if(n.containsKey("mesh") && !n.containsKey("skin") && !isIdentity(world)) {
            Map<String, Object> mesh = (Map<String, Object>) meshes.get(idx(n.get("mesh")));
            List<Object> prims = (List<Object>) mesh.get("primitives");
            if(prims != null)
                for(Object p : prims)
                    out.put((Map<String, Object>) p, world);
        }
        List<Object> ch = (List<Object>) n.get("children");
        if(ch != null)
            for(Object c : ch)
                accumulate(nodes, meshes, idx(c), world, out);
    }

    /** A node's local transform: an explicit column-major {@code matrix} or T*R*S. */
    private static double[] localMatrix(Map<String, Object> n) {
        Object m = n.get("matrix");
        if(m instanceof List) {
            List<?> l = (List<?>) m;
            double[] r = new double[16];
            for(int i = 0; i < 16; i++)
                r[i] = ((Number) l.get(i)).doubleValue();
            return r;
        }
        double[] t = vecd(n.get("translation"), 3, 0);
        double[] q = vecd(n.get("rotation"), 4, Double.NaN);   // x,y,z,w; default identity below
        if(Double.isNaN(q[3])) { q[0] = 0; q[1] = 0; q[2] = 0; q[3] = 1; }
        double[] s = vecd(n.get("scale"), 3, 1);
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double r00 = 1 - 2 * (y * y + z * z), r01 = 2 * (x * y - w * z), r02 = 2 * (x * z + w * y);
        double r10 = 2 * (x * y + w * z), r11 = 1 - 2 * (x * x + z * z), r12 = 2 * (y * z - w * x);
        double r20 = 2 * (x * z - w * y), r21 = 2 * (y * z + w * x), r22 = 1 - 2 * (x * x + y * y);
        return new double[]{
            s[0] * r00, s[0] * r10, s[0] * r20, 0,
            s[1] * r01, s[1] * r11, s[1] * r21, 0,
            s[2] * r02, s[2] * r12, s[2] * r22, 0,
            t[0],       t[1],       t[2],       1
        };
    }

    private static double[] vecd(Object o, int n, double def) {
        double[] v = new double[n];
        for(int i = 0; i < n; i++)
            v[i] = def;
        if(o instanceof List) {
            List<?> l = (List<?>) o;
            for(int i = 0; i < n && i < l.size(); i++)
                v[i] = ((Number) l.get(i)).doubleValue();
        }
        return v;
    }

    private static double[] identity() {
        return new double[]{1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1};
    }

    /** Column-major 4x4 product a*b. */
    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for(int col = 0; col < 4; col++)
            for(int row = 0; row < 4; row++) {
                double s = 0;
                for(int k = 0; k < 4; k++)
                    s += a[k * 4 + row] * b[col * 4 + k];
                r[col * 4 + row] = s;
            }
        return r;
    }

    private static boolean isIdentity(double[] m) {
        double[] id = identity();
        for(int i = 0; i < 16; i++)
            if(Math.abs(m[i] - id[i]) > 1e-9)
                return false;
        return true;
    }

    /** Applies a column-major matrix (w=1) to each xyz triple, in place. */
    private static void applyMatrix(float[] p, double[] m) {
        for(int i = 0; i + 2 < p.length; i += 3) {
            double x = p[i], y = p[i + 1], z = p[i + 2];
            p[i]     = (float) (m[0] * x + m[4] * y + m[8] * z + m[12]);
            p[i + 1] = (float) (m[1] * x + m[5] * y + m[9] * z + m[13]);
            p[i + 2] = (float) (m[2] * x + m[6] * y + m[10] * z + m[14]);
        }
    }

    /** Applies a column-major matrix's linear 3x3 (w=0) to xyz deltas, in place. */
    private static void applyVectorMatrix(float[] vectors, double[] matrix) {
        for(int i = 0; i + 2 < vectors.length; i += 3) {
            double x = vectors[i], y = vectors[i + 1], z = vectors[i + 2];
            vectors[i] = (float) (matrix[0] * x + matrix[4] * y + matrix[8] * z);
            vectors[i + 1] = (float) (matrix[1] * x + matrix[5] * y + matrix[9] * z);
            vectors[i + 2] = (float) (matrix[2] * x + matrix[6] * y + matrix[10] * z);
        }
    }

    /** Applies the inverse-transpose of a matrix's 3x3 to each normal, then renormalizes. */
    private static void applyNormalMatrix(float[] nrm, double[] m) {
        double a00 = m[0], a10 = m[1], a20 = m[2];
        double a01 = m[4], a11 = m[5], a21 = m[6];
        double a02 = m[8], a12 = m[9], a22 = m[10];
        // Cofactor matrix == det * inverse-transpose; the det scale cancels on normalize.
        double c00 = a11 * a22 - a12 * a21, c01 = -(a10 * a22 - a12 * a20), c02 = a10 * a21 - a11 * a20;
        double c10 = -(a01 * a22 - a02 * a21), c11 = a00 * a22 - a02 * a20, c12 = -(a00 * a21 - a01 * a20);
        double c20 = a01 * a12 - a02 * a11, c21 = -(a00 * a12 - a02 * a10), c22 = a00 * a11 - a01 * a10;
        for(int i = 0; i + 2 < nrm.length; i += 3) {
            double x = nrm[i], y = nrm[i + 1], z = nrm[i + 2];
            double nx = c00 * x + c01 * y + c02 * z;
            double ny = c10 * x + c11 * y + c12 * z;
            double nz = c20 * x + c21 * y + c22 * z;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if(len > 1e-12) {
                nrm[i]     = (float) (nx / len);
                nrm[i + 1] = (float) (ny / len);
                nrm[i + 2] = (float) (nz / len);
            }
        }
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }
}
