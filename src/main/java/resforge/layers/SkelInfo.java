package resforge.layers;

import resforge.io.MessageReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only decoder for the {@code skel} layer (from haven.Skeleton.Res): the
 * bone hierarchy a model is rigged to. Each bone has a name, a parent name (empty
 * for a root), a position, and a rotation (axis + angle) describing its rest pose.
 *
 * <p>Version 0 stores positions/rotations as custom-packed floats ({@code cpfloat})
 * and is read recursively — a one-character name whose code is below 32 switches
 * the decoder to that sub-version. Version 1 stores positions as {@code float32}
 * and the rotation as a modular-normalised angle plus an octahedral-encoded axis.
 *
 * <p>Informational only; the layer stays raw/lossless.
 */
public final class SkelInfo {
    public static final class Bone {
        public final String name;
        public final String parent;
        public final float px, py, pz;
        public final float ax, ay, az;
        public final float ang;

        Bone(String name, String parent, float px, float py, float pz,
             float ax, float ay, float az, float ang) {
            this.name = name;
            this.parent = parent;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.ang = ang;
        }
    }

    public boolean recognized;
    public boolean reachedEnd;
    public final List<Bone> bones = new ArrayList<>();

    public static SkelInfo parse(byte[] payload) {
        SkelInfo si = new SkelInfo();
        try {
            MessageReader in = new MessageReader(payload);
            read(si, in, 0);
            si.recognized = true;
            si.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            si.recognized = false;
        }
        return si;
    }

    private static void read(SkelInfo si, MessageReader in, int ver) {
        if(ver == 0) {
            while(!in.eom()) {
                String name = in.string();
                if(name.length() == 1 && name.charAt(0) < 32) {
                    read(si, in, name.charAt(0));
                    return;
                }
                float px = (float) in.cpfloat(), py = (float) in.cpfloat(), pz = (float) in.cpfloat();
                float ax = (float) in.cpfloat(), ay = (float) in.cpfloat(), az = (float) in.cpfloat();
                float[] n = normalize(ax, ay, az);
                float ang = (float) in.cpfloat();
                String parent = in.string();
                si.bones.add(new Bone(name, parent, px, py, pz, n[0], n[1], n[2], ang));
            }
        } else if(ver == 1) {
            while(!in.eom()) {
                String name = in.string();
                String parent = in.string();
                float px = in.float32(), py = in.float32(), pz = in.float32();
                float ang = in.mnorm16() * 2 * (float) Math.PI;
                float[] rax = new float[3];
                MessageReader.oct2uvec(rax, in.snorm16(), in.snorm16());
                si.bones.add(new Bone(name, parent, px, py, pz, rax[0], rax[1], rax[2], ang));
            }
        } else {
            throw new IllegalStateException("skeleton version " + ver);
        }
    }

    private static float[] normalize(float x, float y, float z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if(len == 0)
            return new float[]{0, 0, 1};
        return new float[]{(float) (x / len), (float) (y / len), (float) (z / len)};
    }

    /** Count of bones that are roots (no parent). */
    public int rootCount() {
        int n = 0;
        for(Bone b : bones)
            if(b.parent.isEmpty())
                n++;
        return n;
    }
}
