package resforge.gui;

import resforge.model.ModelGeometry;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * A tiny dependency-free software 3D renderer (Java2D + a hand-written z-buffered
 * triangle rasteriser) for previewing a Haven model's geometry. No native libs,
 * no OpenGL — in keeping with the rest of the tool. Shows the model in its bind /
 * rest pose with two-sided Lambert shading from a head-light, an optional
 * wireframe overlay, and mouse orbit / zoom / pan.
 *
 * <p>Coordinates are Haven's native Z-up; the camera orbits with +Z as up.
 */
final class Model3DView extends JPanel {
    private final ModelGeometry geo;

    // Camera: spherical orbit around the model centre, plus a screen-space pan.
    private double yaw = Math.toRadians(35);
    private double pitch = Math.toRadians(20);
    private double dist;
    private double panX, panY;

    private boolean shaded = true;
    private boolean wireframe = false;

    private BufferedImage img;
    private int[] pix;
    private float[] zbuf;

    private int lastX, lastY;

    private static final Color BG = new Color(43, 43, 43);
    private static final int[] BASE_RGB = {200, 206, 214};   // diffuse model colour
    private static final Color WIRE = new Color(0, 0, 0, 90);

    Model3DView(ModelGeometry geo) {
        this.geo = geo;
        this.dist = geo.radius * 3.0;
        setPreferredSize(new Dimension(640, 520));
        setBackground(BG);

        java.awt.event.MouseAdapter m = new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                lastX = e.getX();
                lastY = e.getY();
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                int dx = e.getX() - lastX, dy = e.getY() - lastY;
                lastX = e.getX();
                lastY = e.getY();
                boolean pan = javax.swing.SwingUtilities.isRightMouseButton(e)
                        || javax.swing.SwingUtilities.isMiddleMouseButton(e)
                        || e.isShiftDown();
                if(pan) {
                    panX -= dx * dist / Math.max(1, getHeight());
                    panY += dy * dist / Math.max(1, getHeight());
                } else {
                    yaw -= dx * 0.01;
                    pitch += dy * 0.01;
                    double lim = Math.toRadians(89);
                    pitch = Math.max(-lim, Math.min(lim, pitch));
                }
                repaint();
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(e -> {
            double f = Math.pow(1.1, e.getPreciseWheelRotation());
            dist = Math.max(geo.radius * 0.05, Math.min(geo.radius * 50, dist * f));
            repaint();
        });
    }

    void setShaded(boolean b) { shaded = b; repaint(); }
    void setWireframe(boolean b) { wireframe = b; repaint(); }

    void resetView() {
        yaw = Math.toRadians(35);
        pitch = Math.toRadians(20);
        dist = geo.radius * 3.0;
        panX = panY = 0;
        repaint();
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth(), h = getHeight();
        if(w <= 0 || h <= 0)
            return;
        if(img == null || img.getWidth() != w || img.getHeight() != h) {
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            pix = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            zbuf = new float[w * h];
        }
        render(w, h);
        g.drawImage(img, 0, 0, null);
    }

    /* ---- camera basis (right/up/forward) for the current orbit ---- */

    private void render(int w, int h) {
        int bg = BG.getRGB();
        java.util.Arrays.fill(pix, bg);
        java.util.Arrays.fill(zbuf, Float.POSITIVE_INFINITY);

        // Eye position on a sphere around the centre (Z-up).
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double[] dir = {cp * cy, cp * sy, sp};                 // centre -> eye
        double[] up0 = {0, 0, 1};
        // right = normalize(dir x up0); trueUp = right x dir
        double[] right = norm(cross(dir, up0));
        double[] tup = norm(cross(right, dir));
        double[] center = {geo.center[0], geo.center[1], geo.center[2]};
        double[] panVec = {
                right[0] * panX + tup[0] * panY,
                right[1] * panX + tup[1] * panY,
                right[2] * panX + tup[2] * panY};
        double[] target = {center[0] + panVec[0], center[1] + panVec[1], center[2] + panVec[2]};
        double[] eye = {target[0] + dir[0] * dist, target[1] + dir[1] * dist, target[2] + dir[2] * dist};
        double[] fwd = {-dir[0], -dir[1], -dir[2]};            // eye -> target

        double fov = Math.toRadians(45);
        double focal = (h / 2.0) / Math.tan(fov / 2);
        double near = geo.radius * 0.01;

        // Head-light: from the viewer toward the model.
        double[] L = norm(new double[]{eye[0] - target[0], eye[1] - target[1], eye[2] - target[2]});

        float[] p = geo.positions;
        float[] nrm = geo.normals;
        double[] sx = new double[3], syc = new double[3], sz = new double[3];
        double[] inten = new double[3];

        for(int t = 0; t < p.length; t += 9) {
            boolean ok = true;
            for(int k = 0; k < 3; k++) {
                int b = t + k * 3;
                double dx = p[b] - eye[0], dy = p[b + 1] - eye[1], dz = p[b + 2] - eye[2];
                double vz = dx * fwd[0] + dy * fwd[1] + dz * fwd[2];       // depth along view
                if(vz <= near) { ok = false; break; }
                double vx = dx * right[0] + dy * right[1] + dz * right[2];
                double vy = dx * tup[0] + dy * tup[1] + dz * tup[2];
                sx[k] = w / 2.0 + (vx * focal / vz);
                syc[k] = h / 2.0 - (vy * focal / vz);
                sz[k] = vz;
                if(shaded) {
                    double d = nrm[b] * L[0] + nrm[b + 1] * L[1] + nrm[b + 2] * L[2];
                    inten[k] = 0.28 + 0.72 * Math.abs(d);                 // two-sided
                }
            }
            if(!ok)
                continue;
            if(shaded)
                fillTriangle(w, h, sx, syc, sz, inten);
            if(wireframe)
                drawTriEdges(w, h, sx, syc);
        }

        if(wireframe && !shaded) {
            // wireframe already drawn above
        }
        // Overlay wireframe colour blending is done per-line in drawTriEdges.
    }

    /* ---- z-buffered barycentric triangle fill with Gouraud intensity ---- */
    private void fillTriangle(int w, int h, double[] x, double[] y, double[] z, double[] in) {
        double x0 = x[0], y0 = y[0], x1 = x[1], y1 = y[1], x2 = x[2], y2 = y[2];
        double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if(Math.abs(area) < 1e-7)
            return;
        int minX = (int) Math.max(0, Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = (int) Math.min(w - 1, Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = (int) Math.max(0, Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = (int) Math.min(h - 1, Math.ceil(Math.max(y0, Math.max(y1, y2))));
        double inv = 1.0 / area;
        for(int py = minY; py <= maxY; py++) {
            for(int px = minX; px <= maxX; px++) {
                double cx = px + 0.5, cy = py + 0.5;
                double l0 = ((x1 - cx) * (y2 - cy) - (x2 - cx) * (y1 - cy)) * inv;
                double l1 = ((x2 - cx) * (y0 - cy) - (x0 - cx) * (y2 - cy)) * inv;
                double l2 = 1 - l0 - l1;
                if(l0 < 0 || l1 < 0 || l2 < 0)
                    continue;
                float depth = (float) (l0 * z[0] + l1 * z[1] + l2 * z[2]);
                int zi = py * w + px;
                if(depth >= zbuf[zi])
                    continue;
                zbuf[zi] = depth;
                double it = l0 * in[0] + l1 * in[1] + l2 * in[2];
                int r = clamp((int) (BASE_RGB[0] * it));
                int gg = clamp((int) (BASE_RGB[1] * it));
                int bb = clamp((int) (BASE_RGB[2] * it));
                pix[zi] = (r << 16) | (gg << 8) | bb;
            }
        }
    }

    private void drawTriEdges(int w, int h, double[] x, double[] y) {
        line(w, h, x[0], y[0], x[1], y[1]);
        line(w, h, x[1], y[1], x[2], y[2]);
        line(w, h, x[2], y[2], x[0], y[0]);
    }

    /* Bresenham-ish line into the pixel buffer (dark, semi-blended). */
    private void line(int w, int h, double x0, double y0, double x1, double y1) {
        int ix0 = (int) Math.round(x0), iy0 = (int) Math.round(y0);
        int ix1 = (int) Math.round(x1), iy1 = (int) Math.round(y1);
        int dx = Math.abs(ix1 - ix0), dy = -Math.abs(iy1 - iy0);
        int sx = ix0 < ix1 ? 1 : -1, sy = iy0 < iy1 ? 1 : -1;
        int err = dx + dy;
        while(true) {
            if(ix0 >= 0 && ix0 < w && iy0 >= 0 && iy0 < h) {
                int zi = iy0 * w + ix0;
                int c = pix[zi];
                // blend toward dark for a subtle wireframe over the shaded fill
                int r = ((c >> 16) & 0xff) * 1 / 3;
                int g = ((c >> 8) & 0xff) * 1 / 3;
                int b = (c & 0xff) * 1 / 3;
                pix[zi] = (r << 16) | (g << 8) | b;
            }
            if(ix0 == ix1 && iy0 == iy1)
                break;
            int e2 = 2 * err;
            if(e2 >= dy) { err += dy; ix0 += sx; }
            if(e2 <= dx) { err += dx; iy0 += sy; }
        }
    }

    private static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] norm(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if(len < 1e-12)
            return new double[]{0, 0, 1};
        return new double[]{v[0] / len, v[1] / len, v[2] / len};
    }
}
