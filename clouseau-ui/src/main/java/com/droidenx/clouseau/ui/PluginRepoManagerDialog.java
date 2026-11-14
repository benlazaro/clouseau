package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for managing the list of configured plugin repositories.
 * Supports adding, editing, and deleting Nexus 3 repositories.
 * Maven Central is always available and cannot be removed.
 * <p>
 * A manager like the others: the list's actions on the footer's left, Delete removes the
 * selected repository after asking, Close is plain, and the size is remembered.
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
        ManagerDialogs.rememberSize(this, "plugin.repos", new Dimension(560, 340), new Dimension(460, 260));
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        JPanel content = new JPanel(new MigLayout("fill, insets 16, wrap 1, gapy 8", "[grow, fill]", "[][grow, fill]"));
        content.add(ManagerDialogs.sectionTitle(Messages.get("plugins.repos.list.label")), "growx");

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
        ManagerDialogs.onDeleteKey(repoList, this::deleteSelected);

        addBtn.addActionListener(e -> {
            AppPrefs.PluginRepo r = showEditDialog(null, -1);
            if (r == null) return;
            repos.add(r);
            save();
            repoList.setSelectedIndex(repos.size() - 1);
        });
        editBtn.addActionListener(e -> editSelected());
        deleteBtn.addActionListener(e -> deleteSelected());

        JButton closeBtn = new JButton(Messages.get("plugins.repos.close"));
        closeBtn.addActionListener(e -> dispose());

        // The list's actions on the left, Close on the right, as in the Plugin Manager.
        JPanel root = new JPanel(new java.awt.BorderLayout());
        root.add(content, java.awt.BorderLayout.CENTER);
        root.add(DialogFooter.of(new JComponent[]{ addBtn, editBtn, deleteBtn }, closeBtn), java.awt.BorderLayout.SOUTH);
        setContentPane(root);
        // No default button: the actions apply at once, so there is nothing to confirm, and
        // Close shouldn't be the amber one. Escape closes.
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void editSelected() {
        int idx = repoList.getSelectedIndex();
        if (idx < 0) return;
        AppPrefs.PluginRepo r = showEditDialog(repos.get(idx), idx);
        if (r == null) return;
        repos.set(idx, r);
        save();
    }

    private void deleteSelected() {
        int idx = repoList.getSelectedIndex();
        if (idx < 0) return;
        String name = repos.get(idx).name();
        if (!ManagerDialogs.confirmDelete(this, Messages.get("plugins.repos.delete.title"),
                Messages.format("plugins.repos.delete.message", name))) return;
        repos.remove(idx);
        save();
        if (!repos.isEmpty()) repoList.setSelectedIndex(Math.min(idx, repos.size() - 1));
        repoList.requestFocusInWindow();
    }

    private void save() {
        AppPrefs.savePluginRepos(repos);
        rebuildListModel();
        onChange.run();
    }

    private void rebuildListModel() {
        int sel = repoList != null ? repoList.getSelectedIndex() : -1;
        listModel.clear();
        repos.forEach(r -> listModel.addElement(r.name() + "  —  " + r.url()));
        if (sel >= 0 && sel < listModel.size()) repoList.setSelectedIndex(sel);
    }

    /** Whether a repository other than the one at {@code self} is already called {@code name}, ignoring case. */
    private boolean nameTaken(String name, int self) {
        for (int i = 0; i < repos.size(); i++) {
            if (i != self && repos.get(i).name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** A URL that's still only the scheme the field starts with counts as empty. */
    static boolean urlMissing(String url) {
        String u = url.trim();
        return u.isEmpty() || u.equalsIgnoreCase("https://") || u.equalsIgnoreCase("http://");
    }

    // ── Edit / Add dialog ─────────────────────────────────────────────────────

    // Display name (a message key) → internal type id
    private static final String[][] REPO_TYPES = {
        { "plugins.repos.type.nexus3", "nexus3" },
    };

    // Auth type: display name (a message key) → internal id
    private static final String[][] AUTH_TYPES = {
        { "plugins.repos.auth.none",      "none"      },
        { "plugins.repos.auth.basic",     "basic"     },
        { "plugins.repos.auth.usertoken", "usertoken" },
    };

    /** The options by their messages, {@code selectedId}'s selected; the index maps back to the id. */
    private static JComboBox<String> combo(String[][] options, String selectedId) {
        JComboBox<String> c = new JComboBox<>(java.util.Arrays.stream(options)
                .map(t -> Messages.get(t[0])).toArray(String[]::new));
        for (int i = 0; i < options.length; i++) {
            if (options[i][1].equals(selectedId)) { c.setSelectedIndex(i); break; }
        }
        return c;
    }

    private static String selectedId(JComboBox<String> c, String[][] options, String fallback) {
        int i = c.getSelectedIndex();
        return i >= 0 && i < options.length ? options[i][1] : fallback;
    }

    /** Edits {@code existing} (at {@code index} in the list), or a new repository when null. */
    private AppPrefs.PluginRepo showEditDialog(AppPrefs.PluginRepo existing, int index) {
        JTextField nameField = new JTextField(existing != null ? existing.name() : "", 22);
        JTextField urlField  = new JTextField(existing != null ? existing.url()  : "https://", 22);
        JTextField repoField = new JTextField(
                existing != null && existing.repository() != null ? existing.repository() : "", 22);

        JComboBox<String> typeCombo = combo(REPO_TYPES, existing != null ? existing.type() : "nexus3");
        JComboBox<String> authCombo = combo(AUTH_TYPES, existing != null ? existing.authType() : "none");

        JTextField     usernameField = new JTextField(
                existing != null && existing.username() != null ? existing.username() : "", 16);
        JPasswordField credField     = new JPasswordField(
                existing != null && existing.credential() != null ? existing.credential() : "", 16);

        JLabel usernameLabel = new JLabel(Messages.get("plugins.repos.edit.username"));
        JLabel credLabel     = new JLabel(Messages.get("plugins.repos.edit.credential"));

        Runnable syncAuth = () -> {
            String authId = selectedId(authCombo, AUTH_TYPES, "none");
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

        // Labels left-aligned and indented under their section titles, as in Settings.
        String indent = SettingsDialog.INDENT_NARROW;
        JPanel form = new JPanel(new MigLayout(
                "wrap 2, gapy 6, gapx 12, hidemode 3, insets 16 20 12 20", "[left][grow, fill, 260]"));
        form.add(ManagerDialogs.sectionTitle(Messages.get("plugins.repos.edit.section.repository")),
                "span 2, growx, gapbottom 4");
        form.add(new JLabel(Messages.get("plugins.repos.edit.name")), indent);
        form.add(nameField);
        form.add(new JLabel(Messages.get("plugins.repos.edit.type")), indent);
        form.add(typeCombo);
        form.add(new JLabel(Messages.get("plugins.repos.edit.url")), indent);
        form.add(urlField);
        form.add(new JLabel(Messages.get("plugins.repos.edit.repository")), indent);
        form.add(repoField);
        form.add(ManagerDialogs.sectionTitle(Messages.get("plugins.repos.edit.section.signin")),
                "span 2, growx, gaptop 14, gapbottom 4");
        form.add(new JLabel(Messages.get("plugins.repos.edit.auth")), indent);
        form.add(authCombo);
        form.add(usernameLabel, indent);
        form.add(usernameField);
        form.add(credLabel, indent);
        form.add(credField);

        // What OK is waiting for, or why the name is refused. Always present, so the dialog
        // doesn't change size as the fields fill in.
        NoticeLabel notice = new NoticeLabel();
        form.add(notice, "span 2, gaptop 10, growx 0");

        boolean[] confirmed = { false };
        JButton okBtn     = new JButton(Messages.get("settings.button.ok"));
        JButton cancelBtn = new JButton(Messages.get("settings.button.cancel"));

        Runnable validate = () -> {
            String name = nameField.getText().trim();
            List<String> missing = new ArrayList<>();
            if (name.isEmpty()) missing.add(Messages.get("plugins.repos.edit.missing.name"));
            if (urlMissing(urlField.getText())) missing.add(Messages.get("plugins.repos.edit.missing.url"));
            boolean taken = !name.isEmpty() && nameTaken(name, index);
            nameField.putClientProperty("JComponent.outline", taken ? "error" : null);
            if (taken) {
                notice.show(Messages.format("plugins.repos.edit.clash", name), NoticeLabel.Kind.ERROR);
            } else {
                notice.setText(missing.isEmpty() ? " "
                        : Messages.format("plugins.repos.edit.missing", String.join(", ", missing)));
            }
            okBtn.setEnabled(missing.isEmpty() && !taken);
        };
        DocumentListener onEdit = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { validate.run(); }
            @Override public void removeUpdate(DocumentEvent e)  { validate.run(); }
            @Override public void changedUpdate(DocumentEvent e) { validate.run(); }
        };
        nameField.getDocument().addDocumentListener(onEdit);
        urlField.getDocument().addDocumentListener(onEdit);
        validate.run();

        JPanel content = new JPanel(new java.awt.BorderLayout());
        content.add(form, java.awt.BorderLayout.CENTER);
        content.add(DialogFooter.of(cancelBtn, okBtn), java.awt.BorderLayout.SOUTH);

        String title = Messages.get(existing == null ? "plugins.repos.add.title" : "plugins.repos.edit.title");
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
        dlg.setMinimumSize(new Dimension(440, dlg.getHeight()));
        dlg.setResizable(true);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);

        if (!confirmed[0]) return null;

        String name = nameField.getText().trim();
        String url  = urlField.getText().trim();
        if (name.isBlank() || urlMissing(url) || nameTaken(name, index)) return null;

        String type     = selectedId(typeCombo, REPO_TYPES, "nexus3");
        String authType = selectedId(authCombo, AUTH_TYPES, "none");
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
