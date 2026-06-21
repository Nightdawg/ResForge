package resforge.layers;

import resforge.io.MessageReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only decoder for the {@code manim} layer (from haven.MeshAnim.Res): a
 * mesh ("morph") animation — per-frame vertex position offsets that deform a
 * model over time (e.g. a flag rippling, a plant swaying). Unlike {@code skan}
 * (which animates bones), {@code manim} moves individual vertices directly.
 *
 * <p>Structure: {@code uint8 ver}(1), {@code int16 id}, {@code uint8 rnd} (play
 * frames in random vs sequential order), {@code float32 len}, then frames until
 * a {@code 0} terminator. Each frame is {@code uint8 fmt} (1..4), {@code float32}
 * time, {@code uint16} vertex-count, an optional quantisation header (fmt 4),
 * then run-length spans of per-vertex data whose size depends on the format:
 * <ul>
 *   <li>fmt 1: position + normal, 6×{@code float32};</li>
 *   <li>fmt 2: position only, packed {@code float9995} ({@code int32});</li>
 *   <li>fmt 3: position only, 3×{@code float16};</li>
 *   <li>fmt 4: position only, 3×{@code unorm8} dequantised by the frame header.</li>
 * </ul>
 *
 * <p>This decoder walks the structure to report frame counts and formats; it
 * neither plays nor edits the animation, and the layer stays raw/lossless.
 */
public final class MeshAnimInfo {
    public static final class Frame {
        public final float time;
        public final int fmt;
        public final int vertices;
        public final int[] idx;        // animated vertex indices (into the vbuf), or null
        public final float[] pos;      // per-animated-vertex position deltas [x,y,z]·vertices, or null

        Frame(float time, int fmt, int vertices, int[] idx, float[] pos) {
            this.time = time;
            this.fmt = fmt;
            this.vertices = vertices;
            this.idx = idx;
            this.pos = pos;
        }

        public String formatName() {
            switch(fmt) {
                case 1: return "float32 pos+nrm";
                case 2: return "float9995 pos";
                case 3: return "float16 pos";
                case 4: return "unorm8 pos";
                default: return "fmt" + fmt;
            }
        }
    }

    public boolean recognized;
    public boolean reachedEnd;
    public int ver;
    public int id;
    public boolean random;
    public float len;
    public final List<Frame> frames = new ArrayList<>();

    public static MeshAnimInfo parse(byte[] payload) {
        MeshAnimInfo mi = new MeshAnimInfo();
        try {
            MessageReader in = new MessageReader(payload);
            mi.ver = in.uint8();
            if(mi.ver != 1)
                throw new IllegalStateException("meshanim version " + mi.ver);
            mi.id = in.int16();
            mi.random = in.uint8() != 0;
            mi.len = in.float32();
            while(true) {
                int t = in.uint8();
                if(t == 0)
                    break;
                if(t > 4)
                    throw new IllegalStateException("meshanim frame format " + t);
                float tm = in.float32();
                int n = in.uint16();
                float xm = 0, xk = 0, ym = 0, yk = 0, zm = 0, zk = 0;
                if(t == 4) {
                    xm = in.float16(); xk = in.float16();
                    ym = in.float16(); yk = in.float16();
                    zm = in.float16(); zk = in.float16();
                }
                int[] idx = new int[n];
                // Position deltas captured for fmt 1/3/4 (fmt 2 = float9995 is not in
                // any sample; its bytes are still consumed, but pos is left null).
                float[] pos = (t == 2) ? null : new float[n * 3];
                int i = 0;
                while(i < n) {
                    int st = in.uint16();              // span start
                    int run = in.uint16();
                    for(int o = 0; o < run; o++) {
                        idx[i] = st + o;
                        switch(t) {
                            case 1:
                                pos[i * 3] = in.float32();
                                pos[i * 3 + 1] = in.float32();
                                pos[i * 3 + 2] = in.float32();
                                in.float32(); in.float32(); in.float32();   // normal delta (unused)
                                break;
                            case 2:
                                in.int32();
                                break;
                            case 3:
                                pos[i * 3] = in.float16();
                                pos[i * 3 + 1] = in.float16();
                                pos[i * 3 + 2] = in.float16();
                                break;
                            case 4:
                                pos[i * 3] = xm + xk * (in.uint8() / 255.0f);
                                pos[i * 3 + 1] = ym + yk * (in.uint8() / 255.0f);
                                pos[i * 3 + 2] = zm + zk * (in.uint8() / 255.0f);
                                break;
                            default:
                                throw new IllegalStateException("meshanim frame format " + t);
                        }
                        i++;
                    }
                }
                mi.frames.add(new Frame(tm, t, n, idx, pos));
            }
            mi.recognized = true;
            mi.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            mi.recognized = false;
        }
        return mi;
    }

    public int totalMorphs() {
        int n = 0;
        for(Frame f : frames)
            n += f.vertices;
        return n;
    }
}
