package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for managing the list of configured plugin repositories.
 * Supports adding, editing, and deleting Nexus 3 repositories.
 * Maven Central is always available and cannot be removed.
 */
final class PluginRepoManagerDialog extends JDialog {

    private final Runnable              onChange;
    private       List<AppPrefs.PluginRepo> repos;
    private final DefaultListModel<String>  listModel = new DefaultListModel<>();
    private       JList<String>             repoList;
    private       JButton                   editBtn;
    private       JButton                   deleteBtn;

    PluginRepoManagerDialog(Window owner, Runnable onChange) {
        super(owner, Messages.get("plugins.repos.title"), ModalityType.APPLICATION_MODAL);
        this.onChange = onChange;
        this.repos    = new ArrayList<>(AppPrefs.getPluginRepos());
        buildUI();
        pack();
        setMinimumSize(new Dimension(520, 300));
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        JPanel content = new JPanel(new MigLayout("fill, insets 16", "[grow][]", "[grow]"));

        rebuildListModel();
        repoList = new JList<>(listModel);
        repoList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        repoList.setFixedCellHeight(26);
        content.add(ClouseauColors.noFocusFrame(new JScrollPane(repoList)), "grow");

        JButton addBtn = new JButton(Messages.get("plugins.repos.add"));
        editBtn   = new JButton(Messages.get("plugins.repos.edit"));
        deleteBtn = new JButton(Messages.get("plugins.repos.delete"));
        editBtn.setEnabled(false);
        deleteBtn.setEnabled(false);

        repoList.addListSelectionListener(e -> {
            boolean sel = repoList.getSelectedIndex() >= 0;
            editBtn.setEnabled(sel);
            deleteBtn.setEnabled(sel);
        });
        repoList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && repoList.getSelectedIndex() >= 0) editSelected();
            }
        });

        addBtn.addActionListener(e -> {
            AppPrefs.PluginRepo r = showEditDialog(null);
            if (r == null) return;
            repos.add(r);
            save();
        });
        editBtn.addActionListener(e -> editSelected());
        deleteBtn.addActionListener(e -> {
            int idx = repoList.getSelectedIndex();
            if (idx < 0) return;
            repos.remove(idx);
            save();
        });

        JPanel side = new JPanel(new MigLayout("insets 0, wrap 1, fillx", "[fill]"));
        side.add(addBtn);
        side.add(editBtn);
        side.add(deleteBtn);
        content.add(side, "top, wrap");

        JButton closeBtn = new JButton(Messages.get("plugins.repos.close"));
        closeBtn.addActionListener(e -> dispose());

        JPanel root = new JPanel(new java.awt.BorderLayout());
        root.add(content, java.awt.BorderLayout.CENTER);
        root.add(DialogFooter.of(closeBtn), java.awt.BorderLayout.SOUTH);
        setContentPane(root);
        // No default button: the actions apply at once, so there is nothing to confirm, and
        // Close shouldn't be the amber one. Escape closes.
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void editSelected() {
        int idx = repoList.getSelectedIndex();
        if (idx < 0) return;
        AppPrefs.PluginRepo r = showEditDialog(repos.get(idx));
        if (r == null) return;
        repos.set(idx, r);
        save();
    }

    private void save() {
        AppPrefs.savePluginRepos(repos);
        rebuildListModel();
        onChange.run();
    }

    private void rebuildListModel() {
        int sel = repoList != null ? repoList.getSelectedIndex() : -1;
        listModel.clear();
        repos.forEach(r -> listModel.addElement(r.name() + "  \u2014  " + r.url()));
        if (sel >= 0 && sel < listModel.size()) repoList.setSelectedIndex(sel);
    }

    // ── Edit / Add dialog ─────────────────────────────────────────────────────

    // Maps display name → internal type id
    private static final String[][] REPO_TYPES = {
        { "Nexus Repository Manager 3", "nexus3" },
    };

    private AppPrefs.PluginRepo showEditDialog(AppPrefs.PluginRepo existing) {
        JTextField nameField = new JTextField(existing != null ? existing.name() : "", 22);
        JTextField urlField  = new JTextField(existing != null ? existing.url()  : "https://", 22);
        JTextField repoField = new JTextField(
                existing != null && existing.repository() != null ? existing.repository() : "", 22);

        // Type combo — display names only; internal ids tracked via REPO_TYPES
        String[] typeLabels = java.util.Arrays.stream(REPO_TYPES).map(t -> t[0]).toArray(String[]::new);
        JComboBox<String> typeCombo = new JComboBox<>(typeLabels);
        if (existing != null) {
            for (String[] t : REPO_TYPES) {
                if (t[1].equals(existing.type())) { typeCombo.setSelectedItem(t[0]); break; }
            }
        }

        // Auth type: display label → internal id
        String[][] authTypes = {
            { "None",                "none"      },
            { "Username / Password", "basic"     },
            { "User Token",          "usertoken" },
        };
        String[] authLabels = java.util.Arrays.stream(authTypes).map(t -> t[0]).toArray(String[]::new);
        JComboBox<String> authCombo = new JComboBox<>(authLabels);
        if (existing != null && existing.authType() != null) {
            for (String[] t : authTypes) {
                if (t[1].equals(existing.authType())) { authCombo.setSelectedItem(t[0]); break; }
            }
        }

        JTextField     usernameField = new JTextField(
                existing != null && existing.username() != null ? existing.username() : "", 16);
        JPasswordField credField     = new JPasswordField(
                existing != null && existing.credential() != null ? existing.credential() : "", 16);

        JLabel usernameLabel = new JLabel(Messages.get("plugins.repos.edit.username"));
        JLabel credLabel     = new JLabel(Messages.get("plugins.repos.edit.credential"));

        Runnable syncAuth = () -> {
            // Resolve internal id from selected label
            String selectedLabel = (String) authCombo.getSelectedItem();
            String authId = "none";
            for (String[] t : authTypes) {
                if (t[0].equals(selectedLabel)) { authId = t[1]; break; }
            }
            boolean notNone = !"none".equals(authId);
            boolean isToken = "usertoken".equals(authId);
            usernameLabel.setVisible(notNone);
            usernameField.setVisible(notNone);
            credLabel.setVisible(notNone);
            credField.setVisible(notNone);
            usernameLabel.setText(isToken
                    ? Messages.get("plugins.repos.edit.username.usertoken")
                    : Messages.get("plugins.repos.edit.username"));
            credLabel.setText(isToken
                    ? Messages.get("plugins.repos.edit.credential.usertoken")
                    : Messages.get("plugins.repos.edit.credential"));
        };
        authCombo.addActionListener(e -> syncAuth.run());
        syncAuth.run();

        JPanel form = new JPanel(new MigLayout(
                "wrap 2, gapy 6, gapx 8, hidemode 3, insets 16", "[right][grow, fill, 260]"));
        form.add(new JLabel(Messages.get("plugins.repos.edit.name")));
        form.add(nameField);
        form.add(new JLabel(Messages.get("plugins.repos.edit.type")));
        form.add(typeCombo);
        form.add(new JLabel(Messages.get("plugins.repos.edit.url")));
        form.add(urlField);
        form.add(new JLabel(Messages.get("plugins.repos.edit.repository")));
        form.add(repoField);
        form.add(new JLabel(Messages.get("plugins.repos.edit.auth")));
        form.add(authCombo);
        form.add(usernameLabel);
        form.add(usernameField);
        form.add(credLabel);
        form.add(credField);

        // Button bar
        boolean[] confirmed = { false };
        JButton okBtn     = new JButton(Messages.get("settings.button.ok"));
        JButton cancelBtn = new JButton(Messages.get("settings.button.cancel"));

        JPanel buttons = DialogFooter.of(cancelBtn, okBtn);

        JPanel content = new JPanel(new java.awt.BorderLayout());
        content.add(form,    java.awt.BorderLayout.CENTER);
        content.add(buttons, java.awt.BorderLayout.SOUTH);

        String title = Messages.get(existing == null ? "plugins.repos.add" : "plugins.repos.edit");
        JDialog dlg = new JDialog(this, title, true);
        dlg.setContentPane(content);
        dlg.getRootPane().setDefaultButton(okBtn);
        dlg.getRootPane().registerKeyboardAction(
                e -> dlg.dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        okBtn.addActionListener(e -> { confirmed[0] = true; dlg.dispose(); });
        cancelBtn.addActionListener(e -> dlg.dispose());
        dlg.pack();
        dlg.setMinimumSize(new Dimension(420, dlg.getHeight()));
        dlg.setResizable(true);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);

        if (!confirmed[0]) return null;

        String name = nameField.getText().trim();
        String url  = urlField.getText().trim();
        if (name.isBlank() || url.isBlank()) return null;

        String selectedLabel = (String) typeCombo.getSelectedItem();
        String type = "nexus3";
        for (String[] t : REPO_TYPES) {
            if (t[0].equals(selectedLabel)) { type = t[1]; break; }
        }

        String selectedAuthLabel = (String) authCombo.getSelectedItem();
        String authType = "none";
        for (String[] t : authTypes) {
            if (t[0].equals(selectedAuthLabel)) { authType = t[1]; break; }
        }
        String username = usernameField.getText().trim();
        String cred     = new String(credField.getPassword());

        return new AppPrefs.PluginRepo(
                name, type, url,
                repoField.getText().trim().isEmpty() ? null : repoField.getText().trim(),
                authType,
                (username.isEmpty() || "none".equals(authType)) ? null : username,
                (cred.isEmpty()     || "none".equals(authType)) ? null : cred
        );
    }
}
