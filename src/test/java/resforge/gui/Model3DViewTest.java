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
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static ResContainer texturedResource() throws Exception {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(1, solidPng(new Color(220, 20, 20)))));
        res.layers.add(new Layer("tex", tex(3, solidPng(new Color(20, 20, 220)))));
        res.layers.add(new Layer("mat2", mat2Local(9, 1)));
        res.layers.add(new Layer("vbuf2", vbufTex()));
        res.layers.add(new Layer("mesh", meshMat(9)));
        return res;
    }

    private static ModelGeometry texturedGeometry() throws Exception {
        return ModelGeometry.from(texturedResource());
    }

    /** Render through the synchronous renderer seam, independent of worker timing. */
    private static int[] render(ModelGeometry geo, int w, int h, java.util.function.Consumer<Model3DView> cfg) {
        Model3DView view = new Model3DView(geo, Model3DView.preparePalette(geo));
        cfg.accept(view);
        BufferedImage out = view.renderForTest(w, h);
        view.dispose();
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
        ModelGeometry geo = texturedGeometry();
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
    void workerPublishesCachedFrameAndPaintOnlyDrawsIt() throws Exception {
        ModelGeometry geo = texturedGeometry();
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor edt = new ManualExecutor();
        Model3DView view = new Model3DView(
                geo, Model3DView.preparePalette(geo), worker, edt);

        view.requestRender(120, 120);
        assertNull(view.cachedImageForTest());
        worker.runNext();
        assertNull(view.cachedImageForTest(), "worker result is not installed before the EDT callback");
        edt.runNext();
        BufferedImage cached = view.cachedImageForTest();
        assertEquals(120, cached.getWidth());

        long generation = view.generationForTest();
        BufferedImage painted = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = painted.createGraphics();
        view.paintCached(graphics, 120, 120);
        view.paintCached(graphics, 120, 120);
        graphics.dispose();
        assertSame(cached, view.cachedImageForTest());
        assertEquals(generation, view.generationForTest());
        assertTrue(reddish(painted.getRGB(0, 0, 120, 120, null, 0, 120)) > 0);
        assertTrue(worker.isEmpty(), "painting must not enqueue raster work");
        view.dispose();
    }

    @Test
    void staleRenderGenerationCannotPublish() throws Exception {
        ModelGeometry geo = texturedGeometry();
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor edt = new ManualExecutor();
        Model3DView view = new Model3DView(
                geo, Model3DView.preparePalette(geo), worker, edt);

        view.requestRender(80, 80);
        view.requestRender(100, 90);
        worker.runNext();
        assertTrue(edt.isEmpty(), "superseded render must not enqueue publication");
        worker.runNext();
        edt.runNext();
        assertEquals(100, view.cachedImageForTest().getWidth());
        assertEquals(90, view.cachedImageForTest().getHeight());

        view.requestRender(60, 60);
        view.dispose();
        worker.runNext();
        assertTrue(edt.isEmpty(), "disposed views ignore late render completion");
    }

    @Test
    void animatedGeometrySupersedesBindGeometryInRenderSnapshot() throws Exception {
        ModelGeometry geo = texturedGeometry();
        Model3DView view = new Model3DView(geo, Model3DView.preparePalette(geo));
        int[] bind = view.renderForTest(120, 120).getRGB(0, 0, 120, 120, null, 0, 120);
        float[] moved = geo.positions.clone();
        for(int i = 0; i < moved.length; i += 3)
            moved[i] += 20;

        view.setAnimatedGeometry(moved, geo.normals.clone());
        int[] animated = view.renderForTest(120, 120).getRGB(0, 0, 120, 120, null, 0, 120);
        view.dispose();

        assertTrue(reddish(bind) > 0);
        assertEquals(0, reddish(animated), "the translated animated triangle leaves the camera view");
    }

    @Test
    void rendererCancelsDeterministicallyDuringRasterization() throws Exception {
        ModelGeometry geo = texturedGeometry();
        Model3DView view = new Model3DView(geo, Model3DView.preparePalette(geo));
        AtomicInteger checks = new AtomicInteger();

        assertThrows(CancellationException.class, () -> view.renderForTest(
                120, 120, () -> checks.incrementAndGet() > 20,
                PreviewBudget.MAX_RASTER_WORK));

        assertTrue(checks.get() > 20);
        view.dispose();
    }

    @Test
    void rasterWorkFailurePublishesExplicitStateAndWorkerRemainsUsable() throws Exception {
        ModelGeometry geo = texturedGeometry();
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor edt = new ManualExecutor();
        Model3DView view = new Model3DView(
                geo, Model3DView.preparePalette(geo), worker, edt, 1);

        view.requestRender(120, 120);
        worker.runNext();
        edt.runNext();
        assertNull(view.cachedImageForTest());
        assertTrue(view.cachedFailureForTest().contains("raster-work limit"));

        view.requestRender(120, 120);
        worker.runNext();
        edt.runNext();
        assertTrue(view.cachedFailureForTest().contains("raster-work limit"),
                "a budget failure must not terminate subsequent worker tasks");
        view.dispose();
    }

    @Test
    void synchronousRendererEnforcesWorkBudgetWithoutPartialImage() throws Exception {
        ModelGeometry geo = texturedGeometry();
        Model3DView view = new Model3DView(geo, Model3DView.preparePalette(geo));

        PreviewFailure failure = assertThrows(PreviewFailure.class,
                () -> view.renderForTest(120, 120, () -> false, 1));

        assertTrue(failure.getMessage().contains("raster-work limit"));
        view.dispose();
    }

    @Test
    void geometryTriangleLimitIsCheckedBeforeSoupAllocation() throws Exception {
        ResContainer resource = texturedResource();
        assertNotNull(ModelGeometry.from(resource, null, 1));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ModelGeometry.from(resource, null, 0));
        assertTrue(failure.getMessage().contains("triangle limit"));
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

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }

        boolean isEmpty() {
            return tasks.isEmpty();
        }

    }
}
