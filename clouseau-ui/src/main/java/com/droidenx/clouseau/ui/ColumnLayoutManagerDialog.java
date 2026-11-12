package com.droidenx.clouseau.ui;

import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for managing saved column layouts: set default, delete.
 */
final class ColumnLayoutManagerDialog extends JDialog {

    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> layoutList           = new JList<>(listModel);
    private final JButton setDefaultBtn;
    private final JButton deleteBtn;
    private List<AppPrefs.ColumnLayout> layouts;

    ColumnLayoutManagerDialog(Window owner) {
        super(owner, Messages.get("table.columns.layout.manage.title"), ModalityType.APPLICATION_MODAL);
        layouts = new ArrayList<>(AppPrefs.getColumnLayouts());

        layoutList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        layoutList.setFixedCellHeight(24);
        rebuildList();

        setDefaultBtn = new JButton(Messages.get("table.columns.layout.set.default"));
        deleteBtn     = new JButton(Messages.get("table.columns.layout.delete"));
        JButton closeBtn = new JButton(Messages.get("table.columns.layout.close"));

        setDefaultBtn.setEnabled(false);
        deleteBtn.setEnabled(false);

        layoutList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            boolean sel = layoutList.getSelectedIndex() >= 0;
            setDefaultBtn.setEnabled(sel);
            deleteBtn.setEnabled(sel);
            refreshSetDefaultLabel();
        });

        setDefaultBtn.addActionListener(e -> toggleDefault());
        deleteBtn.addActionListener(e -> deleteSelected());
        closeBtn.addActionListener(e -> dispose());

        JPanel content = new JPanel(new MigLayout("insets 16, wrap 1, gapy 8", "[340px, fill]"));

        content.add(new JLabel(Messages.get("table.columns.layout.manage.list.label")));

        JScrollPane scroll = new JScrollPane(layoutList);
        scroll.setPreferredSize(new Dimension(340, 160));
        content.add(scroll, "grow, h 160!");

        // The list's actions on the left, Close on the right, as in the Plugin Manager.
        JPanel root = new JPanel(new java.awt.BorderLayout());
        root.add(content, java.awt.BorderLayout.CENTER);
        root.add(DialogFooter.of(new JComponent[]{ setDefaultBtn, deleteBtn }, closeBtn), java.awt.BorderLayout.SOUTH);
        setContentPane(root);
        // No default button: the actions apply at once, so there is nothing to confirm, and
        // Close shouldn't be the amber one. Escape closes.
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void rebuildList() {
        int sel = layoutList.getSelectedIndex();
        String defaultName = AppPrefs.getDefaultColumnLayout();
        listModel.clear();
        for (AppPrefs.ColumnLayout layout : layouts) {
            String prefix = layout.name().equals(defaultName) ? "\u2605  " : "    ";
            listModel.addElement(prefix + layout.name());
        }
        if (sel >= 0 && sel < layouts.size()) layoutList.setSelectedIndex(sel);
    }

    private void refreshSetDefaultLabel() {
        int idx = layoutList.getSelectedIndex();
        if (idx < 0) {
            setDefaultBtn.setText(Messages.get("table.columns.layout.set.default"));
            return;
        }
        String defaultName = AppPrefs.getDefaultColumnLayout();
        boolean isDefault  = layouts.get(idx).name().equals(defaultName);
        setDefaultBtn.setText(isDefault
                ? Messages.get("table.columns.layout.clear.default")
                : Messages.get("table.columns.layout.set.default"));
    }

    private void toggleDefault() {
        int idx = layoutList.getSelectedIndex();
        if (idx < 0) return;
        String name        = layouts.get(idx).name();
        String defaultName = AppPrefs.getDefaultColumnLayout();
        AppPrefs.setDefaultColumnLayout(name.equals(defaultName) ? null : name);
        rebuildList();
        refreshSetDefaultLabel();
    }

    private void deleteSelected() {
        int idx = layoutList.getSelectedIndex();
        if (idx < 0) return;
        String name = layouts.get(idx).name();
        if (name.equals(AppPrefs.getDefaultColumnLayout())) AppPrefs.setDefaultColumnLayout(null);
        layouts.remove(idx);
        AppPrefs.saveColumnLayouts(layouts);
        rebuildList();
        int newSel = layouts.isEmpty() ? -1 : Math.min(idx, layouts.size() - 1);
        if (newSel >= 0) layoutList.setSelectedIndex(newSel);
        setDefaultBtn.setEnabled(newSel >= 0);
        deleteBtn.setEnabled(newSel >= 0);
        refreshSetDefaultLabel();
    }
}
