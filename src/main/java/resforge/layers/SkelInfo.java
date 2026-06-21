package resforge.layers;

import resforge.io.MessageReader;
import resforge.io.MessageWriter;

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

        public Bone(String name, String parent, float px, float py, float pz,
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

    /**
     * Encodes a bone list as a version-1 {@code skel} layer (the client reads both
     * versions, so an edited skeleton is always written in this form): a leading
     * {@code "\u0001"} marker, then per bone its name, parent, {@code float32}
     * position, the rotation angle as {@code mnorm16} ({@code angle/2π} mod 1) and the
     * rotation axis octahedral-encoded into two {@code snorm16}s. Mirrors the
     * version-1 branch of {@link #parse}.
     */
    public static byte[] encodeVer1(List<Bone> bones) {
        MessageWriter w = new MessageWriter();
        w.string("\u0001");
        float[] oct = new float[2];
        for(Bone b : bones) {
            w.string(b.name).string(b.parent);
            w.float32(b.px).float32(b.py).float32(b.pz);
            double f = b.ang / (2 * Math.PI);
            f -= Math.floor(f);                          // wrap to [0, 1)
            w.uint16((int) Math.round(f * 0x10000) & 0xffff);
            uvec2oct(oct, b.ax, b.ay, b.az);
            w.int16(snorm16(oct[0])).int16(snorm16(oct[1]));
        }
        return w.toByteArray();
    }

    private static int snorm16(float v) {
        int q = Math.round(Math.max(-1f, Math.min(1f, v)) * 0x7fff);
        return Math.max(-0x7fff, Math.min(0x7fff, q));
    }

    /** Octahedral-encode a unit vector to two components in [-1, 1] (mirrors Utils.uvec2oct). */
    private static void uvec2oct(float[] out, float x, float y, float z) {
        float m = 1.0f / (Math.abs(x) + Math.abs(y) + Math.abs(z));
        float hx = x * m, hy = y * m;
        if(z >= 0) {
            out[0] = hx;
            out[1] = hy;
        } else {
            out[0] = (1 - Math.abs(hy)) * Math.copySign(1, hx);
            out[1] = (1 - Math.abs(hx)) * Math.copySign(1, hy);
        }
    }
}
