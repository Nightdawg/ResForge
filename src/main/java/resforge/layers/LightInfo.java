package resforge.layers;

import resforge.io.MessageReader;

/**
 * Read-only decoder for the {@code light} layer (from haven.Light.Res): a light
 * source attached to a resource. Version 0 stores colours and parameters as
 * custom-packed floats ({@code cpfloat}); version 1 uses plain {@code float32}.
 *
 * <p>Fields: an id, three RGBA colours (ambient / diffuse / specular), and
 * optional tagged extras — attenuation ({@code ac}, {@code al}, {@code aq}), a
 * direction vector, and a spotlight exponent. The presence of attenuation and/or
 * a direction is what makes it a point, spot or directional light.
 *
 * <p>This is informational only; the layer stays raw/lossless.
 */
public final class LightInfo {
    public boolean recognized;
    public boolean reachedEnd;
    public int ver;
    public int id;
    public int[] amb, dif, spc;       // RGBA 0..255
    public boolean hasAtt, hasDir, hasExp;
    public float ac, al, aq, exp;
    public float dx, dy, dz;

    private static int[] colorCp(MessageReader in) {
        return new int[]{
                (int) (in.cpfloat() * 255.0), (int) (in.cpfloat() * 255.0),
                (int) (in.cpfloat() * 255.0), (int) (in.cpfloat() * 255.0)};
    }

    private static int[] colorF32(MessageReader in) {
        return new int[]{
                (int) (in.float32() * 255.0), (int) (in.float32() * 255.0),
                (int) (in.float32() * 255.0), (int) (in.float32() * 255.0)};
    }

    public static LightInfo parse(byte[] payload) {
        LightInfo li = new LightInfo();
        try {
            MessageReader in = new MessageReader(payload);
            li.ver = in.uint8();
            boolean cp = (li.ver == 0);
            if(li.ver == 0)
                li.id = in.int8();
            else if(li.ver == 1)
                li.id = in.int16();
            else
                throw new IllegalStateException("light version " + li.ver);
            li.amb = cp ? colorCp(in) : colorF32(in);
            li.dif = cp ? colorCp(in) : colorF32(in);
            li.spc = cp ? colorCp(in) : colorF32(in);
            while(!in.eom()) {
                int t = in.uint8();
                if(t == 1) {
                    li.hasAtt = true;
                    li.ac = num(in, cp);
                    li.al = num(in, cp);
                    li.aq = num(in, cp);
                } else if(t == 2) {
                    li.hasDir = true;
                    li.dx = num(in, cp);
                    li.dy = num(in, cp);
                    li.dz = num(in, cp);
                } else if(t == 3) {
                    li.hasExp = true;
                    li.exp = num(in, cp);
                } else {
                    throw new IllegalStateException("light data tag " + t);
                }
            }
            li.recognized = true;
            li.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            li.recognized = false;
        }
        return li;
    }

    private static float num(MessageReader in, boolean cp) {
        return cp ? (float) in.cpfloat() : in.float32();
    }

    /** The kind of light implied by the present fields. */
    public String kind() {
        if(hasAtt)
            return hasExp ? "spotlight" : "point light";
        return "directional light";
    }
}
