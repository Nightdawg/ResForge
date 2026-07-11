package resforge.gui;

import resforge.model.ExternalTextures;
import resforge.model.GltfExport;
import resforge.model.GltfImport;
import resforge.model.ModelGeometry;
import resforge.res.Layer;
import resforge.res.Replacer;
import resforge.res.ResContainer;
import resforge.io.SafeFiles;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
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
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
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
public class ResForgeFrame extends JFrame {
    private ResContainer res;
    private Path file;
    private boolean dirty;
    private String suggestedName;
    private final DocumentRevision documentRevision = new DocumentRevision();

    private final LayerTableModel model = new LayerTableModel();
    private final JTable table = new JTable(model);
    private final JPanel detail = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel(" ");
    private final JSpinner versionSpinner =
            new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private boolean updatingVersion;
    private JButton addBtn, delBtn, upBtn, downBtn;
    private AudioPlayerPanel currentPlayer;
    private javax.swing.Timer animTimer;
    private final ThumbnailCache thumbCache = new ThumbnailCache();
    private final JTextField pathField = new JTextField("(no file open)");

    /** Builds the per-layer detail/editor panels; calls back through {@link EditorHost}. */
    private final LayerEditors editors = new LayerEditors(new EditorHost() {
        public ResContainer res() { return res; }
        public void setLayerPayload(int idx, byte[] payload) { ResForgeFrame.this.setLayerPayload(idx, payload); }
        public boolean applyBytes(int idx, byte[] bytes) { return ResForgeFrame.this.applyBytes(idx, bytes); }
        public void replaceFromFile(int idx, String layerName) { ResForgeFrame.this.replaceFromFile(idx, layerName); }
        public void exportLayer(int idx) { ResForgeFrame.this.exportLayer(idx); }
        public void replaceTexMask(int idx) { ResForgeFrame.this.replaceTexMask(idx); }
        public void exportTexMask(int idx) { ResForgeFrame.this.exportTexMask(idx); }
        public void setStatus(String s) { ResForgeFrame.this.setStatus(s); }
        public void error(String msg) { ResForgeFrame.this.error(msg); }
        public void setCurrentPlayer(AudioPlayerPanel p) { currentPlayer = p; }
        public void setAnimTimer(javax.swing.Timer t) { animTimer = t; }
        public void exportGltf() { doExportGltf(); }
        public void rebuildGltf() { doRebuildGltf(); }
    });

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

    private static final class RebuildProgress {
        private final JDialog dialog;

        RebuildProgress(ResForgeFrame owner, String sourceName) {
            dialog = new JDialog(owner, "Rebuilding from glTF", true);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

            JProgressBar progress = new JProgressBar();
            progress.setIndeterminate(true);
            JPanel body = new JPanel(new BorderLayout(UiScaling.scale(8), UiScaling.scale(8)));
            body.setBorder(UiScaling.emptyBorder(12, 12, 12, 12));
            body.add(new JLabel("Rebuilding from " + sourceName + "\u2026"), BorderLayout.NORTH);
            body.add(progress, BorderLayout.CENTER);
            dialog.setContentPane(body);
            dialog.pack();
            dialog.setResizable(false);
            dialog.setLocationRelativeTo(owner);
        }

        void showModal() {
            dialog.setVisible(true);
        }

        void close() {
            dialog.dispose();
        }
    }

    public ResForgeFrame() {
        super("ResForge");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                if(confirmDiscard())
                    dispose();
            }
        });

        setJMenuBar(buildMenuBar());
        JPanel north = new JPanel(new BorderLayout());
        north.add(buildToolBar(), BorderLayout.NORTH);
        north.add(buildPathBar(), BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(UiScaling.scale(36));
        table.getColumnModel().getColumn(0).setMaxWidth(UiScaling.scale(40));
        table.getColumnModel().getColumn(1).setMaxWidth(UiScaling.scale(44));
        table.getColumnModel().getColumn(1).setMinWidth(UiScaling.scale(44));
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
        left.setPreferredSize(UiScaling.scale(380, 480));

        detail.setBorder(UiScaling.emptyBorder(8, 8, 8, 8));
        showPlaceholder("Open a .res file to begin (File \u2192 Open, or drag one in).");

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, detail);
        split.setDividerLocation(UiScaling.scale(380));
        add(split, BorderLayout.CENTER);

        status.setBorder(UiScaling.emptyBorder(2, 6, 2, 6));
        add(status, BorderLayout.SOUTH);

        setTransferHandler(new FileDropHandler());
        setSize(UiScaling.scale(900), UiScaling.scale(600));
        setLocationByPlatform(true);
        updateLayerButtons();
    }

    @Override
    public void dispose() {
        documentRevision.invalidateOperations();
        if(currentPlayer != null)
            currentPlayer.dispose();
        super.dispose();
    }

    private JComponent buildLayerBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        addBtn = new JButton(action("Add", this::addLayer));
        addBtn.setToolTipText("Add a new layer (optionally from a file)");
        delBtn = new JButton(action("Delete", this::deleteLayer));
        upBtn = new JButton(action("Move \u2191", () -> moveLayer(-1)));
        upBtn.setToolTipText("Move the selected layer earlier (order is significant)");
        downBtn = new JButton(action("Move \u2193", () -> moveLayer(1)));
        downBtn.setToolTipText("Move the selected layer later (order is significant)");
        bar.add(addBtn);
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
        thumbCache.retainOnly(res.layers);
        dirty = s.dirty;
        documentRevision.modified();
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
        Path chosen = openDialog("Content for '" + name + "'  (Cancel = empty layer)", null);
        if(chosen != null) {
            try {
                data = Files.readAllBytes(chosen);
            } catch(Exception e) {
                error("Could not read file: " + e.getMessage());
                return;
            }
            // Adding an image from a raw picture: wrap it in a valid image header
            // with the next free id, so it's a real (editable, animatable) image layer.
            if(name.equals("image") && resforge.layers.ImageMagic.formatAt(data, 0) != null) {
                int newId = nextImageId();
                data = resforge.layers.ImageHeaderCodec.build(newId, 0, 0, false, data);
                setStatus("Wrapped image as a new layer with id=" + newId);
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
        thumbCache.remove(l);
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

    /* ------------------------------------------------------------------ menus */

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        fileMenu.add(item("Open", KeyEvent.VK_L, this::doOpen));
        fileMenu.add(item("Fetch from server", KeyEvent.VK_R, this::doFetch));
        fileMenu.add(item("Open from game cache", KeyEvent.VK_O, this::doOpenFromCache));
        fileMenu.add(item("Save As", KeyEvent.VK_S, this::doSaveAs));
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

        JMenu options = new JMenu("Options");
        JCheckBoxMenuItem darkMode = new JCheckBoxMenuItem("Dark mode", Theme.isDarkPreferred());
        darkMode.addActionListener(e -> {
            Theme.setDark(darkMode.isSelected());
            setStatus(darkMode.isSelected() ? "Dark mode on." : "Dark mode off.");
        });
        options.add(darkMode);
        options.add(menuItem("UI scale\u2026", this::doUiScale));
        bar.add(options);

        JMenu help = new JMenu("Help");
        help.add(menuItem("About", this::doAbout));
        bar.add(help);
        return bar;
    }

    private JComponent buildPathBar() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBorder(UiScaling.emptyBorder(3, 6, 3, 6));
        JLabel lab = new JLabel("File: ");
        lab.setFont(lab.getFont().deriveFont(Font.BOLD));
        pathField.setEditable(false);
        pathField.setBorder(BorderFactory.createEmptyBorder());
        pathField.setOpaque(false);
        pathField.setToolTipText("Full path of the open file (read-only; select to copy)");
        bar.add(lab, BorderLayout.WEST);
        bar.add(pathField, BorderLayout.CENTER);

        JLabel vl = new JLabel("Resource version: ");
        versionSpinner.setToolTipText("Resource format version (0\u201365535). Saved into the file header.");
        versionSpinner.setEnabled(false);
        versionSpinner.setMaximumSize(UiScaling.scale(90, 28));
        versionSpinner.setPreferredSize(UiScaling.scale(90, 28));
        versionSpinner.addChangeListener(e -> {
            if(updatingVersion || res == null)
                return;
            pushUndo();
            res.version = (Integer) versionSpinner.getValue();
            markDirty();
            setStatus("Resource version set to " + res.version);
        });
        JPanel east = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        east.add(vl);
        east.add(versionSpinner);
        bar.add(east, BorderLayout.EAST);
        return bar;
    }

    private void updatePath() {
        pathField.setText(file != null ? file.toAbsolutePath().toString() : "(no file open)");
        pathField.setCaretPosition(0);
    }

    private JComponent buildToolBar() {
        JToolBar row1 = new JToolBar();
        row1.setFloatable(false);
        row1.setAlignmentX(Component.LEFT_ALIGNMENT);
        row1.add(new JButton(action("Open File", this::doOpen)));
        row1.addSeparator();
        row1.add(new JButton(action("Fetch from Server", this::doFetch)));
        row1.addSeparator();
        row1.add(new JButton(action("Open from Cache (AppData)", this::doOpenFromCache)));

        JToolBar row2 = new JToolBar();
        row2.setFloatable(false);
        row2.setAlignmentX(Component.LEFT_ALIGNMENT);
        row2.add(new JButton(action("View 3D", this::doView3D)));
        row2.addSeparator();
        row2.add(new JButton(action("Export to glTF", this::doExportGltf)));
        row2.addSeparator();
        row2.add(new JButton(action("Rebuild from glTF", this::doRebuildGltf)));
        row2.addSeparator();
        row2.add(new JButton(action("References", this::doShowReferences)));

        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.add(row1);
        rows.add(row2);
        return rows;
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
        DocumentRevision.Token operation = documentRevision.beginOperation();
        setStatus("Opening " + p.getFileName() + " \u2026");
        Thread t = new Thread(() -> {
            ResContainer parsed = null;
            String err = null;
            try {
                parsed = ResContainer.parse(Files.readAllBytes(p));
            } catch(Exception e) {
                err = e.getMessage();
            }
            final ResContainer result = parsed;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                if(!finishOperation(operation, "Open"))
                    return;
                if(result == null) {
                    error("Could not open " + p + ":\n" + error);
                    setStatus("Open failed");
                    return;
                }
                applyLoaded(result, p, p.toAbsolutePath().toString(),
                        "Opened " + p.getFileName() + " \u2014 res-version " + result.version
                                + ", " + result.layers.size() + " layers");
            });
        }, "res-open");
        t.setDaemon(true);
        t.start();
    }

    /** Loads an already-validated remote resource (no local file yet; Save As prompts). */
    private void openRemote(ResContainer parsed, String url, String status) {
        applyLoaded(parsed, null, url, status);
        this.suggestedName = remoteFileName(url);
    }

    /** Derives a "name.res" save suggestion from a fetched resource URL/path. */
    private static String remoteFileName(String url) {
        String n = url;
        int slash = n.lastIndexOf('/');
        if(slash >= 0)
            n = n.substring(slash + 1);
        int q = n.indexOf('?');
        if(q >= 0)
            n = n.substring(0, q);
        n = n.strip();
        if(n.isEmpty())
            n = "resource";
        return n.toLowerCase().endsWith(".res") ? n : n + ".res";
    }

    private void applyLoaded(ResContainer parsed, Path f, String pathText, String status) {
        documentRevision.replaceDocument();
        applyDocument(parsed, f, pathText, status);
    }

    private void applyRebuilt(ResContainer parsed, Path f, String pathText, String status) {
        applyDocument(parsed, f, pathText, status);
        markDirty();
    }

    private void applyDocument(ResContainer parsed, Path f, String pathText, String status) {
        this.res = parsed;
        this.file = f;
        this.suggestedName = null;
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
        pathField.setText(pathText);
        pathField.setCaretPosition(0);
        updateLayerButtons();
        undoStack.clear();
        redoStack.clear();
        updateUndoState();
        setStatus(status);
    }

    private boolean finishOperation(DocumentRevision.Token operation, String name) {
        if(documentRevision.complete(operation))
            return true;
        if(documentRevision.isLatest(operation))
            setStatus(name + " result ignored because the document changed");
        return false;
    }

    private void doFetch() {
        if(!confirmDiscard())
            return;
        FetchDialog.Selection sel = FetchDialog.show(this);
        if(sel == null)
            return;
        if(sel.path.isEmpty()) {
            error("Please enter a resource path.");
            return;
        }
        fetchFromServer(sel.path, sel.base);
    }

    /** Fetch a resource fresh from the server on a background thread and open it,
     *  recording the path in the fetch history on success. Shared by the Fetch
     *  dialog and "Open from cache" (which only supplies names — the bytes always
     *  come from the server, so you get the latest version). */
    private void fetchFromServer(String path, String base) {
        DocumentRevision.Token operation = documentRevision.beginOperation();
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(ResForgeFrame.class);
        prefs.put("resBaseUrl", base);
        List<String> history = FetchHistory.parse(prefs.get("fetchHistory", ""));
        String url = resforge.net.ResourceFetcher.urlFor(base, path);
        setStatus("Fetching " + url + " \u2026");
        Thread t = new Thread(() -> {
            byte[] data = null;
            ResContainer parsed = null;
            String err = null;
            try {
                data = resforge.net.ResourceFetcher.fetch(base, path);
                parsed = ResContainer.parse(data);
            } catch(Exception ex) {
                err = ex.getMessage();
            }
            final byte[] result = data;
            final ResContainer resource = parsed;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                if(!finishOperation(operation, "Fetch"))
                    return;
                if(resource == null) {
                    error("Fetch failed:\n" + error);
                    setStatus("Fetch failed");
                    return;
                }
                String fetchedStatus = "Fetched " + url + " (" + result.length + " bytes)";
                openRemote(resource, url, fetchedStatus);
                try {
                    prefs.put("fetchHistory",
                            FetchHistory.serialize(FetchHistory.add(history, path)));
                } catch(IllegalArgumentException | SecurityException e) {
                    setStatus(fetchedStatus + " \u2014 history not saved: " + e.getMessage());
                }
            });
        }, "res-fetch");
        t.setDaemon(true);
        t.start();
    }

    /** Scan the local Haven cache for the resources the player already has, then
     *  let them pick one to fetch fresh from the server. We use the cache only to
     *  recover the resource <em>names</em>; the bytes always come from the server. */
    private void doOpenFromCache() {
        if(!confirmDiscard())
            return;
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(ResForgeFrame.class);
        String stored = prefs.get("cacheDir", null);
        Path cacheDir = (stored != null) ? Path.of(stored)
                : resforge.net.CacheIndex.defaultCacheDir().orElse(null);
        if(cacheDir == null || !Files.isDirectory(cacheDir)) {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Locate the Haven cache folder (\u2026/Haven and Hearth/data)");
            if(cacheDir != null)
                fc.setCurrentDirectory(cacheDir.toFile());
            if(fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
                return;
            cacheDir = fc.getSelectedFile().toPath();
        }
        prefs.put("cacheDir", cacheDir.toString());
        CachePickerDialog.Selection sel = CachePickerDialog.show(this, cacheDir, this::setStatus);
        if(sel != null)
            fetchFromServer(sel.path, sel.base);
    }

    private void doOpen() {
        if(!confirmDiscard())
            return;
        Path chosen = openDialog("Open resource", new FileNameExtensionFilter("Haven resource (*.res)", "res"));
        if(chosen != null)
            openFile(chosen);
    }

    private void doSaveAs() {
        if(res == null)
            return;
        String defName = (file != null) ? file.getFileName().toString()
                : (suggestedName != null ? suggestedName : null);
        Path chosen = saveDialog("Save resource as", defName);
        if(chosen != null) {
            Path p = ensureResExtension(chosen);
            if(writeRes(p)) {
                this.file = p;
                updateTitle();
                updatePath();
            }
        }
    }

    /** Guarantees the chosen path ends with {@code .res} so saved files always
     *  carry the extension (e.g. a fetched "male" becomes "male.res"). */
    private static Path ensureResExtension(Path p) {
        String n = p.getFileName().toString();
        if(!n.toLowerCase().endsWith(".res"))
            p = p.resolveSibling(n + ".res");
        return p;
    }

    private boolean writeRes(Path p) {
        byte[] data;
        try {
            data = res.serialize();
        } catch(Exception e) {
            error("Could not save: " + e.getMessage());
            return false;
        }
        if(!writeRes(p, data, message -> error(message)))
            return false;
        dirty = false;
        updateTitle();
        setStatus("Saved " + p.getFileName());
        return true;
    }

    static boolean writeRes(Path p, byte[] data, java.util.function.Consumer<String> onError) {
        try {
            SafeFiles.write(p, data);
            return true;
        } catch(Exception e) {
            onError.accept("Could not save: " + e.getMessage());
            return false;
        }
    }

    private void doRebuildGltf() {
        if(res == null)
            return;
        boolean hasGeom = res.layers.stream().anyMatch(l -> l.name.equals("vbuf2"));
        if(!hasGeom) {
            info("This resource has no 3D geometry (vbuf2) to rebuild.");
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "Rebuild regenerates the model's geometry from the glTF, allowing added or\n"
                        + "removed vertices. It isn't byte-lossless, so verify the\n"
                        + "result in-game. Continue?",
                "Rebuild from glTF", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if(ok != JOptionPane.OK_OPTION)
            return;
        Path chosen = openDialog("Rebuild from glTF", new FileNameExtensionFilter("Binary glTF (*.glb)", "glb"));
        if(chosen == null)
            return;
        final java.io.File sel = chosen.toFile();
        final byte[] orig = res.serialize();
        final Path curFile = file;
        final String curPath = pathField.getText();
        final DocumentRevision.Token operation = documentRevision.beginOperation();
        final RebuildProgress progress = new RebuildProgress(this, sel.getName());
        setStatus("Rebuilding from " + sel.getName() + " \u2026");
        Thread t = new Thread(() -> {
            GltfImport.RebuildResult r = null;
            String err = null;
            try {
                byte[] glb = Files.readAllBytes(sel.toPath());
                r = GltfImport.rebuild(orig, glb);
            } catch(Exception e) {
                err = e.getMessage();
            }
            final GltfImport.RebuildResult rr = r;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                progress.close();
                if(!finishOperation(operation, "Rebuild"))
                    return;
                if(rr == null) {
                    error("glTF rebuild failed: " + error);
                    return;
                }
                try {
                    applyRebuilt(ResContainer.parse(rr.res), curFile, curPath,
                            "Rebuilt " + rr.vertices + " vertices, " + rr.triangles + " triangles"
                                    + (rr.skinned ? " (with skinning)" : "") + (rr.skel ? " (skeleton re-posed)" : "")
                                    + " from " + sel.getName() + " \u2014 Save to keep changes");
                } catch(Exception e) {
                    error("glTF rebuild failed: " + e.getMessage());
                }
            });
        }, "gltf-rebuild");
        t.setDaemon(true);
        t.start();
        progress.showModal();
    }

    private void doView3D() {
        if(res == null)
            return;
        final byte[] snapshot = res.serialize();
        final String title = file != null ? file.getFileName().toString() : "model";
        setStatus("Building 3D model \u2026");
        Thread t = new Thread(() -> {
            ModelGeometry g = null;
            boolean hasExt = false;
            String err = null;
            try {
                ResContainer parsed = ResContainer.parse(snapshot);
                g = ModelGeometry.from(parsed);
                hasExt = ExternalTextures.hasExternalStatic(parsed);
            } catch(Exception e) {
                err = e.getMessage();
            }
            final ModelGeometry geo = g;
            final boolean hasExternal = hasExt;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                setStatus("Ready");
                if(geo == null) {
                    info(error != null ? "Could not build the 3D model: " + error
                            : "This resource has no 3D geometry (vbuf2/mesh) to view.");
                    return;
                }
                show3DDialog(title, snapshot, geo, hasExternal);
            });
        }, "model-3d");
        t.setDaemon(true);
        t.start();
    }

    private void show3DDialog(String title, byte[] snapshot, ModelGeometry geoPlain, boolean hasExternal) {
        JDialog dlg = new JDialog(this, "3D view \u2014 " + title, false);
        dlg.setLayout(new BorderLayout());

        JLabel hint = new JLabel(" Drag: orbit \u00b7 Shift/Right-drag: pan \u00b7 Wheel: zoom"
                + " \u2014 shown in bind pose (no skinning/animation)");
        Theme.muted(hint);
        hint.setBorder(UiScaling.emptyBorder(2, 4, 2, 4));
        dlg.add(hint, BorderLayout.SOUTH);

        // Only offered when the model actually has external static materials to resolve
        // (detected offline); off by default, since resolving fetches other .res files,
        // so the otherwise-offline viewer only goes online on request.
        JCheckBox resolveExt = hasExternal
                ? new JCheckBox("Resolve external textures (network)", false)
                : null;

        final ModelGeometry[] resolvedCache = {null};
        final Component[] installed = {null, null};   // current NORTH, CENTER

        java.util.function.Consumer<ModelGeometry> install = geo -> {
            if(installed[0] != null)
                dlg.remove(installed[0]);
            if(installed[1] != null)
                dlg.remove(installed[1]);
            Model3DView view = new Model3DView(geo);
            JPanel north = build3DControls(geo, view, resolveExt);
            dlg.add(north, BorderLayout.NORTH);
            dlg.add(view, BorderLayout.CENTER);
            installed[0] = north;
            installed[1] = view;
            dlg.revalidate();
            dlg.repaint();
        };

        if(resolveExt != null)
            resolveExt.addActionListener(e -> {
                if(!resolveExt.isSelected()) {
                    install.accept(geoPlain);
                    return;
                }
                if(resolvedCache[0] != null) {
                    install.accept(resolvedCache[0]);
                    return;
                }
                resolveExt.setEnabled(false);
                setStatus("Resolving external textures \u2026");
                java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(ResForgeFrame.class);
                String base = prefs.get("resBaseUrl", resforge.net.ResourceFetcher.DEFAULT_BASE);
                Thread t = new Thread(() -> {
                    ModelGeometry g = null;
                    try {
                        ExternalTextures.Fetcher fetcher = path -> {
                            try {
                                return ResContainer.parse(resforge.net.ResourceFetcher.fetch(base, path));
                            } catch(Exception ex) {
                                return null;
                            }
                        };
                        g = ModelGeometry.from(ResContainer.parse(snapshot), fetcher);
                    } catch(Exception ex) {
                        /* leave g null */
                    }
                    final ModelGeometry geo = g;
                    SwingUtilities.invokeLater(() -> {
                        resolveExt.setEnabled(true);
                        if(geo == null) {
                            resolveExt.setSelected(false);
                            setStatus("Ready");
                            info("Could not resolve external textures.");
                            return;
                        }
                        resolvedCache[0] = geo;
                        install.accept(geo);
                        setStatus(geo.externalTextures.isEmpty()
                                ? "No external textures resolved (offline or none present)"
                                : "Ready");
                    });
                }, "model-3d-resolve");
                t.setDaemon(true);
                t.start();
            });

        install.accept(geoPlain);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    /** Builds the NORTH control panel (toggles + per-material texture pickers) for a
     *  3D-view geometry, wired to {@code view}. Rebuilt whenever the geometry is swapped
     *  (e.g. when external textures are resolved), reusing the persistent {@code resolveExt}
     *  toggle when present ({@code null} when the model has no external static materials). */
    private JPanel build3DControls(ModelGeometry geo, Model3DView view, JCheckBox resolveExt) {
        JCheckBox shaded = new JCheckBox("Shaded", true);
        JCheckBox wire = new JCheckBox("Wireframe", false);
        JCheckBox tex = new JCheckBox("Textured", geo.hasTextures());
        tex.setEnabled(geo.hasTextures());
        shaded.addActionListener(e -> view.setShaded(shaded.isSelected()));
        wire.addActionListener(e -> view.setWireframe(wire.isSelected()));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        controls.add(shaded);
        controls.add(tex);
        controls.add(wire);
        controls.add(new JButton(action("Reset view", view::resetView)));
        if(resolveExt != null)
            controls.add(resolveExt);
        controls.add(new JLabel("   " + geo.vertexCount + " verts \u00b7 "
                + geo.triangleCount + " tris \u00b7 " + geo.submeshCount + " part(s)"
                + (geo.hasTextures() ? "" : " \u00b7 no local textures")));

        // Per-material texture pickers, laid out over two balanced rows (so a model
        // with many materials doesn't stretch into one very wide row). Empty when
        // there's no real choice (fewer than two local textures).
        java.util.List<JComboBox<Integer>> texCombos = new java.util.ArrayList<>();
        java.util.List<JPanel> texRows = buildTexturePickerRows(geo, view, texCombos);
        tex.addActionListener(e -> {
            view.setTextured(tex.isSelected());
            for(JComboBox<Integer> c : texCombos)
                c.setEnabled(tex.isSelected());
        });

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(controls);
        for(JPanel r : texRows) {
            r.setAlignmentX(Component.LEFT_ALIGNMENT);
            north.add(r);
        }
        return north;
    }

    /** Build the per-material texture-picker rows for the 3D dialog. One combo per
     *  <em>locally-textured</em> material (listing the resource's local {@code tex}
     *  layers by id, defaulting to the authored one); materials whose base is non-local
     *  (an external-static {@code mlink}/external string, a runtime varmat, a {@code Dyntex}
     *  {@code spr} addition, or an {@code otex} overlay only) get no combo — the local
     *  palette isn't theirs to swap. The combos
     *  are split over two balanced rows so a model with many materials doesn't make one
     *  very wide row. Returns the rows (empty when there's no real choice — fewer than
     *  two local textures, or no locally-textured material) and collects every combo
     *  into {@code outCombos} for the Textured toggle. Package-private + static so the
     *  layout can be unit-tested without a frame. */
    static java.util.List<JPanel> buildTexturePickerRows(ModelGeometry geo, Model3DView view,
                                                         java.util.List<JComboBox<Integer>> outCombos) {
        java.util.List<JPanel> rows = new java.util.ArrayList<>();
        java.util.List<Integer> palette = new java.util.ArrayList<>();
        for(int o = 0; o < geo.localTextures.size(); o++)
            if(geo.localTextures.get(o) != null)
                palette.add(o);
        if(!geo.hasTextures() || palette.size() <= 1)
            return rows;

        // Only materials with a local base texture are swappable; non-local ones
        // (external-static mlink/external string, runtime varmat, Dyntex spr, otex-only) are skipped.
        java.util.List<Integer> swappable = new java.util.ArrayList<>();
        for(int mi = 0; mi < geo.materials.size(); mi++)
            if(geo.materials.get(mi).localBase)
                swappable.add(mi);
        if(swappable.isEmpty())
            return rows;

        boolean many = swappable.size() > 1;
        // One (label?, combo) group per swappable material.
        java.util.List<java.util.List<java.awt.Component>> groups = new java.util.ArrayList<>();
        for(int mi : swappable) {
            ModelGeometry.Material mat = geo.materials.get(mi);
            JComboBox<Integer> combo = new JComboBox<>(palette.toArray(new Integer[0]));
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override public java.awt.Component getListCellRendererComponent(
                        JList<?> list, Object value, int index, boolean sel, boolean focus) {
                    super.getListCellRendererComponent(list, value, index, sel, focus);
                    int ord = (Integer) value;
                    setText("tex id " + geo.localTexIds.get(ord)
                            + (ord == mat.defaultTex ? " (default)" : ""));
                    return this;
                }
            });
            combo.setSelectedItem(mat.defaultTex);
            final int fmi = mi;
            combo.addActionListener(e -> view.setMaterialTexture(fmi, (Integer) combo.getSelectedItem()));
            java.util.List<java.awt.Component> grp = new java.util.ArrayList<>();
            if(many)
                grp.add(new JLabel("mat " + mat.matid + ":"));
            grp.add(combo);
            groups.add(grp);
            outCombos.add(combo);
        }
        // Split the groups over two balanced rows; the first carries the caption.
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row1.add(new JLabel("Texture:"));
        int half = (groups.size() + 1) / 2;
        for(int i = 0; i < groups.size(); i++) {
            JPanel target = (i < half) ? row1 : row2;
            for(java.awt.Component c : groups.get(i))
                target.add(c);
        }
        rows.add(row1);
        if(row2.getComponentCount() > 0)
            rows.add(row2);
        return rows;
    }

    private void doExportGltf() {
        if(res == null)
            return;
        final String modelName = file != null ? file.getFileName().toString() : "model";
        final byte[] snapshot = res.serialize();
        setStatus("Exporting glTF \u2026");
        Thread t = new Thread(() -> {
            GltfExport.Result r = null;
            String err = null;
            try {
                r = GltfExport.toGlb(ResContainer.parse(snapshot), modelName);
            } catch(Exception e) {
                err = e.getMessage();
            }
            final GltfExport.Result rr = r;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                if(rr == null) {
                    error("glTF export failed: " + error);
                    return;
                }
                if(rr.vertices == 0 || rr.triangles == 0) {
                    info("This resource has no 3D geometry (vbuf2/mesh) to export.");
                    setStatus("Ready");
                    return;
                }
                Path chosen = saveDialog("Export glTF", baseName() + ".glb");
                if(chosen != null) {
                    try {
                        SafeFiles.write(chosen, rr.glb);
                        setStatus("Exported " + rr.vertices + " vertices, " + rr.triangles
                                + " triangles (" + rr.submeshes + " submeshes, " + rr.textures
                                + " texture(s)) as glTF");
                    } catch(Exception e) {
                        error("glTF export failed: " + e.getMessage());
                    }
                }
            });
        }, "gltf-export");
        t.setDaemon(true);
        t.start();
    }

    private void doShowReferences() {
        if(res == null) {
            info("Open a .res file first.");
            return;
        }
        String name = file != null ? file.getFileName().toString() : (suggestedName != null ? suggestedName : "resource");
        String report = resforge.res.References.scan(res).render(name);

        JTextArea area = new JTextArea(report);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiScaling.scale(12)));
        area.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(UiScaling.scale(520, 420));

        JButton copy = new JButton(action("Copy", () ->
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(report), null)));
        JButton save = new JButton(action("Save", () -> {
            Path chosen = saveDialog("Save reference list", baseName() + "-references.txt");
            if(chosen != null) {
                try {
                    Files.writeString(chosen, report);
                    setStatus("Saved reference list \u2192 " + chosen.getFileName());
                } catch(Exception e) {
                    error("Save failed: " + e.getMessage());
                }
            }
        }));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(copy);
        buttons.add(save);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(sp, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(this, panel, "References \u2014 " + name,
                JOptionPane.PLAIN_MESSAGE);
    }

    /* ----------------------------------------------------------- detail panel */

    private void showSelected() {
        if(currentPlayer != null) {
            currentPlayer.dispose();
            currentPlayer = null;
        }
        if(animTimer != null) {
            animTimer.stop();
            animTimer = null;
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
        head.setFont(head.getFont().deriveFont(Font.BOLD, UiScaling.font(14f)));
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(head);
        content.add(Box.createVerticalStrut(8));

        switch(GuiSupport.kind(l.name)) {
            case "icon":
            case "texture":
            case "terrain tile":
                editors.buildImagePanel(content, idx, l);
                break;
            case "text":
                editors.buildTextPanel(content, idx, l);
                break;
            case "props":
            case "keybind":
            case "material":
            case "hitbox":
            case "collision":
            case "equip point":
            case "light":
                editors.buildJsonPanel(content, idx, l);
                break;
            case "animation":
                editors.buildAnimPanel(content, idx, l);
                break;
            case "sound":
            case "font":
            case "music":
                editors.buildMediaPanel(content, idx, l);
                break;
            case "3D model":
                editors.buildModelPanel(content, l);
                break;
            case "code":
                editors.buildCodePanel(content, idx, l);
                break;
            case "dependencies":
            case "links":
            case "source":
            case "tileset":
            case "flavor":
                editors.buildReferencePanel(content, idx, l);
                break;
            case "skeleton":
            case "skeletal anim":
            case "mesh anim":
                editors.buildRigPanel(content, idx, l);
                break;
            default:
                editors.buildRawPanel(content, idx, l);
        }

        detail.removeAll();
        detail.add(content, BorderLayout.CENTER);
        detail.revalidate();
        detail.repaint();
    }

    private void replaceTexMask(int idx) {
        Path chosen = openDialog("Replace alpha mask", filterFor("tex"));
        if(chosen == null)
            return;
        try {
            byte[] newMask = Files.readAllBytes(chosen);
            byte[] payload = resforge.layers.TexMaskCodec.replaceMask(res.layers.get(idx).data, newMask);
            setLayerPayload(idx, payload);
            setStatus("Replaced alpha mask in layer " + idx);
        } catch(Exception e) {
            error("Replace mask failed: " + e.getMessage());
        }
    }

    private void exportTexMask(int idx) {
        Layer l = res.layers.get(idx);
        resforge.layers.TexInfo ti = resforge.layers.TexInfo.parse(l.data);
        byte[] maskBytes = resforge.layers.TexMaskCodec.mask(l.data);
        if(maskBytes == null) {
            error("This layer has no alpha mask to export.");
            return;
        }
        String ext = ti.maskFormat != null ? ti.maskFormat : "png";
        Path chosen = saveDialog("Export alpha mask", baseName() + "_" + idx + "_tex_mask." + ext);
        if(chosen == null)
            return;
        try {
            SafeFiles.write(chosen, maskBytes);
            setStatus("Exported mask of layer " + idx + " \u2192 " + chosen.getFileName());
        } catch(Exception e) {
            error("Export mask failed: " + e.getMessage());
        }
    }

    /** The next free image id (max existing image-layer id + 1, or 0 if none). */
    private int nextImageId() {
        int max = -1;
        if(res != null) {
            for(Layer ly : res.layers) {
                if(!ly.name.equals("image"))
                    continue;
                resforge.layers.ImageInfo ii = resforge.layers.ImageInfo.parse(ly.data);
                if(ii.recognized)
                    max = Math.max(max, ii.id);
            }
        }
        return max + 1;
    }

    /** Replaces a layer's entire payload directly (for header/whole-payload edits). */
    private void setLayerPayload(int idx, byte[] payload) {
        if(res == null)
            return;
        Snapshot before = snapshot();
        Layer old = res.layers.get(idx);
        res.layers.set(idx, new Layer(old.name, payload));
        thumbCache.remove(old);
        commit(before);
        markDirty();
        int sel = table.getSelectedRow();
        model.fireTableRowsUpdated(idx, idx);
        if(sel == idx)
            showSelected();
    }

    /* ------------------------------------------------------------ edit actions */

    private void replaceFromFile(int idx, String layerName) {
        Path chosen = openDialog("Replace layer", filterFor(layerName));
        if(chosen == null)
            return;
        try {
            byte[] bytes = Files.readAllBytes(chosen);
            if(applyBytes(idx, bytes))
                setStatus("Replaced layer " + idx + " (" + layerName + ")");
        } catch(Exception e) {
            error("Replace failed: " + e.getMessage());
        }
    }

    /** Routes every in-memory edit through the tested Replacer (by absolute index). */
    private boolean applyBytes(int idx, byte[] bytes) {
        if(res == null)
            return false;
        Snapshot before = snapshot();
        Layer old = res.layers.get(idx);
        try {
            Replacer.replace(res, "#" + idx, bytes);
        } catch(Replacer.ReplaceException e) {
            error(e.getMessage());
            return false;
        }
        thumbCache.remove(old);
        commit(before);
        markDirty();
        int sel = table.getSelectedRow();
        model.fireTableRowsUpdated(idx, idx);
        if(sel == idx)
            showSelected();
        return true;
    }

    private void exportLayer(int idx) {
        Layer l = res.layers.get(idx);
        GuiSupport.Export ex = GuiSupport.export(l);
        Path chosen = saveDialog("Export layer", baseName() + "_" + idx + "_" + l.name + "." + ex.ext);
        if(chosen == null)
            return;
        try {
            SafeFiles.write(chosen, ex.data);
            setStatus("Exported layer " + idx + " \u2192 " + chosen.getFileName());
        } catch(Exception e) {
            error("Export failed: " + e.getMessage());
        }
    }

    private static FileNameExtensionFilter filterFor(String layerName) {
        switch(layerName) {
            case "image":
            case "tex":
            case "tile":   return new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp");
            case "audio2": return new FileNameExtensionFilter("Ogg Vorbis (*.ogg)", "ogg");
            case "font":   return new FileNameExtensionFilter("Fonts", "ttf", "otf");
            case "midi":   return new FileNameExtensionFilter("MIDI (*.mid)", "mid", "midi");
            default:       return null;
        }
    }

    /* ------------------------------------------------------------------- utils */

    private void showPlaceholder(String text) {
        detail.removeAll();
        JLabel lab = new JLabel(text, JLabel.CENTER);
        Theme.muted(lab);
        detail.add(lab, BorderLayout.CENTER);
        detail.revalidate();
        detail.repaint();
    }

    private void markDirty() {
        dirty = true;
        documentRevision.modified();
        updateTitle();
    }

    private void updateTitle() {
        String n = (file != null) ? file.getFileName().toString() : "(unsaved)";
        setTitle("ResForge \u2014 " + n + (dirty ? " *" : ""));
    }

    private boolean confirmDiscard() {
        if(!dirty)
            return true;
        int r = JOptionPane.showConfirmDialog(this, "Discard unsaved changes?",
                "Unsaved changes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return r == JOptionPane.YES_OPTION;
    }

    /* ------------------------------------------------- native file dialogs */

    /** True when running on Windows, where {@link FileDialog} renders the modern
     *  native Explorer picker but ignores {@link java.io.FilenameFilter} — so we
     *  filter through a wildcard pattern instead. */
    private static final boolean ON_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    /** Shows the OS-native "open" dialog. {@code filter} may be {@code null} to
     *  accept any file. On Windows this is the modern Explorer picker (with the
     *  editable address bar); it falls back to {@link FileDialog} if that native
     *  dialog can't be used. Returns the chosen path, or {@code null} if cancelled. */
    private Path openDialog(String title, FileNameExtensionFilter filter) {
        if(ON_WINDOWS) {
            WinFileDialogs.Result r = WinFileDialogs.open(
                    ownerHwnd(), title, initialDirPath(), null, toFilters(filter));
            if(r.available)
                return r.path;
        }
        FileDialog fd = new FileDialog(this, title, FileDialog.LOAD);
        File d = dir();
        if(d != null)
            fd.setDirectory(d.getAbsolutePath());
        if(filter != null) {
            fd.setFilenameFilter((dirf, name) -> filter.accept(new File(dirf, name)));
            if(ON_WINDOWS)
                fd.setFile(wildcards(filter));
        }
        fd.setVisible(true);
        return chosenPath(fd);
    }

    /** Shows the OS-native "save" dialog, pre-filled with {@code defaultName}
     *  (may be {@code null}). On Windows this is the modern Explorer picker (with
     *  the editable address bar); it falls back to {@link FileDialog} if that
     *  native dialog can't be used. Returns the chosen path, or {@code null} if
     *  cancelled. The native dialog handles overwrite confirmation itself. */
    private Path saveDialog(String title, String defaultName) {
        if(ON_WINDOWS) {
            WinFileDialogs.Result r = WinFileDialogs.save(ownerHwnd(), title, initialDirPath(),
                    defaultName, toFilters(new FileNameExtensionFilter("Haven resource (*.res)", "res")));
            if(r.available)
                return r.path;
        }
        FileDialog fd = new FileDialog(this, title, FileDialog.SAVE);
        File d = dir();
        if(d != null)
            fd.setDirectory(d.getAbsolutePath());
        if(defaultName != null)
            fd.setFile(defaultName);
        fd.setVisible(true);
        return chosenPath(fd);
    }

    /** The folder the native dialog should open in (the current file's folder, or
     *  {@code null} to let Windows pick its usual default). */
    private String initialDirPath() {
        File d = dir();
        return d != null ? d.getAbsolutePath() : null;
    }

    /** This frame's native window handle for dialog ownership, or 0 if unavailable. */
    private long ownerHwnd() {
        try {
            com.sun.jna.Pointer p = com.sun.jna.Native.getWindowPointer(this);
            return p != null ? com.sun.jna.Pointer.nativeValue(p) : 0L;
        } catch(Throwable ignore) {
            return 0L;
        }
    }

    /** Maps a Swing extension filter to the modern dialog's file-type rows, always
     *  appending an "All files" row so nothing is ever hidden from view. */
    private static java.util.List<WinFileDialogs.Filter> toFilters(FileNameExtensionFilter filter) {
        java.util.List<WinFileDialogs.Filter> out = new java.util.ArrayList<>();
        if(filter != null)
            out.add(new WinFileDialogs.Filter(filter.getDescription(), wildcards(filter)));
        out.add(new WinFileDialogs.Filter("All files (*.*)", "*.*"));
        return out;
    }

    private static Path chosenPath(FileDialog fd) {
        String name = fd.getFile();
        return name != null ? Path.of(fd.getDirectory(), name) : null;
    }

    /** Builds a Windows filter spec ({@code "*.png;*.jpg"}) from an extension filter. */
    private static String wildcards(FileNameExtensionFilter filter) {
        StringBuilder sb = new StringBuilder();
        for(String e : filter.getExtensions()) {
            if(sb.length() > 0)
                sb.append(';');
            sb.append("*.").append(e);
        }
        return sb.toString();
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
        JOptionPane.showMessageDialog(this, msg, "ResForge", JOptionPane.INFORMATION_MESSAGE);
    }

    private void doAbout() {
        info("ResForge\n\nA tool to view and edit Haven & Hearth .res files.\n"
                + "Swap icons, textures, sounds and fonts; edit text and properties;\n"
                + "edit 3D models through Blender via glTF. Unchanged layers are preserved byte-for-byte.");
    }

    private void doUiScale() {
        double current = UiScaling.storedScale();
        SpinnerNumberModel model = new SpinnerNumberModel(
                current, UiScaling.MIN_SCALE, UiScaling.MAX_SCALE, 0.05);
        JSpinner spinner = new JSpinner(model);
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.00"));
        ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(5);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(leftRow(new JLabel("Adjust the size of all editor text and controls.")));
        panel.add(leftRow(new JLabel(String.format(
                "1.00 = automatic default. Range %.2f\u2013%.1f.",
                UiScaling.MIN_SCALE, UiScaling.MAX_SCALE))));
        panel.add(Box.createVerticalStrut(6));
        JPanel pick = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        pick.add(new JLabel("Scale factor:"));
        pick.add(spinner);
        panel.add(leftRow(pick));
        Double launch = UiScaling.launchOverride();
        if(launch != null) {
            panel.add(Box.createVerticalStrut(6));
            panel.add(leftRow(new JLabel(String.format(
                    "Note: a launch override (%.2f) is active and overrides this until removed.",
                    launch))));
        }

        int r = JOptionPane.showConfirmDialog(this, panel, "UI scale",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if(r != JOptionPane.OK_OPTION)
            return;
        try {
            spinner.commitEdit();
        } catch(java.text.ParseException ignored) {
        }
        double chosen = UiScaling.clamp(((Number) spinner.getValue()).doubleValue());
        if(chosen == current) {
            setStatus("UI scale unchanged (" + fmtScale(chosen) + ").");
            return;
        }
        UiScaling.setStoredScale(chosen);
        setStatus("UI scale set to " + fmtScale(chosen) + " \u2014 restart to apply.");
        String extra = (launch != null)
                ? "\n\nA launch override is currently active, so this preference will\n"
                + "only take effect once that override is removed."
                : "";
        info("UI scale saved: " + fmtScale(chosen) + ".\n\n"
                + "Restart ResForge for the new scale to take effect." + extra);
    }

    private static String fmtScale(double v) {
        return String.format("\u00d7%.2f", v);
    }

    private static JComponent leftRow(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
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
            return false;          // the layer type/name is read-only
        }
    }

    /** A cached, scaled thumbnail for icon/texture layers, else null. */
    private Icon thumbnail(Layer l) {
        String kind = GuiSupport.kind(l.name);
        if(!kind.equals("icon") && !kind.equals("texture"))
            return null;
        return thumbCache.get(l, ResForgeFrame::makeThumbnail);
    }

    private static Icon makeThumbnail(Layer l) {
        String kind = GuiSupport.kind(l.name);
        if(!kind.equals("icon") && !kind.equals("texture"))
            return null;
        java.awt.image.BufferedImage img = GuiSupport.preview(l);
        if(img == null)
            return null;
        int max = UiScaling.scale(30);
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
        Theme.install();
        UiScaling.normalizeFonts();
        SwingUtilities.invokeLater(() -> {
            ResForgeFrame f = new ResForgeFrame();
            f.setVisible(true);
            if(initial != null)
                f.openFile(initial);
        });
    }
}
