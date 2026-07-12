package resforge.model;

import resforge.layers.SkanInfo;
import resforge.layers.SkelInfo;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluates {@code skan} clips and CPU-skins a viewer triangle soup. */
public final class SkanPlayback {
    private static final int MAX_BONES = 512;
    private static final int MAX_SOUP_VERTICES = 500_000;

    public record Pose(float[] positions, float[] normals) {
    }

    public record TimeState(float time, boolean backward, boolean done) {
    }

    private final ModelGeometry geometry;
    private final List<SkelInfo.Bone> bones;
    private final Map<String, Integer> boneIndex;
    private final int[] parents;
    private final float[][] inverseBind;
    private final int[] geometryBones;
    private final List<SkanInfo> clips;

    private SkanPlayback(ModelGeometry geometry, SkelInfo skeleton, List<SkanInfo> clips) {
        this.geometry = geometry;
        this.bones = List.copyOf(skeleton.bones);
        this.clips = List.copyOf(clips);
        if(bones.size() > MAX_BONES)
            throw new IllegalArgumentException("skeletal preview exceeds the bone limit of " + MAX_BONES);
        if(geometry.positions.length / 3 > MAX_SOUP_VERTICES)
            throw new IllegalArgumentException("skeletal preview exceeds the animated-vertex limit of "
                    + MAX_SOUP_VERTICES);
        this.boneIndex = new LinkedHashMap<>();
        for(int i = 0; i < bones.size(); i++) {
            String name = bones.get(i).name;
            if(boneIndex.putIfAbsent(name, i) != null)
                throw new IllegalArgumentException("skeleton has duplicate bone " + name);
        }
        this.parents = new int[bones.size()];
        for(int i = 0; i < bones.size(); i++) {
            String parent = bones.get(i).parent;
            parents[i] = parent.isEmpty() ? -1 : boneIndex.getOrDefault(parent, -2);
            if(parents[i] == -2)
                throw new IllegalArgumentException("skeleton bone " + bones.get(i).name
                        + " has missing parent " + parent);
        }
        this.geometryBones = new int[geometry.boneNames.size()];
        for(int i = 0; i < geometryBones.length; i++) {
            Integer index = boneIndex.get(geometry.boneNames.get(i));
            if(index == null)
                throw new IllegalArgumentException("preview model bone \""
                        + geometry.boneNames.get(i) + "\" is missing from the selected skeleton");
            geometryBones[i] = index;
        }
        for(SkanInfo clip : clips)
            for(SkanInfo.Track track : clip.tracks)
                if(!boneIndex.containsKey(track.bone))
                    throw new IllegalArgumentException("animation bone \"" + track.bone
                            + "\" is missing from the selected skeleton");
        float[][] bindWorld = worldMatrices(List.of(), 0);
        this.inverseBind = new float[bindWorld.length][];
        for(int i = 0; i < bindWorld.length; i++)
            inverseBind[i] = M4.rigidInverse(bindWorld[i]);
    }

    public static SkanPlayback from(ModelGeometry geometry, ResContainer skeletonRes,
                                    ResContainer animationRes) {
        if(geometry == null || geometry.boneNames.isEmpty())
            return null;
        SkelInfo skeleton = null;
        for(Layer layer : skeletonRes.layers)
            if(layer.name.equals("skel")) {
                SkelInfo parsed = SkelInfo.parse(layer.data);
                if(parsed.recognized) {
                    skeleton = parsed;
                    break;
                }
            }
        if(skeleton == null || skeleton.bones.isEmpty())
            return null;
        List<SkanInfo> clips = new ArrayList<>();
        for(Layer layer : animationRes.layers)
            if(layer.name.equals("skan")) {
                SkanInfo clip = SkanInfo.parse(layer.data);
                if(clip.recognized)
                    clips.add(clip);
            }
        if(clips.isEmpty())
            return null;
        return new SkanPlayback(geometry, skeleton, clips);
    }

    public List<SkanInfo> clips() {
        return clips;
    }

    public Pose pose(SkanInfo clip, float time) {
        if(!clips.contains(clip))
            throw new IllegalArgumentException("clip does not belong to this playback");
        return pose(List.of(clip), time);
    }

    public Pose pose(List<SkanInfo> selected, float time) {
        if(selected.isEmpty() || !clips.containsAll(selected))
            throw new IllegalArgumentException("clips do not belong to this playback");
        float maxLength = 0;
        for(SkanInfo clip : selected)
            maxLength = Math.max(maxLength, clip.len);
        float[][] world = worldMatrices(selected, Math.max(0, Math.min(maxLength, time)));
        float[][] skin = new float[bones.size()][];
        for(int i = 0; i < skin.length; i++)
            skin[i] = M4.mul(world[i], inverseBind[i]);
        float[] positions = new float[geometry.positions.length];
        float[] normals = new float[geometry.normals.length];
        int vertices = positions.length / 3;
        for(int vertex = 0; vertex < vertices; vertex++) {
            int p = vertex * 3, inf = vertex * 4;
            float px = 0, py = 0, pz = 0, nx = 0, ny = 0, nz = 0, used = 0;
            for(int k = 0; k < 4; k++) {
                float weight = geometry.weights[inf + k];
                if(weight <= 0)
                    continue;
                float[] matrix = skin[geometryBones[geometry.joints[inf + k]]];
                float x = geometry.positions[p], y = geometry.positions[p + 1],
                        z = geometry.positions[p + 2];
                px += weight * (matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]);
                py += weight * (matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]);
                pz += weight * (matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]);
                x = geometry.normals[p];
                y = geometry.normals[p + 1];
                z = geometry.normals[p + 2];
                nx += weight * (matrix[0] * x + matrix[4] * y + matrix[8] * z);
                ny += weight * (matrix[1] * x + matrix[5] * y + matrix[9] * z);
                nz += weight * (matrix[2] * x + matrix[6] * y + matrix[10] * z);
                used += weight;
            }
            if(used == 0) {
                positions[p] = geometry.positions[p];
                positions[p + 1] = geometry.positions[p + 1];
                positions[p + 2] = geometry.positions[p + 2];
                normals[p] = geometry.normals[p];
                normals[p + 1] = geometry.normals[p + 1];
                normals[p + 2] = geometry.normals[p + 2];
            } else {
                positions[p] = px / used;
                positions[p + 1] = py / used;
                positions[p + 2] = pz / used;
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if(length == 0)
                    length = 1;
                normals[p] = nx / length;
                normals[p + 1] = ny / length;
                normals[p + 2] = nz / length;
            }
        }
        return new Pose(positions, normals);
    }

    public boolean canCombineAll() {
        if(clips.size() < 2)
            return false;
        SkanInfo first = clips.get(0);
        for(int i = 1; i < clips.size(); i++) {
            SkanInfo clip = clips.get(i);
            if(!clip.mode.equals(first.mode) || Math.abs(clip.len - first.len) > 1e-4f)
                return false;
        }
        return true;
    }

    public static TimeState advance(float time, boolean backward, float delta,
                                    float length, String mode) {
        if(length <= 0)
            return new TimeState(0, backward, true);
        float next;
        boolean done = false;
        switch(mode) {
            case "loop":
                next = (time + delta) % length;
                break;
            case "once":
                next = time + delta;
                if(next >= length) {
                    next = length;
                    done = true;
                }
                break;
            case "pong":
                if(backward) {
                    if(delta >= time) {
                        next = 0;
                        done = true;
                    } else {
                        next = time - delta;
                    }
                } else {
                    float toEnd = length - time;
                    if(delta <= toEnd) {
                        next = time + delta;
                    } else if(delta - toEnd >= length) {
                        next = 0;
                        backward = true;
                        done = true;
                    } else {
                        next = length - (delta - toEnd);
                        backward = true;
                    }
                }
                break;
            case "pong-loop": {
                float period = length * 2;
                float phase = (backward ? period - time : time) + delta;
                phase %= period;
                if(phase <= length) {
                    next = phase;
                    backward = false;
                } else {
                    next = period - phase;
                    backward = true;
                }
                break;
            }
            default:
                throw new IllegalArgumentException("unknown skan mode " + mode);
        }
        return new TimeState(next, backward, done);
    }

    private float[][] worldMatrices(List<SkanInfo> selected, float time) {
        float[][] translations = new float[bones.size()][3];
        float[][] rotations = new float[bones.size()][4];
        for(float[] rotation : rotations)
            rotation[0] = 1;
        for(SkanInfo clip : selected)
            for(SkanInfo.Track track : clip.tracks) {
                int index = boneIndex.get(track.bone);
                float[] translation = new float[3];
                float[] rotation = {1, 0, 0, 0};
                sample(track, clip.len, Math.min(clip.len, time), translation, rotation);
                for(int i = 0; i < 3; i++)
                    translations[index][i] += translation[i];
                rotations[index] = normalize(M4.qmul(rotations[index], rotation));
            }
        float[][] local = new float[bones.size()][];
        for(int i = 0; i < bones.size(); i++) {
            SkelInfo.Bone bone = bones.get(i);
            float[] bind = M4.quat(bone.ax, bone.ay, bone.az, bone.ang);
            float[] posed = normalize(M4.qmul(bind, rotations[i]));
            local[i] = M4.mul(M4.translate(bone.px + translations[i][0],
                            bone.py + translations[i][1], bone.pz + translations[i][2]),
                    M4.fromQuat(posed[0], posed[1], posed[2], posed[3]));
        }
        float[][] world = new float[bones.size()][];
        byte[] state = new byte[bones.size()];
        for(int i = 0; i < bones.size(); i++)
            resolveWorld(i, local, world, state);
        return world;
    }

    private void resolveWorld(int index, float[][] local, float[][] world, byte[] state) {
        if(state[index] == 2)
            return;
        if(state[index] == 1)
            throw new IllegalArgumentException("skeleton has a parent cycle at " + bones.get(index).name);
        state[index] = 1;
        int parent = parents[index];
        if(parent < 0) {
            world[index] = local[index];
        } else {
            resolveWorld(parent, local, world, state);
            world[index] = M4.mul(world[parent], local[index]);
        }
        state[index] = 2;
    }

    private static void sample(SkanInfo.Track track, float length, float time,
                               float[] translation, float[] rotation) {
        if(track.frames == 0)
            return;
        if(track.frames == 1) {
            System.arraycopy(track.trans[0], 0, translation, 0, 3);
            System.arraycopy(track.rot[0], 0, rotation, 0, 4);
            return;
        }
        int current = 0;
        while(current + 1 < track.frames && track.times[current + 1] < time)
            current++;
        int next = (current + 1) % track.frames;
        float currentTime = track.times[current];
        float nextTime = current + 1 < track.frames ? track.times[current + 1] : length;
        float alpha = nextTime == currentTime ? 0 : (time - currentTime) / (nextTime - currentTime);
        alpha = Math.max(0, Math.min(1, alpha));
        for(int i = 0; i < 3; i++)
            translation[i] = track.trans[current][i]
                    + (track.trans[next][i] - track.trans[current][i]) * alpha;
        slerp(track.rot[current], track.rot[next], alpha, rotation);
    }

    private static void slerp(float[] a, float[] b, float alpha, float[] out) {
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        float sign = 1;
        if(dot < 0) {
            dot = -dot;
            sign = -1;
        }
        if(dot > 0.9995f) {
            for(int i = 0; i < 4; i++)
                out[i] = a[i] + (sign * b[i] - a[i]) * alpha;
        } else {
            double theta = Math.acos(Math.max(-1, Math.min(1, dot)));
            double sin = Math.sin(theta);
            double wa = Math.sin((1 - alpha) * theta) / sin;
            double wb = Math.sin(alpha * theta) / sin;
            for(int i = 0; i < 4; i++)
                out[i] = (float) (wa * a[i] + wb * sign * b[i]);
        }
        float[] normalized = normalize(out);
        System.arraycopy(normalized, 0, out, 0, 4);
    }

    private static float[] normalize(float[] q) {
        float length = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1]
                + q[2] * q[2] + q[3] * q[3]);
        if(length == 0)
            return new float[]{1, 0, 0, 0};
        return new float[]{q[0] / length, q[1] / length, q[2] / length, q[3] / length};
    }
}
