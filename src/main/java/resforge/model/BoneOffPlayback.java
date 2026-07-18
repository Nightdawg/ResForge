package resforge.model;

import resforge.io.MessageReader;
import resforge.layers.SkanInfo;
import resforge.res.ResContainer;

import java.util.List;

/** Animates a skinned model and rigidly attaches a second model through a boneoff program. */
public final class BoneOffPlayback {
    public record Pose(float[] positions, float[] normals) {
    }

    private final SkanPlayback player;
    private final ModelGeometry geometry;
    private volatile byte[] boneOff;
    private volatile SkanPlayback.Pose latestPlayerPose;
    private final int weaponOffset;

    private BoneOffPlayback(SkanPlayback player, ModelGeometry geometry,
                            byte[] boneOff, int weaponOffset) {
        this.player = player;
        this.geometry = geometry;
        this.boneOff = boneOff.clone();
        this.weaponOffset = weaponOffset;
    }

    public static BoneOffPlayback from(ModelGeometry playerGeometry, ModelGeometry weaponGeometry,
                                       ResContainer skeleton, ResContainer animation,
                                       byte[] boneOff) {
        ModelGeometry.Combination combination =
                ModelGeometry.combine(playerGeometry, weaponGeometry);
        SkanPlayback playback = SkanPlayback.from(combination.geometry(), skeleton, animation);
        if(playback == null)
            return null;
        BoneOffPlayback result = new BoneOffPlayback(playback, combination.geometry(),
                boneOff, combination.secondPositionOffset());
        result.pose(playback.clips().subList(0, 1), 0);
        return result;
    }

    public ModelGeometry geometry() {
        return geometry;
    }

    public List<SkanInfo> clips() {
        return player.clips();
    }

    public boolean canCombineAll() {
        return player.canCombineAll();
    }

    public Pose pose(SkanInfo clip, float time) {
        return pose(List.of(clip), time);
    }

    public Pose pose(List<SkanInfo> clips, float time) {
        SkanPlayback.Pose playerPose = player.pose(clips, time);
        latestPlayerPose = playerPose;
        float[] positions = playerPose.positions();
        float[] normals = playerPose.normals();
        byte[] currentBoneOff = boneOff;
        float[] transform = evaluate(currentBoneOff, playerPose);
        for(int i = weaponOffset; i < positions.length; i += 3) {
            float x = positions[i], y = positions[i + 1], z = positions[i + 2];
            positions[i] = transform[0] * x + transform[4] * y + transform[8] * z + transform[12];
            positions[i + 1] = transform[1] * x + transform[5] * y + transform[9] * z + transform[13];
            positions[i + 2] = transform[2] * x + transform[6] * y + transform[10] * z + transform[14];
            x = normals[i];
            y = normals[i + 1];
            z = normals[i + 2];
            float nx = transform[0] * x + transform[4] * y + transform[8] * z;
            float ny = transform[1] * x + transform[5] * y + transform[9] * z;
            float nz = transform[2] * x + transform[6] * y + transform[10] * z;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if(length > 1e-12f) {
                normals[i] = nx / length;
                normals[i + 1] = ny / length;
                normals[i + 2] = nz / length;
            }
        }
        return new Pose(positions, normals);
    }

    /**
     * Validates and installs a new preview-only boneoff payload. A rejected payload
     * leaves the previous transform active.
     */
    public void updateBoneOff(byte[] payload) {
        SkanPlayback.Pose pose = latestPlayerPose;
        if(pose == null)
            throw new IllegalStateException("boneoff preview pose is not initialized");
        byte[] replacement = payload.clone();
        evaluate(replacement, pose);
        boneOff = replacement;
    }

    static float[] evaluate(byte[] payload, SkanPlayback.Pose pose) {
        MessageReader in = new MessageReader(payload);
        in.string();
        float[] result = M4.identity();
        while(!in.eom()) {
            int opcode = in.uint8();
            float[] op;
            switch(opcode) {
                case 0:
                    op = M4.translate((float) in.cpfloat(), (float) in.cpfloat(),
                            (float) in.cpfloat());
                    break;
                case 16:
                    op = M4.translate(in.float32(), in.float32(), in.float32());
                    break;
                case 1: {
                    float angle = (float) in.cpfloat();
                    op = M4.rotate((float) in.cpfloat(), (float) in.cpfloat(),
                            (float) in.cpfloat(), angle);
                    break;
                }
                case 17: {
                    float angle = in.mnorm16() * 2 * (float) Math.PI;
                    float[] axis = new float[3];
                    MessageReader.oct2uvec(axis, in.snorm16(), in.snorm16());
                    op = M4.rotate(axis[0], axis[1], axis[2], angle);
                    break;
                }
                case 2:
                    op = requireBone(pose, in.string());
                    break;
                case 3:
                    op = align(pose, (float) in.cpfloat(), (float) in.cpfloat(),
                            (float) in.cpfloat(), in.string(), in.string());
                    break;
                case 19: {
                    float[] ref = new float[3];
                    MessageReader.oct2uvec(ref, in.snorm16(), in.snorm16());
                    op = align(pose, ref[0], ref[1], ref[2], in.string(), in.string());
                    break;
                }
                case 4:
                    result = M4.nullRotation(result);
                    continue;
                case 5:
                    op = M4.scale(in.float32());
                    break;
                default:
                    throw new IllegalArgumentException("unsupported boneoff opcode " + opcode);
            }
            result = M4.mul(result, op);
        }
        return result;
    }

    private static float[] requireBone(SkanPlayback.Pose pose, String name) {
        float[] bone = pose.boneWorld(name);
        if(bone == null)
            throw new IllegalArgumentException("boneoff references missing bone \"" + name + "\"");
        return bone;
    }

    private static float[] align(SkanPlayback.Pose pose, float rx, float ry, float rz,
                                 String from, String to) {
        float[] origin = requireBone(pose, from);
        float[] target = requireBone(pose, to);
        float[] ref = normalize(rx, ry, rz);
        float[] current = normalize(target[12] - origin[12], target[13] - origin[13],
                target[14] - origin[14]);
        float ax = current[1] * ref[2] - current[2] * ref[1];
        float ay = current[2] * ref[0] - current[0] * ref[2];
        float az = current[0] * ref[1] - current[1] * ref[0];
        float dot = Math.max(-1, Math.min(1,
                current[0] * ref[0] + current[1] * ref[1] + current[2] * ref[2]));
        if(dot < -0.999999f && ax * ax + ay * ay + az * az < 1e-12f) {
            if(Math.abs(current[0]) < 0.9f) {
                ax = 0;
                ay = current[2];
                az = -current[1];
            } else {
                ax = -current[2];
                ay = 0;
                az = current[0];
            }
        }
        return M4.mul(M4.translate(origin[12], origin[13], origin[14]),
                M4.rotate(ax, ay, az, -(float) Math.acos(dot)));
    }

    private static float[] normalize(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if(length < 1e-12f)
            throw new IllegalArgumentException("boneoff alignment vector has zero length");
        return new float[]{x / length, y / length, z / length};
    }
}
