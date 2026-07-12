package resforge.gui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.List;

/**
 * The "Fetch from server" dialog: a resource-path field, a click-to-use list of
 * recently-fetched paths (substring-filtered as you type), and the server base
 * URL. Pure UI — it reads the remembered base/history from preferences for its
 * defaults but performs no fetch itself; it just returns the user's choice (the
 * caller does the actual download and history update).
 */
final class FetchDialog {
    private FetchDialog() {
    }

    /** The path + base URL the user chose (path may be blank — the caller validates). */
    static final class Selection {
        final String path;
        final String base;

        Selection(String path, String base) {
            this.path = path;
            this.base = base;
        }
    }

    /** Shows the modal dialog over {@code parent}; returns the choice, or null if cancelled. */
    static Selection show(Component parent) {
        return show(parent, "");
    }

    /** Shows the dialog with an optional suggested resource path. */
    static Selection show(Component parent, String suggestedPath) {
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(ResForgeFrame.class);
        String base = prefs.get("resBaseUrl", resforge.net.ResourceFetcher.DEFAULT_BASE);
        List<String> history = FetchHistory.parse(prefs.get("fetchHistory", ""));

        JTextField pathFld = new JTextField(suggestedPath == null ? "" : suggestedPath, 24);
        JTextField baseFld = new JTextField(base, 24);

        // JOptionPane initially focuses its default button, so grab focus for the
        // path field once it's shown (one-shot: removes itself after firing).
        pathFld.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent e) {
                pathFld.requestFocusInWindow();
                pathFld.removeAncestorListener(this);
            }
            public void ancestorMoved(javax.swing.event.AncestorEvent e) { }
            public void ancestorRemoved(javax.swing.event.AncestorEvent e) { }
        });

        JPanel form = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1;
        gc.anchor = java.awt.GridBagConstraints.WEST;
        gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gc.insets = UiScaling.insets(0, 0, 4, 0);
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
        int ok = JOptionPane.showConfirmDialog(parent, form, "Fetch resource from server",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if(ok != JOptionPane.OK_OPTION)
            return null;
        return new Selection(pathFld.getText().strip(), baseFld.getText().strip());
    }
}
