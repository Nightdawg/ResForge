package resforge.gui;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.FontUIResource;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * HiDPI-friendly normalisation of the Swing Look&amp;Feel default fonts.
 *
 * <p>Java (9+) is DPI-aware on Windows and already applies a render transform
 * for the monitor's scale factor (e.g. 1.5&times; at 150% scaling), so widget
 * sizes expressed in logical pixels scale correctly on their own. What does
 * <em>not</em> come out right is the Windows Look&amp;Feel's default
 * <em>fonts</em>: at fractional scaling it hands back the core control fonts
 * ({@code Label}, {@code Button}, {@code TextField}, {@code Table}, {@code List}
 * &hellip;) at roughly the 96-dpi point size <em>divided</em> by the scale
 * factor (e.g. Tahoma&nbsp;7 instead of ~12), while the menu fonts stay correct
 * (Segoe&nbsp;UI&nbsp;12). Because the render transform then applies to
 * everything equally, the mis-sized control fonts render ~1/scale too small and
 * the whole UI looks tiny — even though the layout itself scaled fine.
 *
 * <p>The fix is DPI-independent: because the transform already handles pixel
 * density, the correct <em>logical</em> font size is a constant (~12 on
 * Windows) at every scale factor. We therefore lift any default font that is
 * smaller than a trusted reference size up to that reference, preserving each
 * font's family and style (bold/italic) — so on a non-broken display (e.g. a
 * 100%-scaled monitor) this is a no-op. The reference is taken from the menu
 * fonts, which are not affected by the bug, so a user who deliberately enlarged
 * their Windows text is respected rather than clamped down.
 *
 * <p>An optional manual override multiplies every default font size uniformly,
 * for users who want the UI a little larger or smaller on any machine. It can be
 * set two ways: persistently from the GUI (<em>Options &rarr; UI scale&hellip;</em>,
 * stored in {@link Preferences}), or as an advanced one-off launch override via
 * the JVM property {@code -Dresforge.uiScale=1.25} or the environment variable
 * {@code RESFORGE_UI_SCALE=1.25}. A launch override, when present, wins over the
 * stored preference. Either way the value is clamped to [0.5, 4.0]. Because the
 * fonts are applied once at startup, a changed override takes effect on the next
 * launch.
 */
final class UiScaling {

    private UiScaling() {
    }

    /** Smallest and largest allowed manual scale multiplier. */
    static final double MIN_SCALE = 0.5;
    static final double MAX_SCALE = 4.0;

    private static final String PREF_KEY = "uiScale";

    /** Menu-family keys whose fonts are not hit by the fractional-scaling bug. */
    private static final String[] REFERENCE_KEYS = {
            "Menu.font", "MenuItem.font", "MenuBar.font", "PopupMenu.font", "ToolTip.font"
    };

    /**
     * Corrects the current Look&amp;Feel's default fonts in place. Call once,
     * after {@code UIManager.setLookAndFeel(...)} and before building any UI.
     */
    static void normalizeFonts() {
        double override = factor();

        // Reference "correct" logical size: at least 12, grown to match the
        // (unbroken) menu fonts so an enlarged-text preference is honoured.
        int ref = 12;
        for (String key : REFERENCE_KEYS) {
            Font f = UIManager.getFont(key);
            if (f != null)
                ref = Math.max(ref, f.getSize());
        }
        ref = Math.min(ref, 24);

        // Collect the font keys first, then mutate, to avoid touching the map
        // we are iterating.
        List<String> fontKeys = new ArrayList<>();
        for (Object key : UIManager.getLookAndFeelDefaults().keySet())
            if (key instanceof String s && s.endsWith(".font"))
                fontKeys.add(s);

        for (String key : fontKeys) {
            Font f = UIManager.getFont(key);
            if (f == null)
                continue;
            int base = Math.max(f.getSize(), ref);          // lift the too-small ones
            int size = (int) Math.round(base * override);   // then apply the manual knob
            if (size < 1)
                size = 1;
            if (size == f.getSize())
                continue;
            UIManager.put(key, new FontUIResource(f.deriveFont((float) size)));
        }
    }

    private static volatile boolean factorInit = false;
    private static volatile double factor = 1.0;

    /**
     * The effective manual scale multiplier for this session, resolved once and
     * cached. Widget code multiplies its own hardcoded pixel sizes and
     * code-set font sizes by this so they stay proportional to the (already
     * multiplier-adjusted) Look&amp;Feel fonts. It is 1.0 by default, so every
     * {@link #scale} call is an identity no-op unless the user chose a manual
     * scale — the automatic DPI transform is untouched either way.
     */
    static double factor() {
        if (!factorInit) {
            factor = readOverride();
            factorInit = true;
        }
        return factor;
    }

    /** Scales an integer pixel length by {@link #factor()} (0/negatives pass through). */
    static int scale(int px) {
        if (px <= 0)
            return px;
        return Math.max(1, (int) Math.round(px * factor()));
    }

    /** A {@link Dimension} with both extents scaled by {@link #factor()}. */
    static Dimension scale(int w, int h) {
        return new Dimension(scale(w), scale(h));
    }

    /** Scales a float font point size by {@link #factor()}. */
    static float font(float size) {
        return (float) (size * factor());
    }

    /** An empty border whose four insets are each scaled by {@link #factor()}. */
    static Border emptyBorder(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(scale(top), scale(left), scale(bottom), scale(right));
    }

    /** {@link Insets} with all four sides scaled by {@link #factor()}. */
    static Insets insets(int top, int left, int bottom, int right) {
        return new Insets(scale(top), scale(left), scale(bottom), scale(right));
    }

    /**
     * The effective manual scale multiplier applied at startup: an explicit
     * launch override ({@code -Dresforge.uiScale} / {@code RESFORGE_UI_SCALE})
     * if present, otherwise the persisted GUI preference, otherwise 1.0.
     */
    private static double readOverride() {
        Double explicit = launchOverride();
        return explicit != null ? explicit : storedScale();
    }

    /** The advanced launch-time override, or {@code null} if none is set. */
    static Double launchOverride() {
        Double v = parse(System.getProperty("resforge.uiScale"));
        return v != null ? v : parse(System.getenv("RESFORGE_UI_SCALE"));
    }

    /** The persisted GUI preference (default 1.0), clamped to the sane range. */
    static double storedScale() {
        return clamp(prefs().getDouble(PREF_KEY, 1.0));
    }

    /** Persists the GUI scale preference (clamped). Takes effect on next launch. */
    static void setStoredScale(double v) {
        prefs().putDouble(PREF_KEY, clamp(v));
    }

    /** Clamps a multiplier into [{@link #MIN_SCALE}, {@link #MAX_SCALE}]. */
    static double clamp(double v) {
        if (Double.isNaN(v))
            return 1.0;
        if (v < MIN_SCALE)
            return MIN_SCALE;
        if (v > MAX_SCALE)
            return MAX_SCALE;
        return v;
    }

    private static Double parse(String s) {
        if (s == null || s.isBlank())
            return null;
        try {
            return clamp(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(ResForgeFrame.class);
    }
}
