package resforge.gui;

import org.junit.jupiter.api.Test;
import resforge.res.Layer;
import resforge.res.ResContainer;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEditingTest {
    @Test
    void rejectsMalformedUtf8Sequences() {
        byte[][] malformed = {
                {(byte) 0xc0, (byte) 0xaf},
                {(byte) 0xe2, (byte) 0x82},
                {(byte) 0x80}
        };

        for(byte[] bytes : malformed) {
            Layer layer = new Layer("tooltip", bytes);
            assertNull(GuiSupport.editableText(layer));
            assertEquals("invalid UTF-8 (raw)", GuiSupport.summary(layer));
        }
    }

    @Test
    void validNonBmpTextRoundTripsExactly() {
        String expected = "Text \ud83d\ude00 \ud801\udc37";
        byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);

        String decoded = GuiSupport.editableText(new Layer("pagina", bytes));

        assertEquals(expected, decoded);
        assertArrayEquals(bytes, decoded.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void nonTextLayersAreNotExposedAsEditableText() {
        assertNull(GuiSupport.editableText(
                new Layer("image", "text".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void invalidTextPanelOffersRawActionsOnly() {
        JPanel content = new JPanel();
        new LayerEditors(new StubHost()).buildTextPanel(
                content, 3, new Layer("tooltip", new byte[]{(byte) 0x80}));

        List<String> buttons = buttonTexts(content);
        assertTrue(buttons.contains("Replace raw"));
        assertTrue(buttons.contains("Export raw"));
        assertFalse(buttons.contains("Apply text"));
        assertTrue(components(content, JTextArea.class).isEmpty());
    }

    @Test
    void validTextPanelRetainsTextEditor() {
        JPanel content = new JPanel();
        new LayerEditors(new StubHost()).buildTextPanel(content, 3,
                new Layer("tooltip", "valid".getBytes(StandardCharsets.UTF_8)));

        assertTrue(buttonTexts(content).contains("Apply text"));
        List<JTextArea> areas = components(content, JTextArea.class);
        assertEquals(1, areas.size());
        assertEquals("valid", areas.get(0).getText());
    }

    private static List<String> buttonTexts(Container root) {
        List<String> texts = new ArrayList<>();
        for(JButton button : components(root, JButton.class))
            texts.add(button.getText());
        return texts;
    }

    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for(Component component : root.getComponents()) {
            if(type.isInstance(component))
                found.add(type.cast(component));
            if(component instanceof Container)
                found.addAll(components((Container) component, type));
        }
        return found;
    }

    private static final class StubHost implements EditorHost {
        public ResContainer res() { return null; }
        public void setLayerPayload(int idx, byte[] payload) { }
        public boolean applyBytes(int idx, byte[] bytes) { return true; }
        public void replaceFromFile(int idx, String layerName) { }
        public void exportLayer(int idx) { }
        public void replaceTexMask(int idx) { }
        public void exportTexMask(int idx) { }
        public void setStatus(String s) { }
        public void error(String msg) { }
        public void setCurrentPlayer(AudioPlayerPanel player) { }
        public void setAnimTimer(javax.swing.Timer timer) { }
        public void exportGltf() { }
        public void rebuildGltf() { }
    }
}
