package resforge.gui;

import resforge.io.MessageWriter;
import resforge.model.ModelGeometry;
import resforge.res.Layer;
import resforge.res.ResContainer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the software 3D view off-screen and checks that the per-material
 * texture picker actually changes what is drawn (the mulberry "pick a seasonal
 * leaf" feature). Two solid-colour textures stand in for the variants.
 */
class Model3DViewTest {

    private static byte[] solidPng(Color c) throws Exception {
        BufferedImage bi = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 8, 8);
        g.dispose();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", bo);
        return bo.toByteArray();
    }

    private static byte[] tex(int id, byte[] png) {
        MessageWriter w = new MessageWriter();
        w.int16(id).uint16(0).uint16(0).uint16(8).uint16(8);
        w.uint8(0).int32(png.length).bytes(png);
        return w.toByteArray();
    }

    private static byte[] mat2Local(int id, int texId) {
        MessageWriter w = new MessageWriter();
        w.uint16(id);
        w.string("tex").uint8(4).uint8(texId).uint8(0);
        return w.toByteArray();
    }

    private static byte[] vbufTex() {
        MessageWriter w = new MessageWriter();
        w.uint8(0);
        w.uint16(3);
        w.string("pos2").uint8(1).string("f4");
        for(float v : new float[]{-1, -1, 0, 1, -1, 0, 0, 1, 0})
            w.float32(v);
        w.string("nrm2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1})
            w.float32(v);
        w.string("tex2").uint8(1).string("f4");
        for(float v : new float[]{0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f})
            w.float32(v);
        return w.toByteArray();
    }

    private static byte[] meshMat(int matid) {
        MessageWriter w = new MessageWriter();
        w.uint8(16);
        w.uint16(1);
        w.int16(matid);
        w.int16(0);
        w.uint16(0).uint16(1).uint16(2);
        return w.toByteArray();
    }

    /** Render a model into a w×h ARGB array via the real paint path. */
    private static int[] render(ModelGeometry geo, int w, int h, java.util.function.Consumer<Model3DView> cfg) {
        Model3DView view = new Model3DView(geo);
        view.setSize(w, h);
        cfg.accept(view);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        view.paintComponent(g);
        g.dispose();
        return out.getRGB(0, 0, w, h, null, 0, w);
    }

    private static int reddish(int[] px) {
        int n = 0;
        for(int p : px) { int r = (p >> 16) & 0xff, b = p & 0xff; if(r > b + 30) n++; }
        return n;
    }

    private static int bluish(int[] px) {
        int n = 0;
        for(int p : px) { int r = (p >> 16) & 0xff, b = p & 0xff; if(b > r + 30) n++; }
        return n;
    }

    @Test
    void perMaterialTexturePickerChangesTheRenderedTexture() throws Exception {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(1, solidPng(new Color(220, 20, 20)))));   // ordinal 0: red (default)
        res.layers.add(new Layer("tex", tex(3, solidPng(new Color(20, 20, 220)))));   // ordinal 1: blue (alternate)
        res.layers.add(new Layer("mat2", mat2Local(9, 1)));   // matid 9 -> tex id 1 (red)
        res.layers.add(new Layer("vbuf2", vbufTex()));
        res.layers.add(new Layer("mesh", meshMat(9)));

        ModelGeometry geo = ModelGeometry.from(res);
        assertEquals(1, geo.materials.size());

        // Default selection (authored): the red texture is visible, no blue.
        int[] dflt = render(geo, 120, 120, v -> {});
        assertTrue(reddish(dflt) > 0, "the authored (red) texture should be drawn");
        assertEquals(0, bluish(dflt), "no blue before switching");

        // Switch material 0 to the alternate (blue, palette ordinal 1): now blue, no red.
        int[] swapped = render(geo, 120, 120, v -> v.setMaterialTexture(0, 1));
        assertTrue(bluish(swapped) > 0, "after picking the alternate, the blue texture is drawn");
        assertEquals(0, reddish(swapped), "the red texture is gone once switched");
    }

    @Test
    void obliqueTriangleDepthBeatsFlatTriangleAtCentroid() {
        double oneThird = 1.0 / 3.0;
        double[] oblique = {1.0, 10.0, 10.0};
        double[] flat = {3.0, 3.0, 3.0};

        double incorrectLinearOblique = (oblique[0] + oblique[1] + oblique[2]) / 3.0;
        assertTrue(incorrectLinearOblique > 3.0,
                "linear interpolation would incorrectly put the flat triangle in front");

        float obliqueDepth =
                Model3DView.perspectiveDepth(oneThird, oneThird, oneThird, oblique);
        float flatDepth =
                Model3DView.perspectiveDepth(oneThird, oneThird, oneThird, flat);
        assertEquals(2.5f, obliqueDepth, 1e-6f);
        assertEquals(3.0f, flatDepth, 1e-6f);
        assertTrue(obliqueDepth < flatDepth,
                "reciprocal depth correctly puts the oblique triangle in front");
    }
}
