package resforge.gui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Plays back a sprite animation accurately: every frame is composited into a
 * single shared coordinate space (using each frame's draw offset) and rendered
 * at one common scale. This preserves the frames' relative sizes and positions —
 * unlike scaling each frame to fill the view, which would make differently-sized
 * frames look identical and discard the per-frame offset motion.
 */
public class AnimView extends JComponent {
    /** A single animation frame: its decoded image and its draw offset. */
    public static final class Frame {
        final BufferedImage img;
        final int x, y;   // top-left of this frame within the shared coordinate space

        public Frame(BufferedImage img, int offX, int offY) {
            this.img = img;
            this.x = offX;
            this.y = offY;
        }
    }

    private List<Frame> frames;
    private int cur;
    private int minX, minY, boundW, boundH;   // union bounding box across all frames

    public void setFrames(List<Frame> frames) {
        this.frames = frames;
        this.cur = 0;
        computeBounds();
        repaint();
    }

    public void setCurrent(int i) {
        if(frames != null && !frames.isEmpty()) {
            this.cur = ((i % frames.size()) + frames.size()) % frames.size();
            repaint();
        }
    }

    private void computeBounds() {
        if(frames == null || frames.isEmpty()) {
            minX = minY = 0;
            boundW = boundH = 1;
            return;
        }
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for(Frame f : frames) {
            x0 = Math.min(x0, f.x);
            y0 = Math.min(y0, f.y);
            x1 = Math.max(x1, f.x + f.img.getWidth());
            y1 = Math.max(y1, f.y + f.img.getHeight());
        }
        minX = x0;
        minY = y0;
        boundW = Math.max(1, x1 - x0);
        boundH = Math.max(1, y1 - y0);
    }

    @Override
    public Dimension getPreferredSize() {
        return isPreferredSizeSet() ? super.getPreferredSize() : UiScaling.scale(240, 200);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        int w = getWidth(), h = getHeight();
        paintCheckerboard(g, w, h);
        if(frames == null || frames.isEmpty()) {
            g.dispose();
            return;
        }
        // One scale for the whole animation, so every frame keeps its true relative size.
        double scale = Math.min((double) w / boundW, (double) h / boundH);
        if(scale > 8)
            scale = 8;
        int drawnW = (int) Math.round(boundW * scale), drawnH = (int) Math.round(boundH * scale);
        int originX = (w - drawnW) / 2, originY = (h - drawnH) / 2;

        Frame f = frames.get(cur);
        int fx = originX + (int) Math.round((f.x - minX) * scale);
        int fy = originY + (int) Math.round((f.y - minY) * scale);
        int fw = (int) Math.round(f.img.getWidth() * scale);
        int fh = (int) Math.round(f.img.getHeight() * scale);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                scale >= 1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                           : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(f.img, fx, fy, fw, fh, null);
        g.dispose();
    }

    private static void paintCheckerboard(Graphics2D g, int w, int h) {
        int s = 12;
        for(int y = 0; y < h; y += s) {
            for(int x = 0; x < w; x += s) {
                boolean even = ((x / s) + (y / s)) % 2 == 0;
                g.setColor(even ? new Color(0xf0f0f0) : new Color(0xd8d8d8));
                g.fillRect(x, y, s, s);
            }
        }
    }
}
