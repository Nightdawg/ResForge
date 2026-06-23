package resforge.gui;

import resforge.model.GltfExport;
import resforge.model.GltfImport;
import resforge.res.Layer;
import resforge.res.Replacer;
import resforge.res.ResContainer;
import resforge.io.SafeFiles;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
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
public class ResForgeFrame extends JFrame {
    private ResContainer res;
    private Path file;
    private boolean dirty;
    private String suggestedName;

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

    public ResForgeFrame() {
        super("ResForge");
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

        JLabel vl = new JLabel("Resource version: ");
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

    /** Loads already-fetched remote bytes (no local file yet; Save As will prompt). */
    private void openRemote(byte[] data, String url, String status) {
        try {
            ResContainer parsed = ResContainer.parse(data);
            applyLoaded(parsed, null, url, status);
            this.suggestedName = remoteFileName(url);
        } catch(Exception e) {
            error("Downloaded data is not a valid .res:\n" + e.getMessage());
        }
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

    private void doFetch() {
        if(!confirmDiscard())
            return;
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(ResForgeFrame.class);
        String base = prefs.get("resBaseUrl", resforge.net.ResourceFetcher.DEFAULT_BASE);
        List<String> history = FetchHistory.parse(prefs.get("fetchHistory", ""));

        JTextField pathFld = new JTextField(24);
        JTextField baseFld = new JTextField(base, 24);

        JPanel form = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1;
        gc.anchor = java.awt.GridBagConstraints.WEST;
        gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gc.insets = new java.awt.Insets(0, 0, 4, 0);
        form.add(new JLabel("Resource path (e.g. gfx/borka/male):"), gc);
        form.add(pathFld, gc);

        // Offer previously-fetched paths as substring-matched, click-to-use suggestions.
        if(!history.isEmpty()) {
            javax.swing.DefaultListModel<String> histModel = new javax.swing.DefaultListModel<>();
            for(String h : history)
                histModel.addElement(h);
            javax.swing.JList<String> histList = new javax.swing.JList<>(histModel);
            histList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
            histList.setVisibleRowCount(6);
            JScrollPane histScroll = new JScrollPane(histList);
            histScroll.setBorder(BorderFactory.createTitledBorder(
                    "Recent fetches (click to use, double-click to fetch)"));

            Runnable refilter = () -> {
                histModel.clear();
                for(String h : FetchHistory.filter(history, pathFld.getText()))
                    histModel.addElement(h);
            };
            pathFld.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { refilter.run(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { refilter.run(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { refilter.run(); }
            });
            histList.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent ev) {
                    int idx = histList.locationToIndex(ev.getPoint());
                    if(idx < 0 || idx >= histModel.size()
                            || !histList.getCellBounds(idx, idx).contains(ev.getPoint()))
                        return;
                    String sel = histModel.getElementAt(idx);
                    pathFld.setText(sel);
                    pathFld.requestFocusInWindow();
                    pathFld.setCaretPosition(sel.length());
                    if(ev.getClickCount() >= 2) {
                        JOptionPane pane = (JOptionPane)
                                SwingUtilities.getAncestorOfClass(JOptionPane.class, histList);
                        if(pane != null)
                            pane.setValue(JOptionPane.OK_OPTION);
                    }
                }
            });
            gc.weighty = 1;
            gc.fill = java.awt.GridBagConstraints.BOTH;
            form.add(histScroll, gc);
            gc.weighty = 0;
            gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        }

        form.add(new JLabel("Server base URL:"), gc);
        form.add(baseFld, gc);
        int ok = JOptionPane.showConfirmDialog(this, form, "Fetch resource from server",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if(ok != JOptionPane.OK_OPTION)
            return;
        String path = pathFld.getText().strip();
        String useBase = baseFld.getText().strip();
        if(path.isEmpty()) {
            error("Please enter a resource path.");
            return;
        }
        fetchFromServer(path, useBase);
    }

    /** Fetch a resource fresh from the server on a background thread and open it,
     *  recording the path in the fetch history on success. Shared by the Fetch
     *  dialog and "Open from cache" (which only supplies names — the bytes always
     *  come from the server, so you get the latest version). */
    private void fetchFromServer(String path, String base) {
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(ResForgeFrame.class);
        prefs.put("resBaseUrl", base);
        List<String> history = FetchHistory.parse(prefs.get("fetchHistory", ""));
        String url = resforge.net.ResourceFetcher.urlFor(base, path);
        setStatus("Fetching " + url + " \u2026");
        Thread t = new Thread(() -> {
            byte[] data = null;
            String err = null;
            try {
                data = resforge.net.ResourceFetcher.fetch(base, path);
            } catch(Exception ex) {
                err = ex.getMessage();
            }
            final byte[] result = data;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                if(result == null) {
                    error("Fetch failed:\n" + error);
                    setStatus("Fetch failed");
                    return;
                }
                prefs.put("fetchHistory",
                        FetchHistory.serialize(FetchHistory.add(history, path)));
                openRemote(result, url, "Fetched " + url + " (" + result.length + " bytes)");
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
        showCachePicker(cacheDir);
    }

    /** Modal picker over the cache's resource names (substring-filtered); the
     *  chosen path is fetched fresh from the server via {@link #fetchFromServer}.
     *  The dialog opens immediately showing a "scanning" message and is filled in
     *  by a background scan (which can take a moment on a large cache), so the UI
     *  never appears frozen while the cache is read. */
    private void showCachePicker(Path dir) {
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(ResForgeFrame.class);
        String base = prefs.get("resBaseUrl", resforge.net.ResourceFetcher.DEFAULT_BASE);

        JTextField filterFld = new JTextField(30);
        filterFld.setEnabled(false);
        JTextField baseFld = new JTextField(base, 30);
        javax.swing.DefaultListModel<String> listModel = new javax.swing.DefaultListModel<>();
        listModel.addElement("Scanning game cache for resource names\u2026");
        javax.swing.JList<String> list = new javax.swing.JList<>(listModel);
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(16);
        // Render dynamic (dyn/) entries greyed, with a divider above the first one,
        // so the volatile account-attached resources are visually set apart.
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(javax.swing.JList<?> l, Object value,
                    int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, value, index, sel, focus);
                String s = String.valueOf(value);
                if(resforge.net.CacheIndex.isDynamic(s)) {
                    if(!sel)
                        setForeground(java.awt.Color.GRAY);
                    boolean firstDyn = index == 0 || !resforge.net.CacheIndex.isDynamic(
                            String.valueOf(l.getModel().getElementAt(index - 1)));
                    if(firstDyn)
                        setBorder(BorderFactory.createCompoundBorder(
                                BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color.LIGHT_GRAY),
                                getBorder()));
                }
                return this;
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        javax.swing.border.TitledBorder border = BorderFactory.createTitledBorder(
                "Scanning game cache for resource names\u2026");
        scroll.setBorder(border);

        // Holds the loaded names once the background scan finishes (empty until then).
        final List<String>[] all = new List[]{ java.util.Collections.<String>emptyList() };
        Runnable refilter = () -> {
            listModel.clear();
            for(String n : FetchHistory.filter(all[0], filterFld.getText()))
                listModel.addElement(n);
            if(!listModel.isEmpty())
                list.setSelectedIndex(0);
        };
        filterFld.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refilter.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refilter.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refilter.run(); }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent ev) {
                if(ev.getClickCount() < 2 || all[0].isEmpty())
                    return;
                int idx = list.locationToIndex(ev.getPoint());
                if(idx < 0 || idx >= listModel.size()
                        || !list.getCellBounds(idx, idx).contains(ev.getPoint()))
                    return;
                list.setSelectedIndex(idx);
                JOptionPane pane = (JOptionPane)
                        SwingUtilities.getAncestorOfClass(JOptionPane.class, list);
                if(pane != null)
                    pane.setValue(JOptionPane.OK_OPTION);
            }
        });

        // Scan on a background thread and populate the (already-visible) dialog.
        setStatus("Scanning game cache " + dir + " \u2026");
        Thread t = new Thread(() -> {
            List<String> found = null;
            String err = null;
            try {
                found = resforge.net.CacheIndex.scan(dir);
            } catch(Exception ex) {
                err = ex.getMessage();
            }
            final List<String> names = found;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                if(names == null) {
                    border.setTitle("Cache scan failed: " + error);
                    listModel.clear();
                    setStatus("Cache scan failed");
                    scroll.revalidate();
                    scroll.repaint();
                    return;
                }
                all[0] = names;
                filterFld.setEnabled(true);
                border.setTitle(names.isEmpty()
                        ? "No resources found in " + dir
                        : names.size() + " resources in your game cache (fetched fresh from the server)");
                refilter.run();
                scroll.revalidate();
                scroll.repaint();
                setStatus(names.size() + " resource(s) found in cache");
            });
        }, "cache-scan");
        t.setDaemon(true);
        t.start();

        JPanel form = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1;
        gc.anchor = java.awt.GridBagConstraints.WEST;
        gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gc.insets = new java.awt.Insets(0, 0, 4, 0);
        form.add(new JLabel("Filter (type any part of a path, e.g. borka):"), gc);
        form.add(filterFld, gc);
        gc.weighty = 1;
        gc.fill = java.awt.GridBagConstraints.BOTH;
        form.add(scroll, gc);
        gc.weighty = 0;
        gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        JLabel dynHint = new JLabel("Greyed \u201cdyn/\u201d entries are dynamic, account-attached "
                + "resources (listed last; may not be fetchable).");
        dynHint.setForeground(java.awt.Color.GRAY);
        dynHint.setFont(dynHint.getFont().deriveFont(dynHint.getFont().getSize2D() - 1f));
        form.add(dynHint, gc);
        form.add(new JLabel("Server base URL:"), gc);
        form.add(baseFld, gc);

        int ok = JOptionPane.showConfirmDialog(this, form, "Open from game cache",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if(ok != JOptionPane.OK_OPTION)
            return;
        if(all[0].isEmpty()) {
            error("The cache is still being scanned (or holds no resources). Please try again.");
            return;
        }
        String path = list.getSelectedValue();
        String useBase = baseFld.getText().strip();
        if(path == null || !all[0].contains(path)) {
            error("Please select a resource.");
            return;
        }
        fetchFromServer(path, useBase);
    }

    private void doOpen() {
        if(!confirmDiscard())
            return;
        JFileChooser fc = new JFileChooser(dir());
        fc.setFileFilter(new FileNameExtensionFilter("Haven resource (*.res)", "res"));
        if(fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            openFile(fc.getSelectedFile().toPath());
    }

    private void doSaveAs() {
        if(res == null)
            return;
        JFileChooser fc = new JFileChooser(dir());
        fc.setFileFilter(new FileNameExtensionFilter("Haven resource (*.res)", "res"));
        if(file != null)
            fc.setSelectedFile(file.toFile());
        else if(suggestedName != null)
            fc.setSelectedFile(new java.io.File(suggestedName));
        if(fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path p = ensureResExtension(fc.getSelectedFile().toPath());
            writeRes(p);
            this.file = p;
            updateTitle();
            updatePath();
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

    private void writeRes(Path p) {
        try {
            SafeFiles.write(p, res.serialize());
            dirty = false;
            updateTitle();
            setStatus("Saved " + p.getFileName());
        } catch(Exception e) {
            error("Could not save: " + e.getMessage());
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
        JFileChooser fc = new JFileChooser(dir());
        fc.setDialogTitle("Rebuild from glTF");
        fc.setFileFilter(new FileNameExtensionFilter("Binary glTF (*.glb)", "glb"));
        if(fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        final java.io.File sel = fc.getSelectedFile();
        final byte[] orig = res.serialize();
        final Path curFile = file;
        final String curPath = pathField.getText();
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
                if(rr == null) {
                    error("glTF rebuild failed: " + error);
                    return;
                }
                try {
                    applyLoaded(ResContainer.parse(rr.res), curFile, curPath,
                            "Rebuilt " + rr.vertices + " vertices, " + rr.triangles + " triangles"
                                    + (rr.skinned ? " (with skinning)" : "") + (rr.skel ? " (skeleton re-posed)" : "")
                                    + " from " + sel.getName() + " \u2014 Save to keep changes");
                    markDirty();
                } catch(Exception e) {
                    error("glTF rebuild failed: " + e.getMessage());
                }
            });
        }, "gltf-rebuild");
        t.setDaemon(true);
        t.start();
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
                JFileChooser fc = new JFileChooser(dir());
                fc.setFileFilter(new FileNameExtensionFilter("Binary glTF (*.glb)", "glb"));
                fc.setSelectedFile(new File(baseName() + ".glb"));
                if(fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    try {
                        SafeFiles.write(fc.getSelectedFile().toPath(), rr.glb);
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
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(520, 420));

        JButton copy = new JButton(action("Copy", () ->
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(report), null)));
        JButton save = new JButton(action("Save", () -> {
            JFileChooser fc = new JFileChooser(dir());
            fc.setFileFilter(new FileNameExtensionFilter("Text (*.txt)", "txt"));
            fc.setSelectedFile(new File(baseName() + "-references.txt"));
            if(fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    Files.writeString(fc.getSelectedFile().toPath(), report);
                    setStatus("Saved reference list \u2192 " + fc.getSelectedFile().getName());
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
            case "material":
            case "hitbox":
            case "collision":
            case "equip point":
            case "light":
                buildJsonPanel(content, idx, l);
                break;
            case "animation":
                buildAnimPanel(content, idx, l);
                break;
            case "sound":
            case "font":
            case "music":
                buildMediaPanel(content, idx, l);
                break;
            case "3D model":
                buildModelPanel(content, l);
                break;
            case "code":
                buildCodePanel(content, idx, l);
                break;
            case "dependencies":
            case "links":
            case "source":
                buildReferencePanel(content, idx, l);
                break;
            case "skeleton":
            case "skeletal anim":
            case "mesh anim":
                buildRigPanel(content, idx, l);
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
                new JButton(action("Replace image", () -> replaceFromFile(idx, l.name))),
                new JButton(action("Export image", () -> exportLayer(idx)))));
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
        row2.add(new JButton(action("Apply header", () -> {
            try {
                byte[] payload = h.encodeWith(
                        (Integer) idSp.getValue(), (Integer) oxSp.getValue(), (Integer) oySp.getValue(),
                        (Integer) sxSp.getValue(), (Integer) sySp.getValue());
                setLayerPayload(idx, payload);
                setStatus("Updated tex header in layer " + idx);
            } catch(IllegalArgumentException ex) {
                error(ex.getMessage());
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
        row2.add(new JButton(action("Apply header", () -> {
            try {
                byte[] payload = h.encodeWith(
                        (Integer) zSp.getValue(), (Integer) subzSp.getValue(),
                        (Integer) idSp.getValue(), (Integer) oxSp.getValue(),
                        (Integer) oySp.getValue(), nooffBox.isSelected());
                setLayerPayload(idx, payload);
                setStatus("Updated image header in layer " + idx);
            } catch(IllegalArgumentException ex) {
                error(ex.getMessage());
            }
        })));

        content.add(row1);
        content.add(row2);
    }

    private static JPanel headerRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
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
        commit(before);
        markDirty();
        int sel = table.getSelectedRow();
        model.fireTableRowsUpdated(idx, idx);
        if(sel == idx)
            showSelected();
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
        })), new JButton(action("Export", () -> exportLayer(idx)))));
    }

    private void buildJsonPanel(JPanel content, int idx, Layer l) {
        addJsonEditor(content, idx, l, 320);
    }

    private void addJsonEditor(JPanel content, int idx, Layer l, int height) {
        String json = GuiSupport.editableJson(l);
        if(json == null) {
            content.add(labeled("This " + l.name + " layer uses types this editor can't safely"
                    + " present as JSON; it is preserved as-is."));
            content.add(Box.createVerticalStrut(8));
            content.add(buttonRow(new JButton(action("Export raw", () -> exportLayer(idx)))));
            return;
        }
        JTextArea area = new JTextArea(json);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(420, height));
        content.add(sp);
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(action("Apply JSON", () -> {
            applyBytes(idx, area.getText().getBytes(StandardCharsets.UTF_8));
            setStatus("Updated " + l.name + " in layer " + idx);
        })), new JButton(action("Export JSON", () -> exportLayer(idx)))));
    }

    /** anim layers: a live frame preview (resolving frame image-ids to sibling image layers) + the JSON editor. */
    private void buildAnimPanel(JPanel content, int idx, Layer l) {
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
        java.util.List<?> ids = (java.util.List<?>) m.get("frames");
        java.util.List<AnimView.Frame> frames = new java.util.ArrayList<>();
        for(Object o : ids) {
            AnimView.Frame fr = frameById(((Number) o).intValue());
            if(fr != null)
                frames.add(fr);
        }
        if(frames.isEmpty()) {
            content.add(labeled("(no matching image frames in this resource to preview)"));
            content.add(Box.createVerticalStrut(8));
            return;
        }
        content.add(labeled("Preview \u2014 " + frames.size() + " frames @ " + delay + "ms"
                + " (true relative size & offset)"));
        AnimView view = new AnimView();
        Dimension d = new Dimension(220, 180);
        view.setPreferredSize(d);
        view.setMaximumSize(d);
        view.setAlignmentX(Component.LEFT_ALIGNMENT);
        view.setFrames(frames);
        content.add(view);
        content.add(Box.createVerticalStrut(8));
        int[] fi = {0};
        animTimer = new javax.swing.Timer(Math.max(20, delay), ev -> {
            fi[0] = (fi[0] + 1) % frames.size();
            view.setCurrent(fi[0]);
        });
        animTimer.setInitialDelay(Math.max(20, delay));
        animTimer.start();
    }

    /** Resolves an animation frame id to its image + draw offset (first matching image layer), else null. */
    private AnimView.Frame frameById(int id) {
        if(res == null)
            return null;
        for(Layer ly : res.layers) {
            if(!ly.name.equals("image"))
                continue;
            try {
                resforge.layers.ImageInfo ii = resforge.layers.ImageInfo.parse(ly.data);
                if(ii.recognized && ii.id == id) {
                    java.awt.image.BufferedImage bi = GuiSupport.preview(ly);
                    if(bi != null) {
                        int ox = ii.nooff ? 0 : ii.offX;
                        int oy = ii.nooff ? 0 : ii.offY;
                        return new AnimView.Frame(bi, ox, oy);
                    }
                }
            } catch(RuntimeException ignored) {
            }
        }
        return null;
    }

    private void buildMediaPanel(JPanel content, int idx, Layer l) {
        if(l.name.equals("audio2")) {
            byte[] ogg = GuiSupport.audioBytes(l);
            if(ogg != null) {
                currentPlayer = new AudioPlayerPanel(ogg);
                content.add(currentPlayer);
                content.add(Box.createVerticalStrut(8));
            }
            addAudioHeaderEditor(content, idx, l);
        }
        String media = GuiSupport.mediaMeta(l);
        content.add(labeled(media != null ? media : GuiSupport.summary(l)));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(
                new JButton(action("Replace", () -> replaceFromFile(idx, l.name))),
                new JButton(action("Export", () -> exportLayer(idx)))));
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
        row.add(new JButton(action("Apply", () -> {
            try {
                byte[] payload = (volSp != null)
                        ? h.encodeWithBvol(idField.getText(), (Double) volSp.getValue())
                        : h.encodeWith(idField.getText(), 0);
                setLayerPayload(idx, payload);
                setStatus("Updated audio header in layer " + idx);
            } catch(IllegalArgumentException ex) {
                error(ex.getMessage());
            }
        })));
        content.add(row);
        content.add(Box.createVerticalStrut(8));
    }

    private void buildModelPanel(JPanel content, Layer l) {
        String detail = GuiSupport.modelDetail(l);
        if(detail != null) {
            JTextArea area = new JTextArea(detail);
            area.setEditable(false);
            area.setOpaque(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
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
                new JButton(action("Export glTF (.glb)", this::doExportGltf)),
                new JButton(action("Rebuild from glTF", this::doRebuildGltf))));
    }

    private void buildCodePanel(JPanel content, int idx, Layer l) {
        JTextArea area = new JTextArea(GuiSupport.codeText(l));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(420, 320));
        content.add(sp);
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(action("Export", () -> exportLayer(idx)))));
    }

    private void buildReferencePanel(JPanel content, int idx, Layer l) {
        JTextArea area = new JTextArea(GuiSupport.referenceText(l));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(420, 320));
        content.add(sp);
        content.add(Box.createVerticalStrut(8));
        content.add(labeled("Read-only: this resource references the items above; preserved exactly on save."));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(action("Export", () -> exportLayer(idx)))));
    }

    private void buildRawPanel(JPanel content, int idx, Layer l) {
        content.add(labeled("Raw layer, preserved exactly on save."));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(
                new JButton(action("Replace raw", () -> replaceFromFile(idx, l.name))),
                new JButton(action("Export raw", () -> exportLayer(idx)))));
    }

    private void buildRigPanel(JPanel content, int idx, Layer l) {
        JTextArea area = new JTextArea(GuiSupport.rigText(l));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(area);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(420, 320));
        content.add(sp);
        content.add(Box.createVerticalStrut(8));
        content.add(labeled("Read-only structural view; the layer is preserved exactly on save."));
        content.add(Box.createVerticalStrut(8));
        content.add(buttonRow(new JButton(action("Export raw", () -> exportLayer(idx)))));
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
            SafeFiles.write(fc.getSelectedFile().toPath(), ex.data);
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
        setTitle("ResForge \u2014 " + n + (dirty ? " *" : ""));
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
        JOptionPane.showMessageDialog(this, msg, "ResForge", JOptionPane.INFORMATION_MESSAGE);
    }

    private void doAbout() {
        info("ResForge\n\nA tool to view and edit Haven & Hearth .res files.\n"
                + "Swap icons, textures, sounds and fonts; edit text and properties;\n"
                + "edit 3D models through Blender via glTF. Unchanged layers are preserved byte-for-byte.");
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
            ResForgeFrame f = new ResForgeFrame();
            f.setVisible(true);
            if(initial != null)
                f.openFile(initial);
        });
    }
}
