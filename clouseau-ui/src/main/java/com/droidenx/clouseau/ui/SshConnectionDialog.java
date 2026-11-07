package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.SshConfig;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Modal dialog for configuring an SSH log-streaming connection.
 * Call {@link #showDialog()} to display it; returns the result if the user confirmed.
 */
public class SshConnectionDialog extends JDialog {

    public record Result(SshConfig config, Optional<LogParser> parser) {}

    // Connection fields
    private final JTextField hostField       = new JTextField(20);
    private final JSpinner   portSpinner     = new JSpinner(new SpinnerNumberModel(22, 1, 65535, 1));
    private final JTextField userField       = new JTextField(20);
    private final JTextField remotePathField = new JTextField(20);

    // Auth panels
    private final JRadioButton passwordRadio = new JRadioButton(Messages.get("ssh.dialog.auth.password"), true);
    private final JRadioButton keyRadio      = new JRadioButton(Messages.get("ssh.dialog.auth.key"));
    private final JRadioButton noneRadio     = new JRadioButton(Messages.get("ssh.dialog.auth.none"));

    // Password auth
    private final JLabel         passwordLabel     = new JLabel(Messages.get("ssh.dialog.password"));
    private final JPasswordField passwordField     = new JPasswordField(20);

    // Key auth
    private final JLabel         keyFileLabel      = new JLabel(Messages.get("ssh.dialog.key.file"));
    private final JTextField     keyFileField      = new JTextField(16);
    private final JLabel         keyPassLabel       = new JLabel(Messages.get("ssh.dialog.key.passphrase"));
    private final JPasswordField keyPassphraseField = new JPasswordField(16);
    private final JCheckBox      noPassphraseBox    = new JCheckBox(Messages.get("ssh.dialog.key.no.passphrase"));
    private       JPanel         keyFileRow;         // built in buildContent()
    private       JPanel         keyPassRow;         // built in buildContent()

    // Parser
    private final JComboBox<String> parserCombo;
    private final List<LogParser>   parsers;

    // Favorites
    private final DefaultComboBoxModel<String> favoritesModel = new DefaultComboBoxModel<>();
    private final JComboBox<String>            favoritesCombo = new JComboBox<>(favoritesModel);
    private       List<AppPrefs.SshFavorite>   favorites;

    // Action
    private final JButton connectBtn = new JButton(Messages.get("ssh.dialog.connect"));
    private Result result;

    public SshConnectionDialog(Frame owner, List<LogParser> parsers) {
        super(owner, Messages.get("ssh.dialog.title"), true);
        this.parsers = parsers;

        String[] parserNames = new String[parsers.size() + 1];
        parserNames[0] = Messages.get("filechooser.parser.autodetect");
        for (int i = 0; i < parsers.size(); i++) parserNames[i + 1] = parsers.get(i).getName();
        parserCombo = new JComboBox<>(parserNames);

        favorites = new ArrayList<>(AppPrefs.getSshFavorites());
        rebuildFavoritesCombo();

        setContentPane(buildContent());
        getRootPane().setDefaultButton(connectBtn);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
        validateForm();
    }

    public Optional<Result> showDialog() {
        setVisible(true);
        return Optional.ofNullable(result);
    }

    /**
     * Fills the form with a restored tab's connection (UC-17, D-19), so Reconnect only asks
     * for the password or passphrase a session never keeps.
     */
    void prefill(com.droidenx.clouseau.core.Session.Ssh ssh, String parserName) {
        loadFavorite(new AppPrefs.SshFavorite(null, ssh.host(), ssh.port(), ssh.user(), ssh.auth(),
                null, ssh.keyFile(), null, ssh.remotePath(), parserName));
        if ("key".equals(ssh.auth()) && ssh.keyPassphrase()) {
            noPassphraseBox.setSelected(false);
            keyPassphraseField.setEnabled(true);
        }
        validateForm();
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private JPanel buildContent() {
        JPanel panel = new JPanel(new MigLayout(
                "insets 20, wrap 2, gapy 8, gapx 10",
                "[right][grow, fill]"));

        // Favorites row
        panel.add(buildFavoritesRow(), "span 2, growx, gapbottom 4");
        panel.add(new JSeparator(), "span 2, growx, gapbottom 4");

        // Host + port on the same row
        panel.add(new JLabel(Messages.get("ssh.dialog.host")));
        JPanel hostRow = new JPanel(new MigLayout("insets 0, gapy 0, gapx 6", "[grow, fill][][]"));
        hostRow.add(hostField, "growx");
        hostRow.add(new JLabel(Messages.get("ssh.dialog.port")));
        JSpinner.NumberEditor portEditor = new JSpinner.NumberEditor(portSpinner, "#");
        portSpinner.setEditor(portEditor);
        portEditor.getTextField().setColumns(5);
        hostRow.add(portSpinner);
        panel.add(hostRow);

        panel.add(new JLabel(Messages.get("ssh.dialog.user")));
        panel.add(userField);

        // Auth type
        ButtonGroup authGroup = new ButtonGroup();
        authGroup.add(passwordRadio);
        authGroup.add(keyRadio);
        authGroup.add(noneRadio);
        JPanel authTypeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        authTypeRow.add(passwordRadio);
        authTypeRow.add(Box.createHorizontalStrut(16));
        authTypeRow.add(keyRadio);
        authTypeRow.add(Box.createHorizontalStrut(16));
        authTypeRow.add(noneRadio);
        panel.add(new JLabel());
        panel.add(authTypeRow);

        // Password field — flat in outer panel so "Password:" aligns with other labels
        panel.add(passwordLabel,  "hidemode 3");
        panel.add(passwordField,  "hidemode 3");

        // Key fields — also flat; shown/hidden as a group when switching auth type
        keyFileRow = new JPanel(new MigLayout("insets 0, gapy 0, gapx 4", "[grow, fill][]"));
        keyFileRow.add(keyFileField, "growx");
        JButton browseBtn = new JButton(Messages.get("ssh.dialog.key.browse"));
        browseBtn.addActionListener(e -> browseKeyFile());
        keyFileRow.add(browseBtn);

        panel.add(keyFileLabel,       "hidemode 3");
        panel.add(keyFileRow,         "hidemode 3");
        keyPassRow = new JPanel(new MigLayout("insets 0, gapy 0, gapx 6", "[grow, fill][]"));
        keyPassRow.add(keyPassphraseField, "growx");
        keyPassRow.add(noPassphraseBox);
        noPassphraseBox.addActionListener(e -> {
            keyPassphraseField.setEnabled(!noPassphraseBox.isSelected());
            if (noPassphraseBox.isSelected()) keyPassphraseField.setText("");
        });

        panel.add(keyPassLabel,  "hidemode 3");
        panel.add(keyPassRow,    "hidemode 3");

        // Default to password auth; wire radio buttons
        switchAuth("password");
        passwordRadio.addActionListener(e -> switchAuth("password"));
        keyRadio.addActionListener(e ->      switchAuth("key"));
        noneRadio.addActionListener(e ->     switchAuth("none"));

        panel.add(new JLabel(Messages.get("ssh.dialog.remote.path")));
        panel.add(remotePathField);

        panel.add(new JLabel(Messages.get("ssh.dialog.parser")));
        panel.add(parserCombo);

        // Separator + buttons
        panel.add(new JSeparator(), "span 2, growx, gaptop 8, gapbottom 4");

        connectBtn.addActionListener(e -> onConnect());
        JButton cancelBtn = new JButton(Messages.get("ssh.dialog.cancel"));
        cancelBtn.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(connectBtn);
        buttons.add(cancelBtn);
        panel.add(buttons, "span 2, growx");

        // Live validation
        DocumentListener validator = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { validateForm(); }
            public void removeUpdate(DocumentEvent e)  { validateForm(); }
            public void changedUpdate(DocumentEvent e) { validateForm(); }
        };
        hostField.getDocument().addDocumentListener(validator);
        userField.getDocument().addDocumentListener(validator);
        remotePathField.getDocument().addDocumentListener(validator);

        return panel;
    }

    private void switchAuth(String authType) {
        boolean usePw  = "password".equals(authType);
        boolean useKey = "key".equals(authType);
        passwordLabel.setVisible(usePw);
        passwordField.setVisible(usePw);
        keyFileLabel.setVisible(useKey);
        keyFileRow.setVisible(useKey);
        keyPassLabel.setVisible(useKey);
        keyPassRow.setVisible(useKey);
        pack();
    }

    private void validateForm() {
        boolean ok = !hostField.getText().isBlank()
                  && !userField.getText().isBlank()
                  && !remotePathField.getText().isBlank();
        connectBtn.setEnabled(ok);
    }

    // ── Favorites ─────────────────────────────────────────────────────────────

    private JPanel buildFavoritesRow() {
        JButton saveBtn = new JButton("\u2605");
        saveBtn.setToolTipText(Messages.get("ssh.dialog.favorites.save.tooltip"));
        saveBtn.setMargin(new Insets(2, 8, 2, 8));

        JButton deleteBtn = new JButton("\u2212");
        deleteBtn.setToolTipText(Messages.get("ssh.dialog.favorites.delete.tooltip"));
        deleteBtn.setMargin(new Insets(2, 8, 2, 8));
        deleteBtn.setEnabled(false);

        favoritesCombo.addActionListener(e -> {
            int idx = favoritesCombo.getSelectedIndex();
            deleteBtn.setEnabled(idx > 0);
            if (idx > 0) loadFavorite(favorites.get(idx - 1));
        });

        saveBtn.addActionListener(e -> saveFavorite());
        deleteBtn.addActionListener(e -> {
            int idx = favoritesCombo.getSelectedIndex();
            if (idx < 1) return;
            favorites.remove(idx - 1);
            AppPrefs.saveSshFavorites(favorites);
            rebuildFavoritesCombo();
            deleteBtn.setEnabled(false);
        });

        JPanel row = new JPanel(new MigLayout("insets 0, gapy 0, gapx 6", "[][grow, fill][][]"));
        row.add(new JLabel(Messages.get("ssh.dialog.favorites")));
        row.add(favoritesCombo, "growx");
        row.add(saveBtn);
        row.add(deleteBtn);
        return row;
    }

    private void rebuildFavoritesCombo() {
        favoritesModel.removeAllElements();
        favoritesModel.addElement(Messages.get("ssh.dialog.favorites.placeholder"));
        favorites.forEach(f -> favoritesModel.addElement(f.name()));
    }

    private void loadFavorite(AppPrefs.SshFavorite f) {
        hostField.setText(f.host());
        portSpinner.setValue(f.port());
        userField.setText(f.user());
        remotePathField.setText(f.remotePath());

        if ("key".equals(f.authType())) {
            keyRadio.setSelected(true);
            keyFileField.setText(f.keyFilePath() != null ? f.keyFilePath() : "");
            boolean noPass = f.keyPassphrase() == null || f.keyPassphrase().isEmpty();
            noPassphraseBox.setSelected(noPass);
            keyPassphraseField.setEnabled(!noPass);
            keyPassphraseField.setText(noPass ? "" : f.keyPassphrase());
        } else if ("none".equals(f.authType())) {
            noneRadio.setSelected(true);
        } else {
            passwordRadio.setSelected(true);
            passwordField.setText(f.password() != null ? f.password() : "");
        }
        switchAuth(f.authType());

        parserCombo.setSelectedIndex(0);
        if (f.parserName() != null) {
            for (int i = 0; i < parsers.size(); i++) {
                if (parsers.get(i).getName().equals(f.parserName())) {
                    parserCombo.setSelectedIndex(i + 1);
                    break;
                }
            }
        }
    }

    private void saveFavorite() {
        String host       = hostField.getText().trim();
        String user       = userField.getText().trim();
        String remotePath = remotePathField.getText().trim();
        if (host.isBlank() || user.isBlank() || remotePath.isBlank()) return;

        String name = user + "@" + host + ":" + remotePath;
        int    port = (int) portSpinner.getValue();

        String parserName = null;
        int parserIdx = parserCombo.getSelectedIndex();
        if (parserIdx > 0) parserName = parsers.get(parserIdx - 1).getName();

        AppPrefs.SshFavorite fav;
        if (keyRadio.isSelected()) {
            fav = new AppPrefs.SshFavorite(name, host, port, user,
                    "key", null,
                    keyFileField.getText().trim(),
                    new String(keyPassphraseField.getPassword()),
                    remotePath, parserName);
        } else if (noneRadio.isSelected()) {
            fav = new AppPrefs.SshFavorite(name, host, port, user,
                    "none", null, null, null, remotePath, parserName);
        } else {
            fav = new AppPrefs.SshFavorite(name, host, port, user,
                    "password", new String(passwordField.getPassword()),
                    null, null, remotePath, parserName);
        }

        favorites.removeIf(f -> f.name().equals(name));
        favorites.add(0, fav);
        AppPrefs.saveSshFavorites(favorites);
        rebuildFavoritesCombo();
        favoritesCombo.setSelectedIndex(1);
    }

    private void browseKeyFile() {
        JFileChooser fc = new JFileChooser(System.getProperty("user.home") + "/.ssh");
        fc.setDialogTitle(Messages.get("ssh.dialog.key.file"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            keyFileField.setText(f.getAbsolutePath());
        }
    }

    private void onConnect() {
        String host       = hostField.getText().trim();
        int    port       = (int) portSpinner.getValue();
        String user       = userField.getText().trim();
        String remotePath = remotePathField.getText().trim();

        SshConfig config;
        if (keyRadio.isSelected()) {
            config = new SshConfig(host, port, user,
                    null,
                    keyFileField.getText().trim(),
                    new String(keyPassphraseField.getPassword()),
                    remotePath);
        } else if (noneRadio.isSelected()) {
            config = new SshConfig(host, port, user, null, null, null, remotePath);
        } else {
            config = new SshConfig(host, port, user,
                    new String(passwordField.getPassword()),
                    null, null,
                    remotePath);
        }

        int parserIdx = parserCombo.getSelectedIndex();
        Optional<LogParser> parser = parserIdx > 0
                ? Optional.of(parsers.get(parserIdx - 1))
                : Optional.empty();

        result = new Result(config, parser);
        dispose();
    }
}
