package resforge.layers;

import resforge.io.MessageReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only decoder for the {@code boneoff} ("bone offset" / equip point) layer
 * (from haven.Skeleton.BoneOffset): a named program that positions an equipped
 * item relative to a skeleton. It is a short list of opcodes, each a small
 * transform (translate / rotate / equip-point reference / bone-alignment /
 * null-rotation / scale), composed in order.
 *
 * <p>Even/low opcodes use custom-packed floats ({@code cpfloat}); the +16/+1
 * variants use {@code float32} or quantised encodings. This decoder reports the
 * point name and a human-readable summary of each opcode; the layer stays
 * raw/lossless.
 */
public final class BoneOffInfo {
    public static final class Op {
        public final int code;
        public final String desc;

        Op(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }
    }

    public boolean recognized;
    public boolean reachedEnd;
    public String name;
    public final List<Op> ops = new ArrayList<>();

    public static BoneOffInfo parse(byte[] payload) {
        BoneOffInfo bo = new BoneOffInfo();
        try {
            MessageReader in = new MessageReader(payload);
            bo.name = in.string();
            while(!in.eom()) {
                int code = in.uint8();
                bo.ops.add(new Op(code, decodeOp(code, in)));
            }
            bo.recognized = true;
            bo.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            bo.recognized = false;
        }
        return bo;
    }

    private static String decodeOp(int code, MessageReader in) {
        switch(code) {
            case 0: {
                float x = (float) in.cpfloat(), y = (float) in.cpfloat(), z = (float) in.cpfloat();
                return String.format("translate (%.3f, %.3f, %.3f)", x, y, z);
            }
            case 16: {
                float x = in.float32(), y = in.float32(), z = in.float32();
                return String.format("translate (%.3f, %.3f, %.3f)", x, y, z);
            }
            case 1: {
                float ang = (float) in.cpfloat();
                float x = (float) in.cpfloat(), y = (float) in.cpfloat(), z = (float) in.cpfloat();
                return String.format("rotate %.3f rad about (%.3f, %.3f, %.3f)", ang, x, y, z);
            }
            case 17: {
                float ang = in.mnorm16() * 2 * (float) Math.PI;
                float[] ax = new float[3];
                MessageReader.oct2uvec(ax, in.snorm16(), in.snorm16());
                return String.format("rotate %.3f rad about (%.3f, %.3f, %.3f)", ang, ax[0], ax[1], ax[2]);
            }
            case 2: {
                String bone = in.string();
                return "equip point at bone \"" + bone + "\"";
            }
            case 3: {
                float x = (float) in.cpfloat(), y = (float) in.cpfloat(), z = (float) in.cpfloat();
                String orig = in.string(), tgt = in.string();
                return String.format("align (%.3f, %.3f, %.3f) of \"%s\" -> \"%s\"", x, y, z, orig, tgt);
            }
            case 19: {
                float[] r = new float[3];
                MessageReader.oct2uvec(r, in.snorm16(), in.snorm16());
                String orig = in.string(), tgt = in.string();
                return String.format("align (%.3f, %.3f, %.3f) of \"%s\" -> \"%s\"", r[0], r[1], r[2], orig, tgt);
            }
            case 4:
                return "null rotation";
            case 5:
                return String.format("scale %.3f", in.float32());
            default:
                throw new IllegalStateException("boneoff opcode " + code);
        }
    }
}
