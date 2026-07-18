package resforge.gui;

import resforge.res.Layer;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.nio.charset.StandardCharsets;

/**
 * Builds the right-hand detail/editor panel for a selected layer. One
 * {@code build*Panel} method per layer kind populates a content panel and wires
 * its buttons back to the {@link EditorHost} (the frame), which owns the
 * document, undo/redo, dialogs and file I/O. This keeps all the per-layer Swing
 * construction out of {@link ResForgeFrame}.
 */
final class LayerEditors {
    private final EditorHost host;
    private final AnimationPreviewLoader animationLoader;
    private final ImagePreviewLoader imageLoader;

    LayerEditors(EditorHost host) {
        this(host, new AnimationPreviewLoader(), new ImagePreviewLoader());
    }

    LayerEditors(EditorHost host, AnimationPreviewLoader animationLoader,
                 ImagePreviewLoader imageLoader) {
        this.host = host;
        this.animationLoader = animationLoader;
        this.imageLoader = imageLoader;
    }

    void buildImagePanel(JPanel content, int idx, Layer l) {
        ImageView view = new ImageView();
        view.setPlaceholder("(loading image preview\u2026)");
        JLabel previewState = labeled("Loading image preview\u2026");
        content.add(previewState);
        imageLoader.load(ImagePreviewLoader.embedded(l), l.name + " preview", result -> {
            if(result.image() != null) {
                view.setImage(result.image());
                previewState.setText(result.image().getWidth() + " \u00d7 "
                        + result.image().getHeight() + " pixels");
            } else {
                view.setPlaceholder("(" + (result.failure() != null
                        ? result.failure() : "image could not be decoded") + ")");
                previewState.setText("Preview unavailable");
            }
        });
        resforge.layers.ImageHeaderCodec hdr =
                l.name.equals("image") ? resforge.layers.ImageHeaderCodec.parse(l.data) : null;
        if(hdr != null && hdr.editable) {
            addImageHeaderEditor(content, idx, hdr);
        } else if(l.name.equals("tex")) {
            resforge.layers.TexHeaderCodec th = resforge.layers.TexHeaderCodec.parse(l.data);
            if(th.editable)
                addTexHeaderEditor(content, idx, th);
            else {
                String meta = GuiSupport.imageMeta(l);
                if(meta != null)
                    content.add(labeled(meta));
            }
        } else {
            String meta = GuiSupport.imageMeta(l);
            if(meta != null)
                content.add(labeled(meta));
        }
        view.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(view);
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(
                new JButton(act("Replace image", () -> host.replaceFromFile(idx, l.name))),
                new JButton(act("Export image", () -> host.exportLayer(idx)))));

        // A tex layer may carry a separate alpha mask (part tag 4) — expose it too.
        if(l.name.equals("tex"))
            addTexMaskSection(content, idx, l);
    }

    /** Preview + export/replace for a tex layer's alpha mask (the foliage/cutout shape). */
    private void addTexMaskSection(JPanel content, int idx, Layer l) {
        JPanel section = new JPanel();
        section.setLayout(new javax.swing.BoxLayout(section, javax.swing.BoxLayout.Y_AXIS));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(Box.createVerticalStrut(12));
        section.add(labeled("Alpha mask"));
        ImageView maskView = new ImageView();
        maskView.setPlaceholder("(loading mask preview\u2026)");
        JLabel maskState = labeled("Loading mask preview\u2026");
        section.add(maskState);
        maskView.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(maskView);
        section.add(Box.createVerticalStrut(8));
        section.add(buttonRow(
                new JButton(act("Replace mask", () -> host.replaceTexMask(idx))),
                new JButton(act("Export mask", () -> host.exportTexMask(idx)))));
        section.setVisible(false);
        content.add(section);
        imageLoader.load(ImagePreviewLoader.textureMask(l), "texture mask preview", result -> {
            if(!result.present())
                return;
            section.setVisible(true);
            if(result.image() != null) {
                maskView.setImage(result.image());
                maskState.setText(result.image().getWidth() + " \u00d7 "
                        + result.image().getHeight() + " pixels (" + result.format() + ")");
            } else {
                maskView.setPlaceholder("(" + (result.failure() != null
                        ? result.failure() : "mask could not be decoded") + ")");
                maskState.setText("Mask preview unavailable");
            }
            content.revalidate();
            content.repaint();
        });
    }

    /** Editable header fields (id, offset, declared size) for a tex layer. */
    private void addTexHeaderEditor(JPanel content, int idx, resforge.layers.TexHeaderCodec h) {
        JSpinner idSp = intSpinner(h.id);
        JSpinner oxSp = u16Spinner(h.offX);
        JSpinner oySp = u16Spinner(h.offY);
        JSpinner sxSp = u16Spinner(h.szX);
        JSpinner sySp = u16Spinner(h.szY);

        JPanel row1 = headerRow();
        row1.add(new JLabel("id")); row1.add(idSp);
        row1.add(new JLabel("  off.x")); row1.add(oxSp);
        row1.add(new JLabel("  off.y")); row1.add(oySp);

        JPanel row2 = headerRow();
        row2.add(new JLabel("sz.x")); row2.add(sxSp);
        row2.add(new JLabel("  sz.y")); row2.add(sySp);
        row2.add(new JButton(act("Apply header", () -> {
            try {
                byte[] payload = h.encodeWith(
                        (Integer) idSp.getValue(), (Integer) oxSp.getValue(), (Integer) oySp.getValue(),
                        (Integer) sxSp.getValue(), (Integer) sySp.getValue());
                host.setLayerPayload(idx, payload);
                host.setStatus("Updated tex header in layer " + idx);
            } catch(IllegalArgumentException ex) {
                host.error(ex.getMessage());
            }
        })));

        content.add(row1);
        content.add(row2);
    }

    /** Editable header fields (id, z, sub-z, offset, nooff) for an old-style image layer. */
    private void addImageHeaderEditor(JPanel content, int idx, resforge.layers.ImageHeaderCodec h) {
        JSpinner idSp = intSpinner(h.id);
        JSpinner zSp = intSpinner(h.z);
        JSpinner subzSp = intSpinner(h.subz);
        JSpinner oxSp = intSpinner(h.offX);
        JSpinner oySp = intSpinner(h.offY);
        JCheckBox nooffBox = new JCheckBox("no offset", h.nooff);

        JPanel row1 = headerRow();
        row1.add(new JLabel("id")); row1.add(idSp);
        row1.add(new JLabel("  z")); row1.add(zSp);
        row1.add(new JLabel("  sub-z")); row1.add(subzSp);

        JPanel row2 = headerRow();
        row2.add(new JLabel("off.x")); row2.add(oxSp);
        row2.add(new JLabel("  off.y")); row2.add(oySp);
        row2.add(nooffBox);
        row2.add(new JButton(act("Apply header", () -> {
            try {
                byte[] payload = h.encodeWith(
                        (Integer) zSp.getValue(), (Integer) subzSp.getValue(),
                        (Integer) idSp.getValue(), (Integer) oxSp.getValue(),
                        (Integer) oySp.getValue(), nooffBox.isSelected());
                host.setLayerPayload(idx, payload);
                host.setStatus("Updated image header in layer " + idx);
            } catch(IllegalArgumentException ex) {
                host.error(ex.getMessage());
            }
        })));

        content.add(row1);
        content.add(row2);
    }

    private static JPanel headerRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiScaling.scale(30)));
        return row;
    }

    private static JSpinner intSpinner(int value) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, -32768, 32767, 1));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(5);
        return s;
    }

    private static JSpinner u16Spinner(int value) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(Math.max(0, Math.min(65535, value)), 0, 65535, 1));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(5);
        return s;
    }

    void buildTextPanel(JPanel content, int idx, Layer l) {
        String text = GuiSupport.editableText(l);
        if(text == null) {
            content.add(labeled("This " + l.name + " layer is not valid UTF-8, so text editing"
                    + " is disabled to preserve its original bytes."));
            content.add(Box.createVerticalStrut(8));
            content.add(buttonRow(
                    new JButton(act("Replace raw", () -> host.replaceFromFile(idx, l.name))),
                    new JButton(act("Export raw", () -> host.exportLayer(idx)))));
            return;
        }
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(UiScaling.scale(420, 320));
        content.add(sp);
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(act("Apply text", () -> {
            if(host.applyBytes(idx, area.getText().getBytes(StandardCharsets.UTF_8)))
                host.setStatus("Updated text in layer " + idx);
        })), new JButton(act("Export", () -> host.exportLayer(idx)))));
    }

    void buildJsonPanel(JPanel content, int idx, Layer l) {
        addJsonEditor(content, idx, l, 320);
    }

    void buildBoneOffPanel(JPanel content, int idx, Layer l) {
        content.add(buttonRow(new JButton(act("Preview equipped\u2026",
                () -> host.previewBoneOff(idx)))));
        content.add(Box.createVerticalStrut(8));
        addJsonEditor(content, idx, l, 280);
    }

    private void addJsonEditor(JPanel content, int idx, Layer l, int height) {
        String json = GuiSupport.editableJson(l);
        if(json == null) {
            content.add(labeled("This " + l.name + " layer uses types this editor can't safely"
                    + " present as JSON; it is preserved as-is."));
            content.add(Box.createVerticalStrut(8));
            content.add(buttonRow(new JButton(act("Export raw", () -> host.exportLayer(idx)))));
            return;
        }
        JTextArea area = new JTextArea(json);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScaling.scale(12)));
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(UiScaling.scale(420), UiScaling.scale(height)));
        content.add(sp);
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(act("Apply JSON", () -> {
            if(host.applyBytes(idx, area.getText().getBytes(StandardCharsets.UTF_8)))
                host.setStatus("Updated " + l.name + " in layer " + idx);
        })), new JButton(act("Export JSON", () -> host.exportLayer(idx)))));
    }

    /** anim layers: a live frame preview (resolving frame image-ids to sibling image layers) + the JSON editor. */
    void buildAnimPanel(JPanel content, int idx, Layer l) {
        addAnimPreview(content, l);
        addJsonEditor(content, idx, l, 160);
    }

    private void addAnimPreview(JPanel content, Layer l) {
        java.util.Map<String, Object> m;
        try {
            m = resforge.layers.AnimCodec.decode(l.data);
        } catch(RuntimeException e) {
            return;
        }
        int delay = ((Number) m.get("delay")).intValue();
        java.util.List<?> rawIds = (java.util.List<?>) m.get("frames");
        java.util.List<Integer> ids = new java.util.ArrayList<>(rawIds.size());
        for(Object id : rawIds)
            ids.add(((Number) id).intValue());
        JLabel state = labeled("Loading animation preview\u2026");
        content.add(state);
        AnimView view = new AnimView();
        Dimension d = UiScaling.scale(220, 180);
        view.setPreferredSize(d);
        view.setMaximumSize(d);
        view.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(view);
        content.add(Box.createVerticalStrut(8));
        java.util.List<Layer> snapshot = host.res() == null
                ? java.util.List.of() : java.util.List.copyOf(host.res().layers);
        animationLoader.load(snapshot, ids, (result, failure) -> {
            if(failure != null) {
                state.setText("Preview unavailable: " + failure);
                return;
            }
            java.util.List<AnimView.Frame> frames = result.frames();
            if(frames.isEmpty()) {
                state.setText("(no matching image frames in this resource to preview)");
                return;
            }
            state.setText("Preview \u2014 " + frames.size() + " frames @ " + delay + "ms"
                    + " (true relative size & offset)");
            view.setFrames(frames);
            int[] fi = {0};
            javax.swing.Timer timer = new javax.swing.Timer(Math.max(20, delay), ev -> {
                fi[0] = (fi[0] + 1) % frames.size();
                view.setCurrent(fi[0]);
            });
            timer.setInitialDelay(Math.max(20, delay));
            timer.start();
            host.setAnimTimer(timer);
        });
    }

    void invalidateAnimationPreview() {
        animationLoader.invalidate();
        imageLoader.invalidate();
    }

    void dispose() {
        animationLoader.close();
        imageLoader.close();
    }

    void buildMediaPanel(JPanel content, int idx, Layer l) {
        if(l.name.equals("audio2")) {
            byte[] ogg = GuiSupport.audioBytes(l);
            if(ogg != null) {
                AudioPlayerPanel player = new AudioPlayerPanel(ogg);
                host.setCurrentPlayer(player);
                content.add(player);
                content.add(Box.createVerticalStrut(8));
            }
            addAudioHeaderEditor(content, idx, l);
        }
        String media = GuiSupport.mediaMeta(l);
        content.add(labeled(media != null ? media : GuiSupport.summary(l)));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(
                new JButton(act("Replace", () -> host.replaceFromFile(idx, l.name))),
                new JButton(act("Export", () -> host.exportLayer(idx)))));
    }

    /** Shows the audio clip id + volume, with an editable volume for ver-2 clips. */
    private void addAudioHeaderEditor(JPanel content, int idx, Layer l) {
        resforge.layers.AudioHeaderCodec h = resforge.layers.AudioHeaderCodec.parse(l.data);
        if(!h.editable) {
            resforge.layers.AudioInfo ai = resforge.layers.AudioInfo.parse(l.data);
            if(ai.recognized)
                content.add(labeled("id=\"" + ai.id + "\"   volume=" + String.format("%.3f", ai.bvol)));
            return;
        }
        JTextField idField = new JTextField(h.id, 10);
        JPanel row = headerRow();
        row.add(new JLabel("id"));
        row.add(idField);
        final JSpinner volSp;
        if(h.hasVol) {
            volSp = new JSpinner(new SpinnerNumberModel(h.bvol(), 0.0, 65.535, 0.05));
            ((JSpinner.DefaultEditor) volSp.getEditor()).getTextField().setColumns(6);
            row.add(new JLabel("  volume"));
            row.add(volSp);
        } else {
            volSp = null;
        }
        row.add(new JButton(act("Apply", () -> {
            try {
                byte[] payload = (volSp != null)
                        ? h.encodeWithBvol(idField.getText(), (Double) volSp.getValue())
                        : h.encodeWith(idField.getText(), 0);
                host.setLayerPayload(idx, payload);
                host.setStatus("Updated audio header in layer " + idx);
            } catch(IllegalArgumentException ex) {
                host.error(ex.getMessage());
            }
        })));
        content.add(row);
        content.add(Box.createVerticalStrut(8));
    }

    void buildModelPanel(JPanel content, Layer l) {
        String detail = GuiSupport.modelDetail(l);
        if(detail != null) {
            JTextArea area = new JTextArea(detail);
            area.setEditable(false);
            area.setOpaque(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScaling.scale(12)));
            area.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(area);
        } else {
            content.add(labeled(GuiSupport.summary(l)));
        }
        content.add(Box.createVerticalStrut(8));
        content.add(labeled("3D geometry is read-only here; export to glTF to edit the whole "
                + "model in Blender, then rebuild from the edited .glb to bring your changes back."));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(
                new JButton(act("Export glTF (.glb)", host::exportGltf)),
                new JButton(act("Rebuild from glTF", host::rebuildGltf))));
    }

    void buildCodePanel(JPanel content, int idx, Layer l) {
        JTextArea area = new JTextArea(GuiSupport.codeText(l));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScaling.scale(12)));
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(UiScaling.scale(420, 320));
        content.add(sp);
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(act("Export", () -> host.exportLayer(idx)))));
    }

    void buildReferencePanel(JPanel content, int idx, Layer l) {
        JTextArea area = new JTextArea(GuiSupport.referenceText(l));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScaling.scale(12)));
        area.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(UiScaling.scale(420, 320));
        content.add(sp);
        content.add(Box.createVerticalStrut(8));
        content.add(labeled("Read-only: this resource references the items above; preserved exactly on save."));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(act("Export", () -> host.exportLayer(idx)))));
    }

    void buildRawPanel(JPanel content, int idx, Layer l) {
        content.add(labeled("Raw layer, preserved exactly on save."));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(
                new JButton(act("Replace raw", () -> host.replaceFromFile(idx, l.name))),
                new JButton(act("Export raw", () -> host.exportLayer(idx)))));
    }

    void buildRigPanel(JPanel content, int idx, Layer l) {
        JTextArea area = new JTextArea(GuiSupport.rigText(l));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScaling.scale(12)));
        area.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(UiScaling.scale(420, 320));
        content.add(sp);
        content.add(Box.createVerticalStrut(8));
        content.add(labeled("Read-only structural view; the layer is preserved exactly on save."));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(act("Export raw", () -> host.exportLayer(idx)))));
    }

    /* ------------------------------------------------------------------- utils */

    /** Wraps a {@link Runnable} as a Swing action, surfacing any error via the host. */
    private AbstractAction act(String text, Runnable r) {
        return new AbstractAction(text) {
            public void actionPerformed(ActionEvent e) {
                try {
                    r.run();
                } catch(Exception ex) {
                    host.error(ex.getMessage());
                }
            }
        };
    }

    private JComponent buttonRow(JButton... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for(int i = 0; i < buttons.length; i++) {
            if(i > 0)
                row.add(Box.createHorizontalStrut(8));
            row.add(buttons[i]);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private JLabel labeled(String text) {
        JLabel lab = new JLabel(text);
        lab.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lab;
    }
}
