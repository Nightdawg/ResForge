package resforge.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.prefs.Preferences;

/**
 * Central Look&amp;Feel control for the GUI: a light or dark <a
 * href="https://www.formdev.com/flatlaf/">FlatLaf</a> theme, remembered between
 * launches and switchable live from <em>Options &rarr; Dark mode</em>.
 *
 * <p>Standard Swing widgets are re-themed automatically by {@link FlatLaf#updateUI()};
 * the few components that paint their own colours (the transparency checkerboard,
 * placeholder text) read the theme-aware colours below at paint time, and the
 * handful of muted hint labels are re-tinted via a small {@linkplain #muted(JComponent)
 * registry} so they follow a live switch too.
 */
final class Theme {

    private Theme() {
    }

    private static final String PREF_KEY = "darkMode";

    /** Labels shown in a muted/secondary colour, re-tinted on every theme switch. */
    private static final Set<JComponent> muted =
            Collections.newSetFromMap(new WeakHashMap<>());

    /** Whether dark mode is the remembered preference (default: light). */
    static boolean isDarkPreferred() {
        return prefs().getBoolean(PREF_KEY, false);
    }

    /**
     * Installs the FlatLaf theme matching the stored preference. Call once at
     * startup, before building any UI; {@code UiScaling.normalizeFonts()} should
     * run immediately afterwards.
     */
    static void install() {
        applyLaf(isDarkPreferred());
    }

    /** Whether the currently installed Look&amp;Feel is a dark FlatLaf theme. */
    static boolean isDark() {
        return UIManager.getLookAndFeel() instanceof FlatLaf laf && laf.isDark();
    }

    /** Switches the theme, persisting the choice and live-updating open windows. */
    static void setDark(boolean dark) {
        if (dark == isDark())
            return;
        prefs().putBoolean(PREF_KEY, dark);
        applyLaf(dark);
        UiScaling.normalizeFonts();
        FlatLaf.updateUI();
        refreshMuted();
    }

    private static void applyLaf(boolean dark) {
        if (dark)
            FlatDarkLaf.setup();
        else
            FlatLightLaf.setup();
    }

    /* ----------------------------------------------------- theme-aware colours */

    /** Secondary/hint text colour for the current theme (never a {@code UIResource}). */
    static Color mutedColor() {
        Color c = UIManager.getColor("Label.disabledForeground");
        if (c == null)
            c = UIManager.getColor("textInactiveText");
        return c != null ? new Color(c.getRGB()) : Color.GRAY;
    }

    /**
     * Registers {@code c} to be painted in {@link #mutedColor()} now and again
     * after every theme switch, returning it for convenient inline use.
     */
    static <T extends JComponent> T muted(T c) {
        muted.add(c);
        c.setForeground(mutedColor());
        return c;
    }

    private static void refreshMuted() {
        Color c = mutedColor();
        for (JComponent m : muted)
            m.setForeground(c);
    }

    /** The lighter of the two alternating transparency-checkerboard squares. */
    static Color checkerLight() {
        return isDark() ? new Color(0x565656) : new Color(0xf0f0f0);
    }

    /** The darker of the two alternating transparency-checkerboard squares. */
    static Color checkerDark() {
        return isDark() ? new Color(0x444444) : new Color(0xd8d8d8);
    }

    /** Foreground for placeholder text painted over the checkerboard. */
    static Color placeholderText() {
        return isDark() ? new Color(0xb0b0b0) : Color.DARK_GRAY;
    }

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(ResForgeFrame.class);
    }
}
