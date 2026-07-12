package resforge.gui;

import resforge.model.ModelGeometry;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Dependency-free software 3D preview. Swing painting only displays the latest
 * completed frame; texture decoding and triangle rasterisation happen on workers.
 */
final class Model3DView extends JPanel {
    private static final Color BG = new Color(43, 43, 43);
    private static final int[] BASE_RGB = {200, 206, 214};

    private final ModelGeometry geo;
    private final DecodedPalette palette;
    private final int[] matOrd;
    private final Executor renderWorker;
    private final Executor edt;
    private final ExecutorService ownedWorker;
    private final long rasterWorkBudget;
    private final AtomicLong renderGeneration = new AtomicLong();

    private double yaw = Math.toRadians(35);
    private double pitch = Math.toRadians(20);
    private double dist;
    private double panX, panY;
    private boolean shaded = true;
    private boolean wireframe;
    private boolean textured;
    private int lastX, lastY;
    private volatile BufferedImage cachedImage;
    private volatile String cachedFailure;
    private volatile float[] animatedPositions;
    private volatile float[] animatedNormals;
    private volatile boolean disposed;

    Model3DView(ModelGeometry geo, DecodedPalette palette) throws PreviewFailure {
        this(geo, palette, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "model-3d-render");
            t.setDaemon(true);
            return t;
        }), SwingUtilities::invokeLater, PreviewBudget.MAX_RASTER_WORK, true);
    }

    Model3DView(ModelGeometry geo, DecodedPalette palette, Executor renderWorker, Executor edt)
            throws PreviewFailure {
        this(geo, palette, renderWorker, edt, PreviewBudget.MAX_RASTER_WORK, false);
    }

    Model3DView(ModelGeometry geo, DecodedPalette palette, Executor renderWorker, Executor edt,
                long rasterWorkBudget) throws PreviewFailure {
        this(geo, palette, renderWorker, edt, rasterWorkBudget, false);
    }

    private Model3DView(ModelGeometry geo, DecodedPalette palette, Executor renderWorker,
                        Executor edt, long rasterWorkBudget, boolean ownsWorker)
            throws PreviewFailure {
        checkTriangleBudget(geo);
        if(rasterWorkBudget < 0)
            throw new IllegalArgumentException("raster-work budget must not be negative");
        this.geo = geo;
        this.palette = palette;
        this.renderWorker = renderWorker;
        this.edt = edt;
        this.ownedWorker = ownsWorker ? (ExecutorService) renderWorker : null;
        this.rasterWorkBudget = rasterWorkBudget;
        this.dist = geo.radius * 3.0;
        this.matOrd = new int[geo.materials.size()];
        for(int i = 0; i < matOrd.length; i++)
            matOrd[i] = geo.materials.get(i).defaultTex;
        this.textured = geo.hasTextures();
        setPreferredSize(UiScaling.scale(640, 520));
        setBackground(BG);
        installInput();
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                requestRender();
            }
        });
    }

    /** Fully decode a model's combined local/external texture palette on a model worker. */
    static DecodedPalette preparePalette(ModelGeometry geo) throws PreviewFailure {
        checkTriangleBudget(geo);
        int local = geo.localTextures.size();
        int total = local + geo.externalTextures.size();
        checkPaletteEntries(total);
        Texture[] textures = new Texture[total];
        long usedPixels = 0;
        for(int i = 0; i < total; i++) {
            byte[] colorBytes = i < local
                    ? geo.localTextures.get(i) : geo.externalTextures.get(i - local);
            byte[] maskBytes = i < local
                    ? geo.localMasks.get(i) : geo.externalMasks.get(i - local);
            BufferedImage color = null;
            BufferedImage mask = null;
            if(colorBytes != null) {
                String kind = "3D texture " + i;
                PreviewBudget.Dimensions dimensions = PreviewBudget.dimensions(colorBytes, kind);
                usedPixels = addPalettePixels(usedPixels, dimensions.pixels());
                color = PreviewBudget.decode(colorBytes, kind);
            }
            if(maskBytes != null) {
                String kind = "3D texture mask " + i;
                PreviewBudget.Dimensions dimensions = PreviewBudget.dimensions(maskBytes, kind);
                usedPixels = addPalettePixels(usedPixels, dimensions.pixels());
                mask = PreviewBudget.decode(maskBytes, kind);
            }
            textures[i] = new Texture(pixels(color), width(color), height(color),
                    pixels(mask), width(mask), height(mask));
        }
        return new DecodedPalette(textures, usedPixels);
    }

    static void checkPaletteEntries(int entries) throws PreviewFailure {
        if(entries > PreviewBudget.MAX_PALETTE_ENTRIES)
            throw new PreviewFailure("3D preview texture palette exceeds the entry limit of "
                    + PreviewBudget.MAX_PALETTE_ENTRIES + " (" + entries + ")");
    }

    static long addPalettePixels(long used, long added) throws PreviewFailure {
        if(used > PreviewBudget.MAX_PALETTE_PIXELS - added)
            throw new PreviewFailure("3D preview texture palette exceeds the cumulative pixel limit of "
                    + PreviewBudget.MAX_PALETTE_PIXELS);
        return used + added;
    }

    private static int[] pixels(BufferedImage image) {
        return image == null ? null
                : image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static int width(BufferedImage image) {
        return image == null ? 0 : image.getWidth();
    }

    private static int height(BufferedImage image) {
        return image == null ? 0 : image.getHeight();
    }

    static void checkTriangleBudget(ModelGeometry geo) throws PreviewFailure {
        checkTriangleCount(geo.triangleCount);
    }

    static void checkTriangleCount(int triangles) throws PreviewFailure {
        if(triangles > PreviewBudget.MAX_RENDER_TRIANGLES)
            throw new PreviewFailure("3D preview exceeds the triangle limit of "
                    + PreviewBudget.MAX_RENDER_TRIANGLES + " (" + triangles + ")");
    }

    private void installInput() {
        java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                lastX = e.getX();
                lastY = e.getY();
            }

            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                int dx = e.getX() - lastX;
                int dy = e.getY() - lastY;
                lastX = e.getX();
                lastY = e.getY();
                boolean pan = SwingUtilities.isRightMouseButton(e)
                        || SwingUtilities.isMiddleMouseButton(e) || e.isShiftDown();
                if(pan) {
                    panX -= dx * dist / Math.max(1, getHeight());
                    panY += dy * dist / Math.max(1, getHeight());
                } else {
                    yaw += dx * 0.01;
                    pitch += dy * 0.01;
                    double limit = Math.toRadians(89);
                    pitch = Math.max(-limit, Math.min(limit, pitch));
                }
                requestRender();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(e -> {
            double factor = Math.pow(1.1, e.getPreciseWheelRotation());
            dist = Math.max(geo.radius * 0.05, Math.min(geo.radius * 50, dist * factor));
            requestRender();
        });
    }

    void setShaded(boolean value) {
        shaded = value;
        requestRender();
    }

    void setWireframe(boolean value) {
        wireframe = value;
        requestRender();
    }

    void setTextured(boolean value) {
        textured = value;
        requestRender();
    }

    boolean hasTextures() {
        return geo.hasTextures();
    }

    void setMaterialTexture(int matIndex, int paletteOrd) {
        if(matIndex >= 0 && matIndex < matOrd.length) {
            matOrd[matIndex] = paletteOrd;
            requestRender();
        }
    }

    void resetView() {
        yaw = Math.toRadians(35);
        pitch = Math.toRadians(20);
        dist = geo.radius * 3.0;
        panX = panY = 0;
        requestRender();
    }

    void setAnimatedGeometry(float[] positions, float[] normals) {
        if(positions == null || normals == null
                || positions.length != geo.positions.length || normals.length != geo.normals.length)
            throw new IllegalArgumentException("animated geometry does not match the model");
        animatedPositions = positions;
        animatedNormals = normals;
        requestRender();
    }

    void clearAnimatedGeometry() {
        animatedPositions = null;
        animatedNormals = null;
        requestRender();
    }

    private void requestRender() {
        requestRender(getWidth(), getHeight());
    }

    long requestRender(int displayWidth, int displayHeight) {
        long generation = renderGeneration.incrementAndGet();
        if(disposed || displayWidth <= 0 || displayHeight <= 0)
            return generation;
        RenderState state = snapshot(displayWidth, displayHeight);
        renderWorker.execute(() -> {
            if(!isCurrent(generation))
                return;
            BufferedImage image;
            try {
                image = new Renderer(geo, palette, state,
                        () -> !isCurrent(generation) || Thread.currentThread().isInterrupted(),
                        rasterWorkBudget).render();
            } catch(CancellationException ignored) {
                return;
            } catch(RuntimeException failure) {
                if(!isCurrent(generation))
                    return;
                String message = failure instanceof PreviewFailure
                        ? failure.getMessage()
                        : "3D preview rendering failed: " + (failure.getMessage() != null
                        ? failure.getMessage() : failure.getClass().getSimpleName());
                edt.execute(() -> {
                    if(isCurrent(generation)) {
                        cachedImage = null;
                        cachedFailure = message;
                        repaint();
                    }
                });
                return;
            }
            if(!isCurrent(generation))
                return;
            edt.execute(() -> {
                if(isCurrent(generation)) {
                    cachedImage = image;
                    cachedFailure = null;
                    repaint();
                }
            });
        });
        return generation;
    }

    private RenderState snapshot(int displayWidth, int displayHeight) {
        int[] framebuffer = PreviewBudget.framebufferSize(displayWidth, displayHeight);
        float[] positions = animatedPositions;
        float[] normals = animatedNormals;
        return new RenderState(yaw, pitch, dist, panX, panY, shaded, wireframe, textured,
                matOrd, positions != null ? positions : geo.positions,
                normals != null ? normals : geo.normals, framebuffer[0], framebuffer[1]);
    }

    private boolean isCurrent(long generation) {
        return !disposed && renderGeneration.get() == generation;
    }

    BufferedImage renderForTest(int width, int height) {
        return renderForTest(width, height, () -> false, PreviewBudget.MAX_RASTER_WORK);
    }

    BufferedImage renderForTest(int width, int height, BooleanSupplier cancelled, long workBudget) {
        return new Renderer(geo, palette, snapshot(width, height), cancelled, workBudget).render();
    }

    BufferedImage cachedImageForTest() {
        return cachedImage;
    }

    String cachedFailureForTest() {
        return cachedFailure;
    }

    long generationForTest() {
        return renderGeneration.get();
    }

    void dispose() {
        disposed = true;
        renderGeneration.incrementAndGet();
        cachedImage = null;
        cachedFailure = null;
        animatedPositions = null;
        animatedNormals = null;
        if(ownedWorker != null)
            ownedWorker.shutdownNow();
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        paintCached(g, getWidth(), getHeight());
    }

    void paintCached(Graphics g, int width, int height) {
        BufferedImage image = cachedImage;
        if(image != null)
            g.drawImage(image, 0, 0, width, height, null);
        else if(cachedFailure != null) {
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(cachedFailure, UiScaling.scale(12), UiScaling.scale(24));
        }
    }

    static float perspectiveDepth(double l0, double l1, double l2, double[] z) {
        return (float) (1.0 / (l0 / z[0] + l1 / z[1] + l2 / z[2]));
    }

    static final class DecodedPalette {
        private final Texture[] textures;
        private final long pixels;

        private DecodedPalette(Texture[] textures, long pixels) {
            this.textures = textures.clone();
            this.pixels = pixels;
        }

        static DecodedPalette empty() {
            return new DecodedPalette(new Texture[0], 0);
        }

        int size() {
            return textures.length;
        }

        long pixels() {
            return pixels;
        }
    }

    private record Texture(int[] color, int width, int height,
                           int[] mask, int maskWidth, int maskHeight) {
    }

    private record RenderState(double yaw, double pitch, double dist, double panX, double panY,
                               boolean shaded, boolean wireframe, boolean textured,
                               int[] matOrd, float[] positions, float[] normals,
                               int width, int height) {
        private RenderState {
            matOrd = matOrd.clone();
        }
    }

    private static final class Renderer {
        private final ModelGeometry geo;
        private final DecodedPalette palette;
        private final RenderState state;
        private final BufferedImage image;
        private final int[] pix;
        private final float[] zbuf;
        private final RasterGuard guard;

        Renderer(ModelGeometry geo, DecodedPalette palette, RenderState state,
                 BooleanSupplier cancelled, long workBudget) {
            this.geo = geo;
            this.palette = palette;
            this.state = state;
            this.guard = new RasterGuard(cancelled, workBudget);
            this.image = new BufferedImage(state.width, state.height, BufferedImage.TYPE_INT_RGB);
            this.pix = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            this.zbuf = new float[state.width * state.height];
        }

        BufferedImage render() {
            guard.check();
            Arrays.fill(pix, BG.getRGB());
            guard.check();
            Arrays.fill(zbuf, Float.POSITIVE_INFINITY);
            double cp = Math.cos(state.pitch), sp = Math.sin(state.pitch);
            double cy = Math.cos(state.yaw), sinYaw = Math.sin(state.yaw);
            double[] dir = {cp * cy, cp * sinYaw, sp};
            double[] right = norm(cross(dir, new double[]{0, 0, 1}));
            double[] up = norm(cross(right, dir));
            double[] center = {geo.center[0], geo.center[1], geo.center[2]};
            double[] pan = {
                    right[0] * state.panX + up[0] * state.panY,
                    right[1] * state.panX + up[1] * state.panY,
                    right[2] * state.panX + up[2] * state.panY};
            double[] target = {center[0] + pan[0], center[1] + pan[1], center[2] + pan[2]};
            double[] eye = {target[0] + dir[0] * state.dist,
                    target[1] + dir[1] * state.dist, target[2] + dir[2] * state.dist};
            double[] forward = {-dir[0], -dir[1], -dir[2]};
            double focal = (state.height / 2.0) / Math.tan(Math.toRadians(45) / 2);
            double near = geo.radius * 0.01;
            double[] light = norm(new double[]{eye[0] - target[0],
                    eye[1] - target[1], eye[2] - target[2]});

            float[] positions = state.positions;
            float[] normals = state.normals;
            float[] uv = geo.uv;
            double[] sx = new double[3], sy = new double[3], sz = new double[3];
            double[] intensity = new double[3], tu = new double[3], tv = new double[3];
            for(int t = 0; t < positions.length; t += 9) {
                guard.work(1);
                int triangle = t / 9;
                int material = state.textured && triangle < geo.triMat.length
                        ? geo.triMat[triangle] : -1;
                int slot = material >= 0 && material < state.matOrd.length
                        ? state.matOrd[material] : -1;
                Texture texture = slot >= 0 && slot < palette.textures.length
                        ? palette.textures[slot] : null;
                boolean useTexture = texture != null && texture.color != null;
                boolean visible = true;
                for(int k = 0; k < 3; k++) {
                    int base = t + k * 3;
                    double dx = positions[base] - eye[0];
                    double dy = positions[base + 1] - eye[1];
                    double dz = positions[base + 2] - eye[2];
                    double vz = dx * forward[0] + dy * forward[1] + dz * forward[2];
                    if(vz <= near) {
                        visible = false;
                        break;
                    }
                    double vx = dx * right[0] + dy * right[1] + dz * right[2];
                    double vy = dx * up[0] + dy * up[1] + dz * up[2];
                    sx[k] = state.width / 2.0 + vx * focal / vz;
                    sy[k] = state.height / 2.0 - vy * focal / vz;
                    sz[k] = vz;
                    if(state.shaded) {
                        double dot = normals[base] * light[0] + normals[base + 1] * light[1]
                                + normals[base + 2] * light[2];
                        intensity[k] = 0.28 + 0.72 * Math.abs(dot);
                        if(useTexture) {
                            tu[k] = uv[triangle * 6 + k * 2];
                            tv[k] = uv[triangle * 6 + k * 2 + 1];
                        }
                    }
                }
                if(!visible)
                    continue;
                if(state.shaded)
                    fillTriangle(sx, sy, sz, intensity, useTexture ? texture : null, tu, tv);
                if(state.wireframe)
                    drawTriEdges(sx, sy);
            }
            guard.check();
            return image;
        }

        private void fillTriangle(double[] x, double[] y, double[] z, double[] intensity,
                                  Texture texture, double[] u, double[] v) {
            double x0 = x[0], y0 = y[0], x1 = x[1], y1 = y[1], x2 = x[2], y2 = y[2];
            double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
            if(Math.abs(area) < 1e-7)
                return;
            int minX = (int) Math.max(0, Math.floor(Math.min(x0, Math.min(x1, x2))));
            int maxX = (int) Math.min(state.width - 1, Math.ceil(Math.max(x0, Math.max(x1, x2))));
            int minY = (int) Math.max(0, Math.floor(Math.min(y0, Math.min(y1, y2))));
            int maxY = (int) Math.min(state.height - 1, Math.ceil(Math.max(y0, Math.max(y1, y2))));
            double inverseArea = 1.0 / area;
            double iz0 = 1 / z[0], iz1 = 1 / z[1], iz2 = 1 / z[2];
            double uoz0 = 0, uoz1 = 0, uoz2 = 0, voz0 = 0, voz1 = 0, voz2 = 0;
            if(texture != null) {
                uoz0 = u[0] * iz0; uoz1 = u[1] * iz1; uoz2 = u[2] * iz2;
                voz0 = v[0] * iz0; voz1 = v[1] * iz1; voz2 = v[2] * iz2;
            }
            for(int py = minY; py <= maxY; py++) {
                guard.check();
                for(int px = minX; px <= maxX; px++) {
                    guard.work(1);
                    double cx = px + 0.5, cy = py + 0.5;
                    double l0 = ((x1 - cx) * (y2 - cy) - (x2 - cx) * (y1 - cy)) * inverseArea;
                    double l1 = ((x2 - cx) * (y0 - cy) - (x0 - cx) * (y2 - cy)) * inverseArea;
                    double l2 = 1 - l0 - l1;
                    if(l0 < 0 || l1 < 0 || l2 < 0)
                        continue;
                    float depth = perspectiveDepth(l0, l1, l2, z);
                    int index = py * state.width + px;
                    if(depth >= zbuf[index])
                        continue;
                    double brightness = l0 * intensity[0] + l1 * intensity[1] + l2 * intensity[2];
                    int red, green, blue;
                    if(texture != null) {
                        double inverseDepth = l0 * iz0 + l1 * iz1 + l2 * iz2;
                        double uu = (l0 * uoz0 + l1 * uoz1 + l2 * uoz2) / inverseDepth;
                        double vv = (l0 * voz0 + l1 * voz1 + l2 * voz2) / inverseDepth;
                        uu -= Math.floor(uu);
                        vv -= Math.floor(vv);
                        if(texture.mask != null) {
                            int mx = Math.min(texture.maskWidth - 1, (int) (uu * texture.maskWidth));
                            int my = Math.min(texture.maskHeight - 1, (int) (vv * texture.maskHeight));
                            if((texture.mask[my * texture.maskWidth + mx] & 0xff) < 128)
                                continue;
                        }
                        int tx = Math.min(texture.width - 1, (int) (uu * texture.width));
                        int ty = Math.min(texture.height - 1, (int) (vv * texture.height));
                        int argb = texture.color[ty * texture.width + tx];
                        if(texture.mask == null && ((argb >>> 24) & 0xff) < 128)
                            continue;
                        red = (argb >> 16) & 0xff;
                        green = (argb >> 8) & 0xff;
                        blue = argb & 0xff;
                    } else {
                        red = BASE_RGB[0];
                        green = BASE_RGB[1];
                        blue = BASE_RGB[2];
                    }
                    zbuf[index] = depth;
                    pix[index] = (clamp((int) (red * brightness)) << 16)
                            | (clamp((int) (green * brightness)) << 8)
                            | clamp((int) (blue * brightness));
                }
            }
        }

        private void drawTriEdges(double[] x, double[] y) {
            line(x[0], y[0], x[1], y[1]);
            line(x[1], y[1], x[2], y[2]);
            line(x[2], y[2], x[0], y[0]);
        }

        private void line(double x0, double y0, double x1, double y1) {
            int ix0 = (int) Math.round(x0), iy0 = (int) Math.round(y0);
            int ix1 = (int) Math.round(x1), iy1 = (int) Math.round(y1);
            int dx = Math.abs(ix1 - ix0), dy = -Math.abs(iy1 - iy0);
            int sx = ix0 < ix1 ? 1 : -1, sy = iy0 < iy1 ? 1 : -1;
            int error = dx + dy;
            while(true) {
                guard.work(1);
                if(ix0 >= 0 && ix0 < state.width && iy0 >= 0 && iy0 < state.height) {
                    int index = iy0 * state.width + ix0;
                    int color = pix[index];
                    pix[index] = ((((color >> 16) & 0xff) / 3) << 16)
                            | ((((color >> 8) & 0xff) / 3) << 8) | ((color & 0xff) / 3);
                }
                if(ix0 == ix1 && iy0 == iy1)
                    break;
                int twice = 2 * error;
                if(twice >= dy) {
                    error += dy;
                    ix0 += sx;
                }
                if(twice <= dx) {
                    error += dx;
                    iy0 += sy;
                }
            }
        }

        private static final class RasterGuard {
            private final BooleanSupplier cancelled;
            private final long limit;
            private long used;

            RasterGuard(BooleanSupplier cancelled, long limit) {
                this.cancelled = cancelled;
                this.limit = limit;
            }

            void check() {
                if(cancelled.getAsBoolean())
                    throw new CancellationException("3D preview rendering cancelled");
            }

            void work(long amount) {
                check();
                if(amount < 0 || used > limit - amount)
                    throw new PreviewFailure("3D preview exceeds the cumulative raster-work limit of "
                            + limit);
                used += amount;
            }
        }
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] norm(double[] value) {
        double length = Math.sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2]);
        if(length < 1e-12)
            return new double[]{0, 0, 1};
        return new double[]{value[0] / length, value[1] / length, value[2] / length};
    }
}
