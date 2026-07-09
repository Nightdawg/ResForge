package resforge.gui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** A simple component that paints a {@link BufferedImage} scaled to fit and
 *  centred, over a light checkerboard so transparency is visible. */
public class ImageView extends JComponent {
    private BufferedImage image;
    private String placeholder = "No preview";

    public void setImage(BufferedImage img) {
        this.image = img;
        repaint();
    }

    public void setPlaceholder(String text) {
        this.image = null;
        this.placeholder = text;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return isPreferredSizeSet() ? super.getPreferredSize() : UiScaling.scale(360, 360);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        int w = getWidth(), h = getHeight();
        paintCheckerboard(g, w, h);
        if(image == null) {
            g.setColor(Color.DARK_GRAY);
            int tw = g.getFontMetrics().stringWidth(placeholder);
            g.drawString(placeholder, (w - tw) / 2, h / 2);
            g.dispose();
            return;
        }
        int iw = image.getWidth(), ih = image.getHeight();
        double scale = Math.min((double) w / iw, (double) h / ih);
        if(scale > 8)
            scale = 8;                 // don't blow tiny icons up absurdly
        int dw = (int) Math.round(iw * scale), dh = (int) Math.round(ih * scale);
        int x = (w - dw) / 2, y = (h - dh) / 2;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                scale >= 1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                           : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, x, y, dw, dh, null);
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
