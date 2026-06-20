package hafen.resedit.gui;

import hafen.resedit.model.ObjExport;
import hafen.resedit.res.Layer;
import hafen.resedit.res.Replacer;
import hafen.resedit.res.ResContainer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A small Swing GUI for browsing and editing a Haven &amp; Hearth {@code .res}
 *  file: a layer table on the left, a preview/edit panel on the right, and the
 *  usual open/save/replace/export actions — all backed by the same lossless
 *  decoders/codecs as the CLI. */
public class ResEditFrame extends JFrame {
    private ResContainer res;
    private Path file;
    private boolean dirty;

    private final LayerTableModel model = new LayerTableModel();
    private final JTable table = new JTable(model);
    private final JPanel detail = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel(" ");
    private final JSpinner versionSpinner =
            new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private boolean updatingVersion;
    private JButton addBtn, delBtn, upBtn, downBtn, rnBtn;
    private AudioPlayerPanel currentPlayer;
    private final java.util.Map<Layer, Icon> thumbCache = new java.util.HashMap<>();
    private final JTextField pathField = new JTextField("(no file open)");

    private static final int UNDO_LIMIT = 100;
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private final Deque<Snapshot> redoStack = new ArrayDeque<>();
    private JMenuItem undoItem, redoItem;

    /** An immutable snapshot of the document for undo/redo. Layers are themselves
     *  immutable and only ever replaced (never mutated in place), so a copy of
     *  the list is a complete, cheap snapshot. */
    private static final class Snapshot {
        final int version;
        final List<Layer> layers;
        final boolean dirty;
        final int selectedRow;

        Snapshot(int version, List<Layer> layers, boolean dirty, int selectedRow) {
            this.version = version;
            this.layers = layers;
            this.dirty = dirty;
            this.selectedRow = selectedRow;
        }
    }

    public ResEditFrame() {
        super("hafen-resedit");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                if(confirmDiscard()) {
                    if(currentPlayer != null)
                        currentPlayer.dispose();
                    dispose();
                }
            }
        });

        setJMenuBar(buildMenuBar());
        JPanel north = new JPanel(new BorderLayout());
        north.add(buildToolBar(), BorderLayout.NORTH);
        north.add(buildPathBar(), BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(36);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setMaxWidth(44);
        table.getColumnModel().getColumn(1).setMinWidth(44);
        table.getSelectionModel().addListSelectionListener(e -> {
            if(!e.getValueIsAdjusting()) {
                showSelected();
                updateLayerButtons();
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        JPanel left = new JPanel(new BorderLayout());
        left.add(tableScroll, BorderLayout.CENTER);
        left.add(buildLayerBar(), BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(380, 480));

        detail.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        showPlaceholder("Open a .res file to begin (File \u2192 Open, or drag one in).");

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, detail);
        split.setDividerLocation(380);
        add(split, BorderLayout.CENTER);

        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        add(status, BorderLayout.SOUTH);

        setTransferHandler(new FileDropHandler());
        setSize(900, 600);
        setLocationByPlatform(true);
        updateLayerButtons();
    }

    private JComponent buildLayerBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        addBtn = new JButton(action("Add\u2026", this::addLayer));
        addBtn.setToolTipText("Add a new layer (optionally from a file)");
        rnBtn = new JButton(action("Rename\u2026", this::renameLayer));
        rnBtn.setToolTipText("Change the selected layer's type/name (or double-click it in the table)");
        delBtn = new JButton(action("Delete", this::deleteLayer));
        upBtn = new JButton(action("Move \u2191", () -> moveLayer(-1)));
        upBtn.setToolTipText("Move the selected layer earlier (order is significant)");
        downBtn = new JButton(action("Move \u2193", () -> moveLayer(1)));
        downBtn.setToolTipText("Move the selected layer later (order is significant)");
        bar.add(addBtn);
        bar.add(rnBtn);
        bar.add(delBtn);
        bar.addSeparator();
        bar.add(upBtn);
        bar.add(downBtn);
        return bar;
    }

    private void updateLayerButtons() {
        int sel = table.getSelectedRow();
        boolean hasFile = res != null;
        boolean hasSel = hasFile && sel >= 0 && sel < res.layers.size();
        if(addBtn != null)
            addBtn.setEnabled(hasFile);
        if(rnBtn != null)
            rnBtn.setEnabled(hasSel);
        if(delBtn != null)
            delBtn.setEnabled(hasSel);
        if(upBtn != null)
            upBtn.setEnabled(hasSel && sel > 0);
        if(downBtn != null)
            downBtn.setEnabled(hasSel && sel < res.layers.size() - 1);
    }

    /* ----------------------------------------------------------- undo / redo */

    private Snapshot snapshot() {
        return new Snapshot(res.version, new ArrayList<>(res.layers), dirty, table.getSelectedRow());
    }

    /** Records the current state as an undo point (call before a mutation). */
    private void pushUndo() {
        if(res != null)
            commit(snapshot());
    }

    private void commit(Snapshot s) {
        undoStack.push(s);
        while(undoStack.size() > UNDO_LIMIT)
            undoStack.removeLast();
        redoStack.clear();
        updateUndoState();
    }

    private void undo() {
        if(res == null || undoStack.isEmpty())
            return;
        redoStack.push(snapshot());
        restore(undoStack.pop());
        setStatus("Undo");
    }

    private void redo() {
        if(res == null || redoStack.isEmpty())
            return;
        undoStack.push(snapshot());
        restore(redoStack.pop());
        setStatus("Redo");
    }

    private void restore(Snapshot s) {
        res.version = s.version;
        res.layers.clear();
        res.layers.addAll(s.layers);
        dirty = s.dirty;
        updatingVersion = true;
        versionSpinner.setValue(res.version);
        updatingVersion = false;
        model.fireTableDataChanged();
        if(!res.layers.isEmpty() && s.selectedRow >= 0 && s.selectedRow < res.layers.size())
            table.setRowSelectionInterval(s.selectedRow, s.selectedRow);
        else if(res.layers.isEmpty())
            showPlaceholder("This file has no layers.");
        updateTitle();
        updateLayerButtons();
        updateUndoState();
    }

    private void updateUndoState() {
        if(undoItem != null)
            undoItem.setEnabled(!undoStack.isEmpty());
        if(redoItem != null)
            redoItem.setEnabled(!redoStack.isEmpty());
    }

    private void addLayer() {
        if(res == null)
            return;
        String name = JOptionPane.showInputDialog(this,
                "New layer type/name (e.g. image, tooltip, neg, props):",
                "Add layer", JOptionPane.PLAIN_MESSAGE);
        if(name == null)
            return;
        name = name.strip();
        if(name.isEmpty()) {
            error("Layer name cannot be empty.");
            return;
        }
        byte[] data = new byte[0];
        JFileChooser fc = new JFileChooser(dir());
        fc.setDialogTitle("Content for '" + name + "'  (Cancel = empty layer)");
        if(fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                data = Files.readAllBytes(fc.getSelectedFile().toPath());
            } catch(Exception e) {
                error("Could not read file: " + e.getMessage());
                return;
            }
        }
        int sel = table.getSelectedRow();
        int at = (sel >= 0) ? sel + 1 : res.layers.size();
        pushUndo();
        res.layers.add(at, new Layer(name, data));
        model.fireTableDataChanged();
        table.setRowSelectionInterval(at, at);
        markDirty();
        setStatus("Added '" + name + "' layer (" + data.length + " bytes) at position " + at);
    }

    private void deleteLayer() {
        int sel = table.getSelectedRow();
        if(res == null || sel < 0 || sel >= res.layers.size())
            return;
        Layer l = res.layers.get(sel);
        int r = JOptionPane.showConfirmDialog(this,
                "Delete layer " + sel + " (" + l.name + ", " + l.data.length + " bytes)?",
                "Delete layer", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if(r != JOptionPane.YES_OPTION)
            return;
        pushUndo();
        res.layers.remove(sel);
        model.fireTableDataChanged();
        if(res.layers.isEmpty()) {
            showPlaceholder("This file has no layers.");
        } else {
            int n = Math.min(sel, res.layers.size() - 1);
            table.setRowSelectionInterval(n, n);
        }
        markDirty();
        updateLayerButtons();
        setStatus("Deleted layer " + sel + " (" + l.name + ")");
    }

    private void moveLayer(int delta) {
        int sel = table.getSelectedRow();
        if(res == null || sel < 0)
            return;
        int target = sel + delta;
        if(target < 0 || target >= res.layers.size())
            return;
        pushUndo();
        Layer l = res.layers.remove(sel);
        res.layers.add(target, l);
        model.fireTableDataChanged();
        table.setRowSelectionInterval(target, target);
        markDirty();
        setStatus("Moved '" + l.name + "' to position " + target);
    }

    private void renameLayer() {
        int sel = table.getSelectedRow();
        if(res == null || sel < 0 || sel >= res.layers.size())
            return;
        Layer l = res.layers.get(sel);
        Object input = JOptionPane.showInputDialog(this, "Layer type/name:", "Rename layer",
                JOptionPane.PLAIN_MESSAGE, null, null, l.name);
        if(input == null)
            return;
        applyRename(sel, String.valueOf(input));
    }

    /** Replaces a layer's name (keeping its bytes); the {@link Layer} is immutable. */
    private void applyRename(int row, String newName) {
        if(res == null || row < 0 || row >= res.layers.size())
            return;
        newName = newName.strip();
        Layer l = res.layers.get(row);
        if(newName.isEmpty()) {
            error("Layer name cannot be empty.");
            return;
        }
        if(newName.equals(l.name))
            return;
        pushUndo();
        res.layers.set(row, new Layer(newName, l.data));
        model.fireTableRowsUpdated(row, row);
        markDirty();
        if(table.getSelectedRow() == row)
            showSelected();
        setStatus("Renamed layer " + row + " to '" + newName + "'");
    }

    /* ------------------------------------------------------------------ menus */

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        fileMenu.add(item("Open\u2026", KeyEvent.VK_O, this::doOpen));
        fileMenu.add(item("Save", KeyEvent.VK_S, this::doSave));
        fileMenu.add(menuItem("Save As\u2026", this::doSaveAs));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Export 3D model as OBJ\u2026", this::doExportObj));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Exit", () -> { if(confirmDiscard()) dispose(); }));
        bar.add(fileMenu);

        JMenu editMenu = new JMenu("Edit");
        undoItem = item("Undo", KeyEvent.VK_Z, this::undo);
        redoItem = item("Redo", KeyEvent.VK_Y, this::redo);
        undoItem.setEnabled(false);
        redoItem.setEnabled(false);
        editMenu.add(undoItem);
        editMenu.add(redoItem);
        bar.add(editMenu);

        JMenu help = new JMenu("Help");
        help.add(menuItem("About", this::doAbout));
        bar.add(help);
        return bar;
    }

    private JComponent buildPathBar() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        JLabel lab = new JLabel("File: ");
        lab.setFont(lab.getFont().deriveFont(Font.BOLD));
        pathField.setEditable(false);
        pathField.setBorder(BorderFactory.createEmptyBorder());
        pathField.setOpaque(false);
        pathField.setToolTipText("Full path of the open file (read-only; select to copy)");
        bar.add(lab, BorderLayout.WEST);
        bar.add(pathField, BorderLayout.CENTER);
        return bar;
    }

    private void updatePath() {
        pathField.setText(file != null ? file.toAbsolutePath().toString() : "(no file open)");
        pathField.setCaretPosition(0);
    }

    private JToolBar buildToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(new JButton(action("Open\u2026", this::doOpen)));
        tb.add(new JButton(action("Save As\u2026", this::doSaveAs)));
        tb.addSeparator();
        tb.add(new JButton(action("Export OBJ\u2026", this::doExportObj)));
        tb.addSeparator();
        JLabel vl = new JLabel("Resource version: ");
        tb.add(vl);
        versionSpinner.setToolTipText("Resource format version (0\u201365535). Saved into the file header.");
        versionSpinner.setEnabled(false);
        versionSpinner.setMaximumSize(new Dimension(90, 28));
        versionSpinner.setPreferredSize(new Dimension(90, 28));
        versionSpinner.addChangeListener(e -> {
            if(updatingVersion || res == null)
                return;
            pushUndo();
            res.version = (Integer) versionSpinner.getValue();
            markDirty();
            setStatus("Resource version set to " + res.version);
        });
        tb.add(versionSpinner);
        return tb;
    }

    private JMenuItem item(String text, int key, Runnable r) {
        JMenuItem mi = menuItem(text, r);
        mi.setAccelerator(KeyStroke.getKeyStroke(key, InputEvent.CTRL_DOWN_MASK));
        return mi;
    }

    private JMenuItem menuItem(String text, Runnable r) {
        return new JMenuItem(action(text, r));
    }

    private AbstractAction action(String text, Runnable r) {
        return new AbstractAction(text) {
            public void actionPerformed(ActionEvent e) {
                try {
                    r.run();
                } catch(Exception ex) {
                    error(ex.getMessage());
                }
            }
        };
    }

    /* ------------------------------------------------------------- file logic */

    public void openFile(Path p) {
        try {
            ResContainer parsed = ResContainer.parse(Files.readAllBytes(p));
            this.res = parsed;
            this.file = p;
            this.dirty = false;
            thumbCache.clear();
            updatingVersion = true;
            versionSpinner.setValue(res.version);
            versionSpinner.setEnabled(true);
            updatingVersion = false;
            model.fireTableDataChanged();
            if(model.getRowCount() > 0)
                table.setRowSelectionInterval(0, 0);
            else
                showPlaceholder("This file has no layers.");
            updateTitle();
            updatePath();
            updateLayerButtons();
            undoStack.clear();
            redoStack.clear();
            updateUndoState();
            setStatus("Opened " + p.getFileName() + " \u2014 res-version " + res.version
                    + ", " + res.layers.size() + " layers");
        } catch(Exception e) {
            error("Could not open " + p + ":\n" + e.getMessage());
        }
    }

    private void doOpen() {
        if(!confirmDiscard())
            return;
        JFileChooser fc = new JFileChooser(dir());
        fc.setFileFilter(new FileNameExtensionFilter("Haven resource (*.res)", "res"));
        if(fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            openFile(fc.getSelectedFile().toPath());
    }

    private void doSave() {
        if(res == null)
            return;
        if(file == null)
            doSaveAs();
        else
            writeRes(file);
    }

    private void doSaveAs() {
        if(res == null)
            return;
        JFileChooser fc = new JFileChooser(dir());
        fc.setFileFilter(new FileNameExtensionFilter("Haven resource (*.res)", "res"));
        if(file != null)
            fc.setSelectedFile(file.toFile());
        if(fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path p = fc.getSelectedFile().toPath();
            writeRes(p);
            this.file = p;
            updateTitle();
            updatePath();
        }
    }

    private void writeRes(Path p) {
        try {
            Files.write(p, res.serialize());
            dirty = false;
            updateTitle();
            setStatus("Saved " + p.getFileName());
        } catch(Exception e) {
            error("Could not save: " + e.getMessage());
        }
    }

    private void doExportObj() {
        if(res == null)
            return;
        try {
            ObjExport.Result r = ObjExport.toObj(res, file != null ? file.getFileName().toString() : "model");
            if(r.vertices == 0 || r.triangles == 0) {
                info("This resource has no 3D geometry (vbuf2/mesh) to export.");
                return;
            }
            JFileChooser fc = new JFileChooser(dir());
            fc.setFileFilter(new FileNameExtensionFilter("Wavefront OBJ (*.obj)", "obj"));
            fc.setSelectedFile(new File(baseName() + ".obj"));
            if(fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                Files.writeString(fc.getSelectedFile().toPath(), r.obj);
                setStatus("Exported " + r.vertices + " vertices, " + r.triangles
                        + " triangles (" + r.submeshes + " submeshes)");
            }
        } catch(Exception e) {
            error("OBJ export failed: " + e.getMessage());
        }
    }

    /* ----------------------------------------------------------- detail panel */

    private void showSelected() {
        if(currentPlayer != null) {
            currentPlayer.dispose();
            currentPlayer = null;
        }
        int idx = table.getSelectedRow();
        if(res == null || idx < 0 || idx >= res.layers.size()) {
            showPlaceholder("Select a layer.");
            return;
        }
        Layer l = res.layers.get(idx);
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel head = new JLabel(GuiSupport.kind(l.name) + "  \u2014  " + l.name
                + "  (" + l.data.length + " bytes)");
        head.setFont(head.getFont().deriveFont(Font.BOLD, 14f));
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(head);
        content.add(Box.createVerticalStrut(8));

        switch(GuiSupport.kind(l.name)) {
            case "icon":
            case "texture":
                buildImagePanel(content, idx, l);
                break;
            case "text":
                buildTextPanel(content, idx, l);
                break;
            case "props":
            case "keybind":
                buildJsonPanel(content, idx, l);
                break;
            case "sound":
            case "font":
            case "music":
                buildMediaPanel(content, idx, l);
                break;
            case "3D model":
                buildModelPanel(content, l);
                break;
            default:
                buildRawPanel(content, idx, l);
        }

        detail.removeAll();
        detail.add(content, BorderLayout.CENTER);
        detail.revalidate();
        detail.repaint();
    }

    private void buildImagePanel(JPanel content, int idx, Layer l) {
        ImageView view = new ImageView();
        java.awt.image.BufferedImage img = GuiSupport.preview(l);
        if(img != null) {
            view.setImage(img);
            content.add(labeled(img.getWidth() + " \u00d7 " + img.getHeight() + " pixels"));
        } else {
            view.setPlaceholder("(image could not be decoded)");
        }
        view.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(view);
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(
                new JButton(action("Replace image\u2026", () -> replaceFromFile(idx, l.name))),
                new JButton(action("Export image\u2026", () -> exportLayer(idx)))));
    }

    private void buildTextPanel(JPanel content, int idx, Layer l) {
        JTextArea area = new JTextArea(GuiSupport.editableText(l));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(420, 320));
        content.add(sp);
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(action("Apply text", () -> {
            applyBytes(idx, area.getText().getBytes(StandardCharsets.UTF_8));
            setStatus("Updated text in layer " + idx);
        })), new JButton(action("Export\u2026", () -> exportLayer(idx)))));
    }

    private void buildJsonPanel(JPanel content, int idx, Layer l) {
        String json = GuiSupport.editableJson(l);
        if(json == null) {
            content.add(labeled("This " + l.name + " layer uses types this editor can't safely"
                    + " present as JSON; it is preserved as-is."));
            content.add(Box.createVerticalStrut(8));
            content.add(buttonRow(new JButton(action("Export raw\u2026", () -> exportLayer(idx)))));
            return;
        }
        JTextArea area = new JTextArea(json);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(420, 320));
        content.add(sp);
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(action("Apply JSON", () -> {
            applyBytes(idx, area.getText().getBytes(StandardCharsets.UTF_8));
            setStatus("Updated " + l.name + " in layer " + idx);
        })), new JButton(action("Export JSON\u2026", () -> exportLayer(idx)))));
    }

    private void buildMediaPanel(JPanel content, int idx, Layer l) {
        if(l.name.equals("audio2")) {
            byte[] ogg = GuiSupport.audioBytes(l);
            if(ogg != null) {
                currentPlayer = new AudioPlayerPanel(ogg);
                content.add(currentPlayer);
                content.add(Box.createVerticalStrut(8));
            }
        }
        content.add(labeled(GuiSupport.summary(l)));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(
                new JButton(action("Replace\u2026", () -> replaceFromFile(idx, l.name))),
                new JButton(action("Export\u2026", () -> exportLayer(idx)))));
    }

    private void buildModelPanel(JPanel content, Layer l) {
        content.add(labeled(GuiSupport.summary(l)));
        content.add(Box.createVerticalStrut(8));
        content.add(labeled("3D geometry is edited via the whole model."));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(action("Export model as OBJ\u2026", this::doExportObj))));
    }

    private void buildRawPanel(JPanel content, int idx, Layer l) {
        content.add(labeled("Raw layer, preserved exactly on save."));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(
                new JButton(action("Replace raw\u2026", () -> replaceFromFile(idx, l.name))),
                new JButton(action("Export raw\u2026", () -> exportLayer(idx)))));
    }

    /* ------------------------------------------------------------ edit actions */

    private void replaceFromFile(int idx, String layerName) {
        JFileChooser fc = new JFileChooser(dir());
        FileNameExtensionFilter filter = filterFor(layerName);
        if(filter != null)
            fc.setFileFilter(filter);
        if(fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        try {
            byte[] bytes = Files.readAllBytes(fc.getSelectedFile().toPath());
            applyBytes(idx, bytes);
            setStatus("Replaced layer " + idx + " (" + layerName + ")");
        } catch(Exception e) {
            error("Replace failed: " + e.getMessage());
        }
    }

    /** Routes every in-memory edit through the tested Replacer (by absolute index). */
    private void applyBytes(int idx, byte[] bytes) {
        if(res == null)
            return;
        Snapshot before = snapshot();
        try {
            Replacer.replace(res, "#" + idx, bytes);
        } catch(Replacer.ReplaceException e) {
            error(e.getMessage());
            return;
        }
        commit(before);
        markDirty();
        int sel = table.getSelectedRow();
        model.fireTableRowsUpdated(idx, idx);
        if(sel == idx)
            showSelected();
    }

    private void exportLayer(int idx) {
        Layer l = res.layers.get(idx);
        GuiSupport.Export ex = GuiSupport.export(l);
        JFileChooser fc = new JFileChooser(dir());
        fc.setFileFilter(new FileNameExtensionFilter(ex.desc + " (*." + ex.ext + ")", ex.ext));
        fc.setSelectedFile(new File(baseName() + "_" + idx + "_" + l.name + "." + ex.ext));
        if(fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        try {
            Files.write(fc.getSelectedFile().toPath(), ex.data);
            setStatus("Exported layer " + idx + " \u2192 " + fc.getSelectedFile().getName());
        } catch(Exception e) {
            error("Export failed: " + e.getMessage());
        }
    }

    private static FileNameExtensionFilter filterFor(String layerName) {
        switch(layerName) {
            case "image":
            case "tex":    return new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp");
            case "audio2": return new FileNameExtensionFilter("Ogg Vorbis (*.ogg)", "ogg");
            case "font":   return new FileNameExtensionFilter("Fonts", "ttf", "otf");
            case "midi":   return new FileNameExtensionFilter("MIDI (*.mid)", "mid", "midi");
            default:       return null;
        }
    }

    /* ------------------------------------------------------------------- utils */

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

    private JComponent labeled(String text) {
        JLabel lab = new JLabel(text);
        lab.setAlignmentX(Component.LEFT_ALIGNMENT);
        return lab;
    }

    private void showPlaceholder(String text) {
        detail.removeAll();
        JLabel lab = new JLabel(text, JLabel.CENTER);
        lab.setForeground(java.awt.Color.GRAY);
        detail.add(lab, BorderLayout.CENTER);
        detail.revalidate();
        detail.repaint();
    }

    private void markDirty() {
        dirty = true;
        updateTitle();
    }

    private void updateTitle() {
        String n = (file != null) ? file.getFileName().toString() : "(unsaved)";
        setTitle("hafen-resedit \u2014 " + n + (dirty ? " *" : ""));
    }

    private boolean confirmDiscard() {
        if(!dirty)
            return true;
        int r = JOptionPane.showConfirmDialog(this, "Discard unsaved changes?",
                "Unsaved changes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return r == JOptionPane.YES_OPTION;
    }

    private File dir() {
        return file != null ? file.toFile().getParentFile() : null;
    }

    private String baseName() {
        if(file == null)
            return "resource";
        String n = file.getFileName().toString();
        return n.toLowerCase().endsWith(".res") ? n.substring(0, n.length() - 4) : n;
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "hafen-resedit", JOptionPane.INFORMATION_MESSAGE);
    }

    private void doAbout() {
        info("hafen-resedit\n\nA tool to view and edit Haven & Hearth .res files.\n"
                + "Swap icons, textures, sounds and fonts; edit text and properties;\n"
                + "export 3D models to OBJ. Unchanged layers are preserved byte-for-byte.");
    }

    /* ---------------------------------------------------------- table + d-and-d */

    private class LayerTableModel extends AbstractTableModel {
        private final String[] cols = {"#", "Img", "Kind", "Layer", "Size", "Summary"};

        public int getRowCount() {
            return res == null ? 0 : res.layers.size();
        }

        public int getColumnCount() {
            return cols.length;
        }

        public String getColumnName(int c) {
            return cols[c];
        }

        public Class<?> getColumnClass(int c) {
            return c == 1 ? Icon.class : Object.class;
        }

        public Object getValueAt(int row, int col) {
            Layer l = res.layers.get(row);
            switch(col) {
                case 0: return row;
                case 1: return thumbnail(l);
                case 2: return GuiSupport.kind(l.name);
                case 3: return l.name;
                case 4: return l.data.length;
                default: return GuiSupport.summary(l);
            }
        }

        public boolean isCellEditable(int row, int col) {
            return col == 3;       // the "Layer" (type/name) column is editable
        }

        public void setValueAt(Object value, int row, int col) {
            if(col == 3 && value != null)
                applyRename(row, String.valueOf(value));
        }
    }

    /** A cached, scaled thumbnail for icon/texture layers, else null. */
    private Icon thumbnail(Layer l) {
        if(thumbCache.containsKey(l))
            return thumbCache.get(l);
        Icon icon = makeThumbnail(l);
        thumbCache.put(l, icon);
        return icon;
    }

    private static Icon makeThumbnail(Layer l) {
        String kind = GuiSupport.kind(l.name);
        if(!kind.equals("icon") && !kind.equals("texture"))
            return null;
        java.awt.image.BufferedImage img = GuiSupport.preview(l);
        if(img == null)
            return null;
        int max = 30;
        int w = img.getWidth(), h = img.getHeight();
        double s = Math.min((double) max / w, (double) max / h);
        if(s > 1)
            s = 1;
        int dw = Math.max(1, (int) Math.round(w * s)), dh = Math.max(1, (int) Math.round(h * s));
        Image scaled = img.getScaledInstance(dw, dh, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    private class FileDropHandler extends TransferHandler {
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport s) {
            if(!canImport(s))
                return false;
            try {
                List<File> files = (List<File>) s.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                if(!files.isEmpty() && confirmDiscard()) {
                    openFile(files.get(0).toPath());
                    return true;
                }
            } catch(Exception e) {
                error("Could not open dropped file: " + e.getMessage());
            }
            return false;
        }
    }

    /** Launches the GUI, optionally opening {@code initial}. */
    public static void launch(Path initial) {
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch(Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> {
            ResEditFrame f = new ResEditFrame();
            f.setVisible(true);
            if(initial != null)
                f.openFile(initial);
        });
    }
}
