package resforge.gui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "Open from game cache" picker: scans the local Haven cache for the
 * resource <em>names</em> the player already has (on a background thread, so the
 * dialog appears instantly with a "scanning" state), lets the user substring-
 * filter and pick one, and returns the chosen path + base URL. The bytes are not
 * read from the cache — the caller fetches the chosen resource fresh from the
 * server, so you always get the latest version.
 */
final class CachePickerDialog {
    private CachePickerDialog() {
    }

    /** The path + base URL the user chose. */
    static final class Selection {
        final String path;
        final String base;

        Selection(String path, String base) {
            this.path = path;
            this.base = base;
        }
    }

    /**
     * Shows the modal picker over {@code parent}, scanning {@code dir} in the
     * background and reporting progress via {@code status}. Returns the chosen
     * resource, or null if cancelled or nothing valid was selected.
     */
    static Selection show(Component parent, Path dir, Consumer<String> status) {
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
                        setForeground(Theme.mutedColor());
                    boolean firstDyn = index == 0 || !resforge.net.CacheIndex.isDynamic(
                            String.valueOf(l.getModel().getElementAt(index - 1)));
                    if(firstDyn)
                        setBorder(BorderFactory.createCompoundBorder(
                                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.mutedColor()),
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
            // Bulk-replace the model in a single shot: clear() + addAll() fire one
            // ListDataEvent each, instead of one per element. Element-by-element
            // addElement() made the filter freeze on large caches (8000+ names),
            // because the JList re-validated/repainted on every single add.
            listModel.clear();
            listModel.addAll(FetchHistory.filter(all[0], filterFld.getText()));
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
        status.accept("Scanning game cache " + dir + " \u2026");
        Thread t = new Thread(() -> {
            List<String> found = null;
            boolean reusedIndex = false;
            String indexWarning = null;
            String err = null;
            try {
                resforge.net.CacheIndex.ScanResult result =
                        resforge.net.CacheIndex.scanCached(dir);
                found = result.paths;
                reusedIndex = result.reusedIndex;
                indexWarning = result.warning;
            } catch(Exception ex) {
                err = ex.getMessage();
            }
            final List<String> names = found;
            final boolean reused = reusedIndex;
            final String warning = indexWarning;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                if(names == null) {
                    border.setTitle("Cache scan failed: " + error);
                    listModel.clear();
                    status.accept("Cache scan failed");
                    scroll.revalidate();
                    scroll.repaint();
                    return;
                }
                all[0] = names;
                filterFld.setEnabled(true);
                border.setTitle(names.isEmpty()
                        ? "No resources found in " + dir
                        : names.size() + " resources in your game cache"
                                + (reused ? " (saved index)" : "")
                                + " (fetched fresh from the server)");
                refilter.run();
                scroll.revalidate();
                scroll.repaint();
                status.accept(names.size() + " resource(s) found in cache"
                        + (reused ? " (saved index)" : "")
                        + (warning == null ? "" : " \u2014 " + warning));
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
        gc.insets = UiScaling.insets(0, 0, 4, 0);
        form.add(new JLabel("Filter (type any part of a path, e.g. borka):"), gc);
        form.add(filterFld, gc);
        gc.weighty = 1;
        gc.fill = java.awt.GridBagConstraints.BOTH;
        form.add(scroll, gc);
        gc.weighty = 0;
        gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        JLabel dynHint = new JLabel("Greyed \u201cdyn/\u201d entries are dynamic, account-attached "
                + "resources (listed last; may not be fetchable).");
        dynHint.setForeground(Theme.mutedColor());
        dynHint.setFont(dynHint.getFont().deriveFont(dynHint.getFont().getSize2D() - 1f));
        form.add(dynHint, gc);
        form.add(new JLabel("Server base URL:"), gc);
        form.add(baseFld, gc);

        int ok = JOptionPane.showConfirmDialog(parent, form, "Open from game cache",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if(ok != JOptionPane.OK_OPTION)
            return null;
        if(all[0].isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "The cache is still being scanned (or holds no resources). Please try again.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        String path = list.getSelectedValue();
        String useBase = baseFld.getText().strip();
        if(path == null || !all[0].contains(path)) {
            JOptionPane.showMessageDialog(parent, "Please select a resource.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        return new Selection(path, useBase);
    }
}
