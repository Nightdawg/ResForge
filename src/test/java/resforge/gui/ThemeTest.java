package resforge.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThemeTest {

    @Test
    void customDefaultsProvideLayeredDarkPaletteAndLightGrid() throws Exception {
        LookAndFeel previous = UIManager.getLookAndFeel();
        try {
            Theme.isDarkPreferred(); // Initializes the application defaults source.
            FlatDarkLaf.setup();

            assertColor("Panel.background", 0x1e1f22);
            assertColor("ToolBar.background", 0x2b2d30);
            assertColor("TextField.background", 0x25272a);
            assertColor("Component.borderColor", 0x43454a);
            assertColor("Table.background", 0x242629);
            assertColor("Table.selectionBackground", 0x2f5f91);
            assertColor("Table.gridColor", 0x484b50);
            assertEquals(new Color(0x35373b), Theme.checkerLight());
            assertEquals(new Color(0x2b2d30), Theme.checkerDark());

            JTable table = new JTable();
            FlatLightLaf.setup();
            assertColor("Table.gridColor", 0x808080);
            table.updateUI();
            assertEquals(new Color(0x808080), table.getGridColor());
        } finally {
            if (previous != null)
                UIManager.setLookAndFeel(previous);
        }
    }

    private static void assertColor(String key, int rgb) {
        Color actual = UIManager.getColor(key);
        assertEquals(new Color(rgb), actual, key);
    }
}
