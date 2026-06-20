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

        Frame(float time, int fmt, int vertices) {
            this.time = time;
            this.fmt = fmt;
            this.vertices = vertices;
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
                if(t == 4)
                    for(int k = 0; k < 6; k++)
                        in.float16();                  // per-frame quantisation bounds
                int i = 0;
                while(i < n) {
                    in.uint16();                       // span start
                    int run = in.uint16();
                    for(int o = 0; o < run; o++) {
                        switch(t) {
                            case 1: for(int k = 0; k < 6; k++) in.float32(); break;
                            case 2: in.int32(); break;
                            case 3: for(int k = 0; k < 3; k++) in.float16(); break;
                            case 4: for(int k = 0; k < 3; k++) in.uint8(); break;
                            default: throw new IllegalStateException("meshanim frame format " + t);
                        }
                        i++;
                    }
                }
                mi.frames.add(new Frame(tm, t, n));
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
