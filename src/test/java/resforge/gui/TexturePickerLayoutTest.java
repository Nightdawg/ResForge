package resforge.gui;

import resforge.io.MessageWriter;
import resforge.model.ModelGeometry;
import resforge.res.Layer;
import resforge.res.ResContainer;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layout tests for the View 3D per-material texture-picker rows. */
class TexturePickerLayoutTest {

    private static byte[] tex(int id, byte marker) {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, marker, 2};
        MessageWriter w = new MessageWriter();
        w.int16(id).uint16(0).uint16(0).uint16(64).uint16(64);
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
        for(float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})
            w.float32(v);
        w.string("nrm2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1})
            w.float32(v);
        w.string("tex2").uint8(1).string("f4");
        for(int i = 0; i < 6; i++)
            w.float32(0.5f);
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

    /** A mat2 with an external mlink base + a local otex overlay (variable/external). */
    private static byte[] mat2Variable(int id, int otexId) {
        MessageWriter w = new MessageWriter();
        w.uint16(id);
        w.string("mlink").uint8(2).string("gfx/x/peartree-tex").uint8(0);
        w.string("otex").uint8(4).uint8(otexId).uint8(0);
        return w.toByteArray();
    }

    /** A model with {@code nMaterials} textured materials and {@code nTex} local textures. */
    private static ModelGeometry geo(int nMaterials, int nTex) {
        ResContainer res = new ResContainer(7);
        for(int i = 0; i < nTex; i++)
            res.layers.add(new Layer("tex", tex(i + 1, (byte) (0xA0 + i))));
        for(int i = 0; i < nMaterials; i++) {
            res.layers.add(new Layer("mat2", mat2Local(10 + i, 1)));   // each -> tex id 1
            res.layers.add(new Layer("mesh", meshMat(10 + i)));
        }
        res.layers.add(new Layer("vbuf2", vbufTex()));
        return ModelGeometry.from(res);
    }

    private static int combosIn(JPanel p) {
        int n = 0;
        for(Component c : p.getComponents())
            if(c instanceof JComboBox)
                n++;
        return n;
    }

    @Test
    void manyMaterialsSplitOverTwoBalancedRows() {
        ModelGeometry g = geo(4, 2);
        assertEquals(4, g.materials.size());
        List<JComboBox<Integer>> combos = new ArrayList<>();
        List<JPanel> rows = ResForgeFrame.buildTexturePickerRows(g,
                new Model3DView(g, Model3DView.DecodedPalette.empty()), combos);

        assertEquals(2, rows.size(), "four pickers should split across two rows");
        assertEquals(4, combos.size());
        // Balanced: ceil(4/2)=2 in the first row, 2 in the second.
        assertEquals(2, combosIn(rows.get(0)));
        assertEquals(2, combosIn(rows.get(1)));
        // The first row carries the caption.
        assertTrue(rows.get(0).getComponent(0) instanceof JLabel
                && "Texture:".equals(((JLabel) rows.get(0).getComponent(0)).getText()));
    }

    @Test
    void oddCountPutsTheExtraPickerOnTheFirstRow() {
        ModelGeometry g = geo(5, 2);
        List<JComboBox<Integer>> combos = new ArrayList<>();
        List<JPanel> rows = ResForgeFrame.buildTexturePickerRows(g,
                new Model3DView(g, Model3DView.DecodedPalette.empty()), combos);
        assertEquals(2, rows.size());
        assertEquals(3, combosIn(rows.get(0)), "ceil(5/2)=3 on the first row");
        assertEquals(2, combosIn(rows.get(1)));
    }

    @Test
    void singleMaterialStaysOnOneRow() {
        ModelGeometry g = geo(1, 2);
        List<JComboBox<Integer>> combos = new ArrayList<>();
        List<JPanel> rows = ResForgeFrame.buildTexturePickerRows(g,
                new Model3DView(g, Model3DView.DecodedPalette.empty()), combos);
        assertEquals(1, rows.size());
        assertEquals(1, combos.size());
    }

    @Test
    void noChoiceMeansNoRows() {
        ModelGeometry g = geo(3, 1);   // 3 materials but only one local texture -> nothing to pick
        List<JComboBox<Integer>> combos = new ArrayList<>();
        List<JPanel> rows = ResForgeFrame.buildTexturePickerRows(g,
                new Model3DView(g, Model3DView.DecodedPalette.empty()), combos);
        assertTrue(rows.isEmpty());
        assertTrue(combos.isEmpty());
    }

    @Test
    void variableExternalMaterialsGetNoPicker() {
        // Two local-base materials + two variable/external ones, with two textures to choose.
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(1, (byte) 0xA1)));
        res.layers.add(new Layer("tex", tex(2, (byte) 0xA2)));
        res.layers.add(new Layer("mat2", mat2Local(10, 1)));      // local base
        res.layers.add(new Layer("mat2", mat2Local(11, 1)));      // local base
        res.layers.add(new Layer("mat2", mat2Variable(12, 1)));   // variable/external
        res.layers.add(new Layer("mat2", mat2Variable(13, 1)));   // variable/external
        for(int matid : new int[]{10, 11, 12, 13})
            res.layers.add(new Layer("mesh", meshMat(matid)));
        res.layers.add(new Layer("vbuf2", vbufTex()));
        ModelGeometry g = ModelGeometry.from(res);
        assertEquals(4, g.materials.size(), "all four render (all resolve to some local texture)");

        List<JComboBox<Integer>> combos = new ArrayList<>();
        List<JPanel> rows = ResForgeFrame.buildTexturePickerRows(g,
                new Model3DView(g, Model3DView.DecodedPalette.empty()), combos);
        assertEquals(2, combos.size(), "only the two local-base materials get a picker");
        assertEquals(2, rows.size(), "two pickers split one per balanced row");
    }

    @Test
    void allVariableMeansNoRows() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(1, (byte) 0xA1)));
        res.layers.add(new Layer("tex", tex(2, (byte) 0xA2)));
        res.layers.add(new Layer("mat2", mat2Variable(12, 1)));
        res.layers.add(new Layer("mat2", mat2Variable(13, 1)));
        res.layers.add(new Layer("mesh", meshMat(12)));
        res.layers.add(new Layer("mesh", meshMat(13)));
        res.layers.add(new Layer("vbuf2", vbufTex()));
        ModelGeometry g = ModelGeometry.from(res);
        assertTrue(g.hasTextures(), "they still render (via the local otex overlay)");

        List<JComboBox<Integer>> combos = new ArrayList<>();
        List<JPanel> rows = ResForgeFrame.buildTexturePickerRows(g,
                new Model3DView(g, Model3DView.DecodedPalette.empty()), combos);
        assertTrue(rows.isEmpty(), "no local-base material -> no pickers");
        assertTrue(combos.isEmpty());
    }
}
