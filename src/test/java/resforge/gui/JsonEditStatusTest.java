package resforge.gui;

import org.junit.jupiter.api.Test;
import resforge.io.MessageWriter;
import resforge.layers.ActionCodec;
import resforge.res.Layer;
import resforge.res.Replacer;
import resforge.res.ResContainer;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonEditStatusTest {
    @Test
    void malformedJsonLeavesDocumentAndSuccessStateUnchanged() {
        assertRejectedEdit(new Layer("action", actionPayload()), "{");
    }

    @Test
    void outOfRangeJsonLeavesDocumentAndSuccessStateUnchanged() {
        assertRejectedEdit(new Layer("anim", animPayload()),
                "{\"id\":-1,\"delay\":70000,\"frames\":[5,6]}");
    }

    @Test
    void unsupportedCodecValueLeavesDocumentAndSuccessStateUnchanged() {
        assertRejectedEdit(new Layer("mat2", mat2Payload()),
                "{\"id\":1,\"entries\":[{\"key\":\"tex\","
                        + "\"values\":[{\"unsupported\":1}]}]}");
    }

    @Test
    void successfulJsonEditCommitsBeforeReportingSuccess() {
        RecordingHost host = host(new Layer("action", actionPayload()));
        JPanel content = buildJsonPanel(host);
        JTextArea area = component(content, JTextArea.class);
        area.setText(area.getText().replace("\"Old\"", "\"New\""));

        button(content, "Apply JSON").doClick();

        assertTrue(host.dirty);
        assertEquals(1, host.undoPoints);
        assertEquals("Updated action in layer 0", host.status);
        assertEquals("New", ActionCodec.decode(host.res.layers.get(0).data).get("name"));
    }

    private static void assertRejectedEdit(Layer layer, String editedJson) {
        RecordingHost host = host(layer);
        byte[] original = Arrays.copyOf(layer.data, layer.data.length);
        JPanel content = buildJsonPanel(host);
        component(content, JTextArea.class).setText(editedJson);

        button(content, "Apply JSON").doClick();

        assertArrayEquals(original, host.res.layers.get(0).data);
        assertFalse(host.dirty);
        assertEquals(0, host.undoPoints);
        assertNull(host.status);
        assertEquals(1, host.errors.size());
    }

    private static RecordingHost host(Layer layer) {
        ResContainer res = new ResContainer(1);
        res.layers.add(layer);
        return new RecordingHost(res);
    }

    private static JPanel buildJsonPanel(RecordingHost host) {
        JPanel content = new JPanel();
        new LayerEditors(host).buildJsonPanel(content, 0, host.res.layers.get(0));
        return content;
    }

    private static byte[] actionPayload() {
        return new MessageWriter()
                .string("p").uint16(1).string("Old").string("")
                .uint16(65).uint16(0)
                .toByteArray();
    }

    private static byte[] animPayload() {
        return new MessageWriter()
                .int16(-1).uint16(100).uint16(2).int16(5).int16(6)
                .toByteArray();
    }

    private static byte[] mat2Payload() {
        return new MessageWriter()
                .uint16(1).string("tex").uint8(4).uint8(0).uint8(0)
                .toByteArray();
    }

    private static JButton button(Container root, String text) {
        for(JButton button : components(root, JButton.class)) {
            if(text.equals(button.getText()))
                return button;
        }
        throw new AssertionError("button not found: " + text);
    }

    private static <T extends Component> T component(Container root, Class<T> type) {
        List<T> found = components(root, type);
        if(found.size() != 1)
            throw new AssertionError("expected one " + type.getSimpleName() + ", found " + found.size());
        return found.get(0);
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

    private static final class RecordingHost implements EditorHost {
        final ResContainer res;
        final List<String> errors = new ArrayList<>();
        boolean dirty;
        int undoPoints;
        String status;

        RecordingHost(ResContainer res) {
            this.res = res;
        }

        public ResContainer res() { return res; }
        public void setLayerPayload(int idx, byte[] payload) { }

        public boolean applyBytes(int idx, byte[] bytes) {
            try {
                Replacer.replace(res, "#" + idx, bytes);
            } catch(Replacer.ReplaceException e) {
                errors.add(e.getMessage());
                return false;
            }
            undoPoints++;
            dirty = true;
            return true;
        }

        public void replaceFromFile(int idx, String layerName) { }
        public void exportLayer(int idx) { }
        public void replaceTexMask(int idx) { }
        public void exportTexMask(int idx) { }
        public void setStatus(String status) { this.status = status; }
        public void error(String msg) { errors.add(msg); }
        public void setCurrentPlayer(AudioPlayerPanel player) { }
        public void setAnimTimer(javax.swing.Timer timer) { }
        public void exportGltf() { }
        public void rebuildGltf() { }
    }
}
