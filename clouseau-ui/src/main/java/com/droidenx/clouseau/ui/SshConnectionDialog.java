package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.SshConfig;
import com.droidenx.clouseau.core.SshRemote;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modal dialog for configuring an SSH log-streaming connection.
 * Call {@link #showDialog()} to display it; returns the result if the user confirmed.
 *
 * <p>Laid out like the Settings window: sections (Server, Authentication, Log) with a hairline
 * title, labels on the left, each section's fields indented under its title, and Cancel before
 * the default button in a footer below a divider. Saved connections are a sidebar on the left,
 * grouped by server, then folder (the way people look for a log: which machine, where, then
 * which file), with a filter on top, and a trash can on the row under the mouse. A server's
 * name is set on its row there, and the form shows it read-only. A connection is saved by ticking
 * "Save this connection", the form's last line, which remembers whether it was ticked last time.
 */
public class SshConnectionDialog extends JDialog {

    public record Result(SshConfig config, Optional<LogParser> parser) {}

    /** Host as typed, split into its parts: {@code user@host:port}, each part but the host optional. */
    record HostSpec(String user, String host, Integer port) {}

    private static final Pattern HOST_SPEC =
            Pattern.compile("^(?:([^@\\s]+)@)?([^@:\\s]+)(?::(\\d{1,5}))?$");

    /** Key files tried, in order, to prefill Key file when Private key is chosen. */
    private static final String[] DEFAULT_KEYS = {"id_ed25519", "id_ecdsa", "id_rsa"};

    private static final String AUTH_PASSWORD = "password";
    private static final String AUTH_KEY      = "key";
    private static final String AUTH_NONE     = "none";
    private static final String[] AUTH_TYPES  = {AUTH_PASSWORD, AUTH_KEY, AUTH_NONE};

    // Server
    private final JTextField hostField       = new JTextField(22);
    private final JTextField portField       = new JTextField("22", 5);
    private final JTextField userField       = new JTextField(22);
    /**
     * The server's name, read-only: it's renamed on its row in Saved connections (right-click,
     * or F2), since a name belongs to the server, not to one saved log.
     */
    private final JLabel     nameValue       = new JLabel();
    /** Server names by host, as saved. */
    private final java.util.Map<String, String> hostNames = AppPrefs.getSshHostNames();

    // Authentication
    private final JComboBox<String> authCombo = new JComboBox<>(new String[] {
            Messages.get("ssh.dialog.auth.password"),
            Messages.get("ssh.dialog.auth.key"),
            Messages.get("ssh.dialog.auth.none")});
    private final JLabel         passwordLabel      = new JLabel(Messages.get("ssh.dialog.password"));
    private final JPasswordField passwordField      = new JPasswordField(22);
    private final JLabel         keyFileLabel       = new JLabel(Messages.get("ssh.dialog.key.file"));
    private final JTextField     keyFileField       = new JTextField(18);
    private       JPanel         keyFileRow;         // built in buildContent()
    private final JLabel         keyPassLabel       = new JLabel(Messages.get("ssh.dialog.key.passphrase"));
    private final JPasswordField keyPassphraseField = new JPasswordField(22);

    // Log
    private final JTextField        remotePathField = new JTextField(22);
    private final JComboBox<String> parserCombo;
    private final List<LogParser>   parsers;

    // Saved connections: a sidebar tree, host → folder → logs
    private final JTextField                   savedFilter = new JTextField(14);
    private final DefaultMutableTreeNode       savedRoot   = new DefaultMutableTreeNode();
    private final SavedTree                    savedTree   = new SavedTree(savedRoot);
    private final CardLayout                   savedCards  = new CardLayout();
    private final JPanel                       savedBody   = new JPanel(savedCards);
    private final JCheckBox                    saveBox     = new JCheckBox(Messages.get("ssh.dialog.save"));
    private       List<AppPrefs.SshFavorite>   favorites;
    /** Servers and folders the user folded (see {@link #foldKey}), remembered between visits. */
    private final java.util.Set<String>        collapsed   = AppPrefs.getSshCollapsed();
    /** Set while the tree is rebuilt, so the expanding it does isn't taken for the user's. */
    private boolean                            rebuilding;

    // Footer
    private final JButton connectBtn = new JButton(Messages.get("ssh.dialog.connect"));
    /** Signs in and checks the file, without opening a tab (G-14). */
    private final JButton testBtn    = new JButton(Messages.get("ssh.dialog.test"));
    /** Lists the server's folders to pick Remote path from (G-13). */
    private final JButton remoteBrowseBtn = new JButton(Messages.get("ssh.dialog.remote.browse"));
    private final NoticeLabel hintLabel = new NoticeLabel();
    /** The test running now, closed if the form changes or the dialog closes; null if none. */
    private SshRemote testRemote;
    /** Set while a change comes from the dialog itself (a test result), not the user. */
    private boolean selfChange;
    /** The saved connections | the form; its divider is remembered. */
    private JSplitPane split;
    private static final int SIDEBAR_WIDTH = 250, SIDEBAR_MIN = 160;

    /** Takes the focus when the dialog opens: Host, or the secret a reconnect still needs. */
    private JComponent initialFocus = hostField;
    private Result result;

    public SshConnectionDialog(Frame owner, List<LogParser> parsers) {
        super(owner, Messages.get("ssh.dialog.title"), true);
        this.parsers = parsers;

        String[] parserNames = new String[parsers.size() + 1];
        parserNames[0] = Messages.get("filechooser.parser.autodetect");
        for (int i = 0; i < parsers.size(); i++) parserNames[i + 1] = parsers.get(i).getName();
        parserCombo = new JComboBox<>(parserNames);

        favorites = new ArrayList<>(AppPrefs.getSshFavorites());
        saveBox.setSelected(AppPrefs.isSshSaveConnection());

        setContentPane(buildContent());
        rebuildSavedTree();
        getRootPane().setDefaultButton(connectBtn);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                initialFocus.requestFocusInWindow();
            }
        });
        switchAuth(AUTH_PASSWORD);
        pack();
        // Resizable, and as it was left last time; never smaller than the form needs.
        Dimension packed = getSize();
        setMinimumSize(new Dimension(packed.width - SIDEBAR_WIDTH + SIDEBAR_MIN, packed.height));
        int[] layout = AppPrefs.getSshDialogLayout();
        if (layout != null) {
            setSize(Math.max(layout[0], getMinimumSize().width), Math.max(layout[1], packed.height));
            split.setDividerLocation(Math.max(SIDEBAR_MIN, layout[2]));
        }
        setLocationRelativeTo(owner);
        validateForm();
    }

    @Override public void dispose() {
        cancelTest();
        if (split != null && isDisplayable()) {
            AppPrefs.setSshDialogLayout(getWidth(), getHeight(), split.getDividerLocation());
        }
        super.dispose();
    }

    public Optional<Result> showDialog() {
        setVisible(true);
        return Optional.ofNullable(result);
    }

    /**
     * Fills the form with a restored tab's connection (UC-17, D-19), so Reconnect only asks
     * for the password or passphrase a session never keeps; that field takes the focus.
     */
    void prefill(com.droidenx.clouseau.core.Session.Ssh ssh, String parserName) {
        loadFavorite(new AppPrefs.SshFavorite(null, ssh.host(), ssh.port(), ssh.user(), ssh.auth(),
                null, ssh.keyFile(), null, ssh.remotePath(), parserName));
        saveBox.setSelected(false);
        if (AUTH_PASSWORD.equals(ssh.auth())) initialFocus = passwordField;
        else if (AUTH_KEY.equals(ssh.auth()) && ssh.keyPassphrase()) initialFocus = keyPassphraseField;
        validateForm();
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private JPanel buildContent() {
        // Two columns, labels and fields, with the Settings window's padding, section spacing
        // and indent. Fields fill their column; the dialog sizes to its widest row.
        JPanel content = new JPanel(new MigLayout("insets 24 28 20 28, gapy 10, hidemode 3",
                "[]24[grow, fill, 320::]"));

        // ── Server ───────────────────────────────────────────────────────
        content.add(SettingsDialog.sectionLabel(Messages.get("ssh.dialog.section.server")),
                "span 2, growx, wrap, gapbottom 4");
        content.add(new JLabel(Messages.get("ssh.dialog.name")), SettingsDialog.INDENT);
        content.add(nameValue, "wrap");
        content.add(new JLabel(Messages.get("ssh.dialog.host")), SettingsDialog.INDENT);
        JPanel hostRow = new JPanel(new MigLayout("insets 0, gapx 8", "[grow, fill][][]"));
        hostRow.setOpaque(false);
        hostRow.add(hostField);
        hostRow.add(new JLabel(Messages.get("ssh.dialog.port")), "gapleft 8");
        hostRow.add(portField, "w 64!");
        content.add(hostRow, "wrap");
        content.add(new JLabel(Messages.get("ssh.dialog.user")), SettingsDialog.INDENT);
        content.add(userField, "wrap");

        hostField.putClientProperty("JTextField.placeholderText", Messages.get("ssh.dialog.host.placeholder"));
        portField.putClientProperty("JTextField.placeholderText", "22");
        ((AbstractDocument) portField.getDocument()).setDocumentFilter(new DigitsOnly(5));
        // "ops@db01:2222" typed or pasted in Host fills Username and Port.
        hostField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                applyHostSpec();
            }
        });
        // The name shown is the one the host in the field has.
        hostField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { showName(); }
            public void removeUpdate(DocumentEvent e)  { showName(); }
            public void changedUpdate(DocumentEvent e) { showName(); }
        });
        showName();

        // ── Authentication ───────────────────────────────────────────────
        content.add(SettingsDialog.sectionLabel(Messages.get("ssh.dialog.section.auth")),
                "span 2, growx, wrap, gaptop 20, gapbottom 4");
        content.add(new JLabel(Messages.get("ssh.dialog.auth.method")), SettingsDialog.INDENT);
        content.add(authCombo, "growx 0, wrap");
        authCombo.addActionListener(e -> switchAuth(AUTH_TYPES[authCombo.getSelectedIndex()]));

        content.add(passwordLabel, SettingsDialog.INDENT);
        content.add(passwordField, "wrap");

        keyFileRow = new JPanel(new MigLayout("insets 0, gapx 8", "[grow, fill][]"));
        keyFileRow.setOpaque(false);
        keyFileRow.add(keyFileField);
        JButton browseBtn = new JButton(Messages.get("ssh.dialog.key.browse"));
        browseBtn.addActionListener(e -> browseKeyFile());
        keyFileRow.add(browseBtn);
        content.add(keyFileLabel, SettingsDialog.INDENT);
        content.add(keyFileRow, "wrap");
        keyFileField.putClientProperty("JTextField.placeholderText", "~/.ssh/id_ed25519");

        content.add(keyPassLabel, SettingsDialog.INDENT);
        content.add(keyPassphraseField, "wrap");
        keyPassphraseField.putClientProperty("JTextField.placeholderText",
                Messages.get("ssh.dialog.key.passphrase.placeholder"));

        // ── Log ──────────────────────────────────────────────────────────
        content.add(SettingsDialog.sectionLabel(Messages.get("ssh.dialog.section.log")),
                "span 2, growx, wrap, gaptop 20, gapbottom 4");
        content.add(new JLabel(Messages.get("ssh.dialog.remote.path")), SettingsDialog.INDENT);
        // Test sits by the path it checks, not in the footer: there, beside the saved
        // connections, it read as testing whichever server was selected in the list.
        JPanel remotePathRow = new JPanel(new MigLayout("insets 0, gapx 8", "[grow, fill][][]"));
        remotePathRow.setOpaque(false);
        remotePathRow.add(remotePathField);
        remotePathRow.add(remoteBrowseBtn);
        remotePathRow.add(testBtn);
        content.add(remotePathRow, "wrap");
        remoteBrowseBtn.setToolTipText(Messages.get("ssh.dialog.remote.browse.tooltip"));
        remoteBrowseBtn.addActionListener(e -> browseRemote());
        remotePathField.putClientProperty("JTextField.placeholderText",
                Messages.get("ssh.dialog.remote.path.placeholder"));
        content.add(new JLabel(Messages.get("ssh.dialog.parser")), SettingsDialog.INDENT);
        content.add(parserCombo, "wrap");
        // Saving is part of setting up the connection: the last line of the form, under the
        // fields and just above Connect, like "Remember me" on a sign-in form.
        content.add(saveBox, "skip 1, wrap, gaptop 4");

        // What Connect is waiting for, while it's disabled. Always present, so the dialog
        // doesn't change size as the fields fill in.
        content.add(hintLabel, "span 2, gaptop 6, growx 0");

        // ── Footer: Cancel · Connect ─────────────────────────────────────
        connectBtn.addActionListener(e -> onConnect());
        JButton cancelBtn = new JButton(Messages.get("ssh.dialog.cancel"));
        cancelBtn.addActionListener(e -> dispose());
        saveBox.setToolTipText(Messages.get("ssh.dialog.save.tooltip"));

        testBtn.setToolTipText(Messages.get("ssh.dialog.test.tooltip"));
        testBtn.addActionListener(e -> onTest());
        JPanel footer = DialogFooter.of(cancelBtn, connectBtn);

        // Live validation. Any change to what a test checked also drops its result.
        DocumentListener validator = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { formChanged(); }
            public void removeUpdate(DocumentEvent e)  { formChanged(); }
            public void changedUpdate(DocumentEvent e) { formChanged(); }
        };
        hostField.getDocument().addDocumentListener(validator);
        portField.getDocument().addDocumentListener(validator);
        userField.getDocument().addDocumentListener(validator);
        remotePathField.getDocument().addDocumentListener(validator);
        passwordField.getDocument().addDocumentListener(validator);
        keyFileField.getDocument().addDocumentListener(validator);
        keyPassphraseField.getDocument().addDocumentListener(validator);
        authCombo.addActionListener(e -> formChanged());

        // Drag the divider to widen the saved connections; the form takes the rest.
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSavedSidebar(), content);
        split.setBorder(null);
        split.setResizeWeight(0);
        split.setContinuousLayout(true);
        JPanel root = new JPanel(new BorderLayout());
        root.add(split, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private void switchAuth(String authType) {
        boolean usePw  = AUTH_PASSWORD.equals(authType);
        boolean useKey = AUTH_KEY.equals(authType);
        int idx = List.of(AUTH_TYPES).indexOf(authType);
        if (idx >= 0 && authCombo.getSelectedIndex() != idx) authCombo.setSelectedIndex(idx);
        passwordLabel.setVisible(usePw);
        passwordField.setVisible(usePw);
        keyFileLabel.setVisible(useKey);
        keyFileRow.setVisible(useKey);
        keyPassLabel.setVisible(useKey);
        keyPassphraseField.setVisible(useKey);
        if (useKey && keyFileField.getText().isBlank()) defaultKeyFile().ifPresent(keyFileField::setText);
        if (isDisplayable()) {
            // Grow to fit the rows that just appeared, keeping the size the user chose.
            Dimension pref = getPreferredSize();
            setMinimumSize(new Dimension(getMinimumSize().width, pref.height));
            setSize(Math.max(getWidth(), pref.width), Math.max(getHeight(), pref.height));
        }
    }

    private void validateForm() {
        HostSpec spec = parseHostSpec(hostField.getText().trim());
        boolean hasHost = spec != null || !hostField.getText().isBlank();
        boolean hasUser = !userField.getText().isBlank() || (spec != null && spec.user() != null);
        boolean hasPath = !remotePathField.getText().isBlank();
        boolean portOk  = portField.getText().isBlank() || validPort(portField.getText());

        List<String> missing = new ArrayList<>();
        if (!hasHost) missing.add(Messages.get("ssh.dialog.missing.host"));
        if (!hasUser) missing.add(Messages.get("ssh.dialog.missing.user"));
        if (!hasPath) missing.add(Messages.get("ssh.dialog.missing.path"));
        if (!portOk)  missing.add(Messages.get("ssh.dialog.missing.port"));

        connectBtn.setEnabled(missing.isEmpty());
        testBtn.setEnabled(missing.isEmpty() && testRemote == null);
        // Browsing needs a server to sign in to, not a path.
        remoteBrowseBtn.setEnabled(hasHost && hasUser && portOk);
        hintLabel.setText(missing.isEmpty() ? " "
                : java.text.MessageFormat.format(Messages.get("ssh.dialog.missing"), String.join(", ", missing)));
        portField.putClientProperty("JComponent.outline", portOk ? null : "error");
    }

    // ── Host spec: user@host:port ─────────────────────────────────────────────

    /**
     * Splits {@code user@host:port} as typed in Host; user and port are optional. Returns null
     * when the text isn't a single host in that form (empty, spaces, a bad port, an IPv6
     * address); the text is then used as typed.
     */
    static HostSpec parseHostSpec(String text) {
        if (text == null) return null;
        Matcher m = HOST_SPEC.matcher(text.trim());
        if (!m.matches()) return null;
        Integer port = null;
        if (m.group(3) != null) {
            if (!validPort(m.group(3))) return null;
            port = Integer.parseInt(m.group(3));
        }
        return new HostSpec(m.group(1), m.group(2), port);
    }

    /** Moves the user and port typed in Host into their own fields; Host keeps just the host. */
    private void applyHostSpec() {
        HostSpec spec = parseHostSpec(hostField.getText());
        if (spec == null || (spec.user() == null && spec.port() == null)) return;
        if (spec.user() != null) userField.setText(spec.user());
        if (spec.port() != null) portField.setText(String.valueOf(spec.port()));
        hostField.setText(spec.host());
    }

    private static boolean validPort(String text) {
        try {
            int p = Integer.parseInt(text.trim());
            return p >= 1 && p <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int port() {
        return portField.getText().isBlank() ? 22 : Integer.parseInt(portField.getText().trim());
    }

    // ── Saved connections ─────────────────────────────────────────────────────

    /**
     * The sidebar: a filter field and the saved connections as a tree, server › folder › log
     * (servers A–Z by name, folders and logs A–Z), each server and folder folding. A server is
     * renamed on its row; a right-click offers Rename, Duplicate and Forget as they apply. With
     * nothing saved, it says how to save one instead.
     */
    private JComponent buildSavedSidebar() {
        savedFilter.putClientProperty("JTextField.placeholderText", Messages.get("ssh.dialog.saved.filter"));
        savedFilter.putClientProperty("JTextField.leadingIcon", new com.formdev.flatlaf.icons.FlatSearchIcon());
        savedFilter.putClientProperty("JTextField.showClearButton", true);
        savedFilter.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { rebuildSavedTree(); }
            public void removeUpdate(DocumentEvent e)  { rebuildSavedTree(); }
            public void changedUpdate(DocumentEvent e) { rebuildSavedTree(); }
        });

        savedTree.setRootVisible(false);
        savedTree.setShowsRootHandles(true);
        savedTree.setRowHeight(0);                       // rows size to their renderer: logs take two lines
        savedTree.setCellRenderer(new SavedRenderer(hostNames));
        // A server is renamed on its row (F2, or Rename in its right-click menu); a click away
        // keeps the name typed, unless another server has it.
        savedTree.setEditable(true);
        savedTree.setCellEditor(new ServerNameEditor());
        savedTree.setInvokesStopCellEditing(true);
        savedTree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
            @Override public void treeExpanded(javax.swing.event.TreeExpansionEvent e)  { folded(e.getPath(), false); }
            @Override public void treeCollapsed(javax.swing.event.TreeExpansionEvent e) { folded(e.getPath(), true); }
        });
        savedTree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        javax.swing.ToolTipManager.sharedInstance().registerComponent(savedTree);
        savedTree.registerKeyboardAction(e -> { AppPrefs.SshFavorite f = selectedFavorite(); if (f != null) duplicate(f); },
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_FOCUSED);
        savedTree.addTreeSelectionListener(e -> {
            AppPrefs.SshFavorite f = selectedFavorite();
            if (f != null) loadFavorite(f);
        });
        // Selection is the theme's standard list highlight, as in menus and the Loggers tree;
        // only the log views use the blue reading selection.
        savedTree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && selectedFavorite() != null && connectBtn.isEnabled()) onConnect();
            }
            @Override public void mousePressed(java.awt.event.MouseEvent e)  { if (e.isPopupTrigger()) showSavedMenu(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { if (e.isPopupTrigger()) showSavedMenu(e); }
        });
        savedTree.registerKeyboardAction(e -> {
                    Object n = savedTree.getLastSelectedPathComponent();
                    if (n instanceof DefaultMutableTreeNode node) forget(node);
                },
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0), JComponent.WHEN_FOCUSED);

        JScrollPane treeScroll = new JScrollPane(savedTree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        // The list sits on the sidebar itself, not on a box of its own.
        savedTree.setOpaque(false);
        treeScroll.setOpaque(false);
        treeScroll.getViewport().setOpaque(false);
        JLabel empty = new JLabel("<html>" + Messages.get("ssh.dialog.saved.empty") + "</html>");
        empty.setForeground(ClouseauColors.dimForeground());
        empty.setVerticalAlignment(SwingConstants.TOP);
        empty.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        savedBody.setOpaque(false);
        savedBody.add(treeScroll, "list");
        savedBody.add(empty, "empty");

        JPanel side = new JPanel(new MigLayout("insets 16 16 16 16, fill, gapy 10", "[grow, fill]", "[][][grow, fill]"));
        side.add(new JLabel(Messages.get("ssh.dialog.saved")), "wrap");
        side.add(savedFilter, "wrap");
        side.add(savedBody, "h 120:100%:");
        side.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 10));
        side.setMinimumSize(new Dimension(SIDEBAR_MIN, 10));
        // A hairline divides the sidebar from the form, as panels are divided elsewhere.
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Separator.foreground")));
        return side;
    }

    /** A folder of saved logs on one server: the tree's middle level. */
    private record SavedFolder(String host, String path) {
        @Override public String toString() { return folderLabel(path); }
    }

    /** A folder as its row shows it: the path, or ~ for a path relative to the home folder. */
    static String folderLabel(String path) {
        return path == null || path.isEmpty() ? "~" : path;
    }

    /** The key a folded server or folder is remembered by; null for a log. */
    private static String foldKey(Object o) {
        if (o instanceof SavedFolder f) return "f:" + f.host() + "\n" + f.path();
        return o instanceof String host ? "h:" + host : null;
    }

    /**
     * Rebuilds the tree from the saved connections that match the filter: servers, the folders
     * of their logs, then the logs. What the user folded stays folded, except while filtering,
     * when every match shows. The selected row stays selected.
     */
    private void rebuildSavedTree() {
        Object selected = savedTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode n
                ? n.getUserObject() : null;
        String q = savedFilter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        // Servers in order of what their row shows: the name if they have one, else the host.
        java.util.TreeMap<String, java.util.TreeMap<String, List<AppPrefs.SshFavorite>>> byHost = new java.util.TreeMap<>(
                java.util.Comparator.comparing((String h) -> hostNames.getOrDefault(h, h), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(String.CASE_INSENSITIVE_ORDER));
        for (AppPrefs.SshFavorite f : favorites) {
            String hay = (f.host() + " " + hostNames.getOrDefault(f.host(), "") + " " + f.remotePath()
                    + " " + f.user()).toLowerCase(java.util.Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            byHost.computeIfAbsent(f.host(), h -> new java.util.TreeMap<>(
                            String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder())))
                    .computeIfAbsent(folder(f.remotePath()), k -> new ArrayList<>()).add(f);
        }
        savedRoot.removeAllChildren();
        DefaultMutableTreeNode toSelect = null;
        List<DefaultMutableTreeNode> hosts = new ArrayList<>();
        for (var host : byHost.entrySet()) {
            DefaultMutableTreeNode hostNode = new DefaultMutableTreeNode(host.getKey());
            if (host.getKey().equals(selected)) toSelect = hostNode;
            for (var folder : host.getValue().entrySet()) {
                SavedFolder sf = new SavedFolder(host.getKey(), folder.getKey());
                DefaultMutableTreeNode folderNode = new DefaultMutableTreeNode(sf);
                if (sf.equals(selected)) toSelect = folderNode;
                folder.getValue().sort(java.util.Comparator.comparing((AppPrefs.SshFavorite f) -> fileName(f.remotePath()),
                        String.CASE_INSENSITIVE_ORDER).thenComparing(AppPrefs.SshFavorite::remotePath));
                for (AppPrefs.SshFavorite f : folder.getValue()) {
                    DefaultMutableTreeNode n = new DefaultMutableTreeNode(f);
                    folderNode.add(n);
                    if (selected instanceof AppPrefs.SshFavorite s && f.name().equals(s.name())) toSelect = n;
                }
                hostNode.add(folderNode);
            }
            savedRoot.add(hostNode);
            hosts.add(hostNode);
        }
        rebuilding = true;
        try {
            ((javax.swing.tree.DefaultTreeModel) savedTree.getModel()).reload();
            boolean filtering = !q.isEmpty();
            for (DefaultMutableTreeNode hostNode : hosts) {
                // Folders first: expanding one opens its server too, and the server folds after,
                // the tree keeping its folders' state for when it opens again.
                for (int i = 0; i < hostNode.getChildCount(); i++) {
                    DefaultMutableTreeNode folderNode = (DefaultMutableTreeNode) hostNode.getChildAt(i);
                    if (filtering || !collapsed.contains(foldKey(folderNode.getUserObject()))) {
                        savedTree.expandPath(new javax.swing.tree.TreePath(folderNode.getPath()));
                    }
                }
                javax.swing.tree.TreePath hostPath = new javax.swing.tree.TreePath(hostNode.getPath());
                if (!filtering && collapsed.contains(foldKey(hostNode.getUserObject()))) savedTree.collapsePath(hostPath);
                else savedTree.expandPath(hostPath);
            }
            if (toSelect != null) savedTree.setSelectionPath(new javax.swing.tree.TreePath(toSelect.getPath()));
        } finally {
            rebuilding = false;
        }
        savedCards.show(savedBody, favorites.isEmpty() ? "empty" : "list");
        savedFilter.setEnabled(!favorites.isEmpty());
    }

    /** Remembers a server or folder the user folded or opened (not while filtering). */
    private void folded(javax.swing.tree.TreePath path, boolean folded) {
        if (rebuilding || !savedFilter.getText().isBlank()) return;
        String key = foldKey(((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject());
        if (key != null && (folded ? collapsed.add(key) : collapsed.remove(key))) AppPrefs.setSshCollapsed(collapsed);
    }

    private AppPrefs.SshFavorite selectedFavorite() {
        Object last = savedTree.getLastSelectedPathComponent();
        return last instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof AppPrefs.SshFavorite f ? f : null;
    }

    /** The saved logs at or under a row: one log, a folder's, or a server's. */
    private static List<AppPrefs.SshFavorite> logsUnder(DefaultMutableTreeNode node) {
        List<AppPrefs.SshFavorite> logs = new ArrayList<>();
        for (var e = node.depthFirstEnumeration(); e.hasMoreElements(); ) {
            if (((DefaultMutableTreeNode) e.nextElement()).getUserObject() instanceof AppPrefs.SshFavorite f) logs.add(f);
        }
        return logs;
    }

    /**
     * Forgets a saved log, or every saved log in a folder or on a server (after asking, when
     * that's several at once). The form keeps what it shows.
     */
    private void forget(DefaultMutableTreeNode node) {
        List<AppPrefs.SshFavorite> gone = logsUnder(node);
        if (gone.isEmpty()) return;
        Object o = node.getUserObject();
        if (!(o instanceof AppPrefs.SshFavorite) && gone.size() > 1) {
            String message = o instanceof SavedFolder sf
                    ? java.text.MessageFormat.format(Messages.get("ssh.dialog.saved.forget.folder.confirm"),
                            gone.size(), folderLabel(sf.path()))
                    : java.text.MessageFormat.format(Messages.get("ssh.dialog.saved.forget.host.confirm"),
                            gone.size(), hostNames.getOrDefault((String) o, (String) o));
            int answer = JOptionPane.showConfirmDialog(this, message,
                    Messages.get("ssh.dialog.saved.forget.host.title"),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        gone.forEach(f -> names.add(f.name()));
        AppPrefs.SshFavorite selected = selectedFavorite();
        favorites.removeIf(x -> names.contains(x.name()));
        AppPrefs.saveSshFavorites(favorites);
        // A server with no saved logs left forgets its name too.
        for (AppPrefs.SshFavorite f : gone) {
            if (hostNames.containsKey(f.host()) && favorites.stream().noneMatch(x -> x.host().equals(f.host()))) {
                hostNames.remove(f.host());
                AppPrefs.setSshHostName(f.host(), null);
            }
        }
        // Nor does it keep the folding of servers and folders that are gone.
        java.util.Set<String> live = new java.util.HashSet<>();
        for (AppPrefs.SshFavorite f : favorites) {
            live.add(foldKey(f.host()));
            live.add(foldKey(new SavedFolder(f.host(), folder(f.remotePath()))));
        }
        if (collapsed.retainAll(live)) AppPrefs.setSshCollapsed(collapsed);
        if (selected != null && names.contains(selected.name())) savedTree.clearSelection();
        rebuildSavedTree();
        showName();
    }

    /** The problem with naming {@code host} {@code name}: another server has that name; else null. */
    private String renameProblem(String host, String name) {
        String n = name.strip();
        if (n.isEmpty()) return null;
        for (var e : hostNames.entrySet()) {
            if (!e.getKey().equals(host) && e.getValue().equalsIgnoreCase(n)) {
                return java.text.MessageFormat.format(Messages.get("ssh.dialog.saved.rename.duplicate"), e.getValue());
            }
        }
        return null;
    }

    /** Names a server, or forgets its name when {@code name} is blank, and shows it everywhere. */
    private void renameServer(String host, String name) {
        String n = name.strip();
        if (n.equals(hostNames.getOrDefault(host, "")) || renameProblem(host, n) != null) return;
        if (n.isEmpty()) hostNames.remove(host);
        else hostNames.put(host, n);
        AppPrefs.setSshHostName(host, n);
        showName();
        // After the editor has closed: the server may move, since servers sort by name.
        SwingUtilities.invokeLater(this::rebuildSavedTree);
    }

    /**
     * The row's right-click menu: Rename on a server, Duplicate on a log, then Forget, which
     * names what it forgets. The row is selected first, as in any list.
     */
    private void showSavedMenu(java.awt.event.MouseEvent e) {
        int row = savedTree.getClosestRowForLocation(e.getX(), e.getY());
        Rectangle r = row >= 0 ? savedTree.getRowBounds(row) : null;
        if (r == null || e.getY() < r.y || e.getY() >= r.y + r.height) return;
        savedTree.setSelectionRow(row);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) savedTree.getPathForRow(row).getLastPathComponent();
        Object o = node.getUserObject();
        JPopupMenu menu = new JPopupMenu();
        if (o instanceof String) {
            JMenuItem rename = new JMenuItem(Messages.get("ssh.dialog.saved.menu.rename"));
            rename.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F2, 0));
            rename.addActionListener(a -> savedTree.startEditingAtPath(savedTree.getPathForRow(row)));
            menu.add(rename);
            menu.addSeparator();
        } else if (o instanceof AppPrefs.SshFavorite f) {
            JMenuItem dup = new JMenuItem(Messages.get("ssh.dialog.saved.menu.duplicate"));
            dup.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_DOWN_MASK));
            dup.addActionListener(a -> duplicate(f));
            menu.add(dup);
            menu.addSeparator();
        }
        int count = logsUnder(node).size();
        String key = o instanceof String ? "ssh.dialog.saved.menu.forget.server"
                   : o instanceof SavedFolder ? "ssh.dialog.saved.menu.forget.folder"
                   : "ssh.dialog.saved.menu.forget";
        // "…" when it will ask first: several logs at once.
        JMenuItem forget = new JMenuItem(Messages.get(key) + (count > 1 ? "…" : ""));
        forget.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0));
        forget.addActionListener(a -> forget(node));
        menu.add(forget);
        menu.show(savedTree, e.getX(), e.getY());
    }

    /**
     * Renames a server on its row: its name in a field (empty shows the host as a hint), Enter
     * saves, Esc cancels. A name another server has is refused, with the field outlined in red
     * and the reason on a line under it, which the row keeps while renaming. Only F2 and the row's Rename start it, never a click.
     */
    private final class ServerNameEditor extends AbstractCellEditor implements javax.swing.tree.TreeCellEditor {
        private final JTextField field = new JTextField();
        private final JLabel error = new JLabel();
        /** As wide as the sidebar shows from the row's indent to the right edge, less its tools. */
        private int width = 160;
        private final JPanel panel = new JPanel(new BorderLayout(0, 2)) {
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return new Dimension(Math.max(width, 120), d.height);
            }
        };
        private String host;
        private DefaultMutableTreeNode node;

        ServerNameEditor() {
            panel.setOpaque(false);
            panel.add(field, BorderLayout.NORTH);
            panel.add(error, BorderLayout.SOUTH);
            field.addActionListener(e -> stopCellEditing());
            field.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e)  { showProblem(renameProblem(host, field.getText())); }
                public void removeUpdate(DocumentEvent e)  { showProblem(renameProblem(host, field.getText())); }
                public void changedUpdate(DocumentEvent e) { showProblem(renameProblem(host, field.getText())); }
            });
        }

        @Override public boolean isCellEditable(java.util.EventObject e) {
            return e == null;   // F2 and Rename start it with no event; clicks never do
        }

        @Override public Component getTreeCellEditorComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row) {
            node = (DefaultMutableTreeNode) value;
            host = (String) node.getUserObject();
            error.setFont(tree.getFont().deriveFont(tree.getFont().getSize2D() * 0.92f));
            error.setForeground(ClouseauColors.statusError());
            field.setText(hostNames.getOrDefault(host, ""));
            field.putClientProperty("JTextField.placeholderText", host);
            field.selectAll();
            showProblem(null);
            panel.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 4));
            Rectangle rb = tree.getRowBounds(row);
            int visible = tree.getParent() instanceof JViewport v ? v.getWidth() : tree.getWidth();
            width = visible - (rb != null ? rb.x : 0) - 4;
            SwingUtilities.invokeLater(field::requestFocusInWindow);
            return panel;
        }

        @Override public Object getCellEditorValue() {
            return field.getText();
        }

        @Override public boolean stopCellEditing() {
            String problem = renameProblem(host, field.getText());
            if (problem != null) {
                showProblem(problem);
                return false;
            }
            return super.stopCellEditing();
        }

        private void showProblem(String problem) {
            // The message line is there from the start, empty until needed, so the row keeps
            // its height: the tree sizes an editing row once, when the editing starts.
            field.putClientProperty("JComponent.outline", problem != null ? "error" : null);
            error.setText(problem != null ? problem : " ");
            field.repaint();
        }
    }

    /**
     * The saved-connections tree. It fills the sidebar's width and shows tools at the right of
     * the row under the mouse: on a log, a duplicate icon and a trash can; on a host, a trash can
     * that forgets every log under it. A press on a tool doesn't select the row.
     */
    private final class SavedTree extends JTree {
        static final int TOOL = 24;                // width of each tool's hit area
        private enum Tool { NONE, DUPLICATE, TRASH }
        private int hoverRow = -1;
        private Tool overTool = Tool.NONE;

        SavedTree(DefaultMutableTreeNode root) {
            super(root);
            // A rename comes back here as the edited text: it names the server, not the node.
            setModel(new javax.swing.tree.DefaultTreeModel(root) {
                @Override public void valueForPathChanged(javax.swing.tree.TreePath path, Object newValue) {
                    if (((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof String host) {
                        renameServer(host, String.valueOf(newValue));
                    }
                }
            });
            java.awt.event.MouseAdapter hover = new java.awt.event.MouseAdapter() {
                @Override public void mouseMoved(java.awt.event.MouseEvent e)  { track(e.getPoint()); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { hoverRow = -1; overTool = Tool.NONE; repaint(); }
            };
            addMouseListener(hover);
            addMouseMotionListener(hover);
        }

        /** Only a server's row can be edited: renaming it. */
        @Override public boolean isPathEditable(javax.swing.tree.TreePath path) {
            return isEditable() && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof String;
        }

        private boolean isFolder(int row) {
            javax.swing.tree.TreePath path = row >= 0 ? getPathForRow(row) : null;
            return path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof SavedFolder;
        }

        private boolean isLog(int row) {
            javax.swing.tree.TreePath path = row >= 0 ? getPathForRow(row) : null;
            return path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()
                    instanceof AppPrefs.SshFavorite;
        }

        private void track(Point p) {
            int row = getClosestRowForLocation(p.x, p.y);
            Rectangle r = row >= 0 ? getRowBounds(row) : null;
            int newRow = r != null && p.y >= r.y && p.y < r.y + r.height ? row : -1;
            Tool tool = Tool.NONE;
            if (newRow >= 0 && p.x >= getWidth() - TOOL - 2) tool = Tool.TRASH;
            else if (newRow >= 0 && isLog(newRow) && p.x >= getWidth() - 2 * TOOL - 2) tool = Tool.DUPLICATE;
            if (newRow != hoverRow || tool != overTool) {
                hoverRow = newRow;
                overTool = tool;
                repaint();
            }
        }

        /** Fills the sidebar, unless a row is wider: then it keeps its width and scrolls sideways. */
        @Override public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport v && v.getWidth() >= getPreferredSize().width;
        }

        @Override protected void processMouseEvent(java.awt.event.MouseEvent e) {
            if (e.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e)) {
                track(e.getPoint());
                if (overTool != Tool.NONE && hoverRow >= 0) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) getPathForRow(hoverRow).getLastPathComponent();
                    Tool tool = overTool;
                    e.consume();
                    SwingUtilities.invokeLater(() -> {
                        if (tool == Tool.TRASH) forget(node);
                        else if (node.getUserObject() instanceof AppPrefs.SshFavorite f) duplicate(f);
                    });
                    return;
                }
            }
            if (e.getID() == java.awt.event.MouseEvent.MOUSE_CLICKED && overTool != Tool.NONE) return;
            super.processMouseEvent(e);
        }

        @Override public String getToolTipText(java.awt.event.MouseEvent e) {
            track(e.getPoint());
            if (overTool == Tool.DUPLICATE) return Messages.get("ssh.dialog.saved.duplicate.tooltip");
            if (overTool == Tool.TRASH) {
                return isLog(hoverRow)    ? Messages.get("ssh.dialog.saved.remove.tooltip")
                     : isFolder(hoverRow) ? Messages.get("ssh.dialog.saved.forget.folder.tooltip")
                                          : Messages.get("ssh.dialog.saved.forget.host.tooltip");
            }
            return super.getToolTipText(e);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (hoverRow >= 0 && hoverRow < getRowCount()) {
                Rectangle r = getRowBounds(hoverRow);
                Color base = isRowSelected(hoverRow) ? UIManager.getColor("Tree.selectionForeground") : ClouseauColors.dimForeground();
                int y = r.y + (r.height - 14) / 2;
                ToolIcons.paintTrash(g2, getWidth() - TOOL + 5, y, overTool == Tool.TRASH
                        ? ClouseauColors.levelColor(com.droidenx.clouseau.api.LogEntry.LogLevel.ERROR) : base);
                if (isLog(hoverRow)) {
                    ToolIcons.paintDuplicate(g2, getWidth() - 2 * TOOL + 5, y,
                            overTool == Tool.DUPLICATE ? ClouseauColors.foreground() : base);
                }
            }
            g2.dispose();
        }
    }

    static String fileName(String path) {
        if (path == null) return "";
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    static String folder(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i) : i == 0 ? "/" : "";
    }

    /**
     * Three kinds of row. A server: its name in bold with the host dimmed beside it (or just the
     * host, in bold, if it has no name), with more room above and below. A folder: its path. A
     * log: its file name. Each row keeps room on its right for the row's tools (trash can, and
     * duplicate on a log), and the tree is never narrower than its widest row, so the tools,
     * drawn at the tree's right edge, never cover a name; a sidebar too narrow for a row
     * scrolls sideways.
     */
    private static final class SavedRenderer extends JPanel implements javax.swing.tree.TreeCellRenderer {
        /** Room kept free on the right for the tools: two on a log, one on a server or folder. */
        private static final int LOG_TOOLS = 2 * SavedTree.TOOL + 4, GROUP_TOOLS = SavedTree.TOOL + 4;
        private final javax.swing.tree.DefaultTreeCellRenderer colors = new javax.swing.tree.DefaultTreeCellRenderer();
        private final JLabel name = new JLabel(), host = new JLabel();
        private final java.util.Map<String, String> hostNames;

        SavedRenderer(java.util.Map<String, String> hostNames) {
            super(new BorderLayout(8, 0));
            this.hostNames = hostNames;
            setOpaque(false);
            add(name, BorderLayout.WEST);
            add(host, BorderLayout.CENTER);
        }

        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            colors.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            Color fg = colors.getForeground();
            Object o = value instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;
            // A server is the level above everything under it: its name is bold (the host beside
            // it stays regular and dim). Weight, not a fill: only the selected row is filled.
            boolean server = o instanceof String;
            name.setFont(server ? tree.getFont().deriveFont(Font.BOLD) : tree.getFont());
            name.setForeground(fg);
            host.setFont(tree.getFont());
            host.setForeground(selected ? fg : ClouseauColors.dimForeground());
            host.setVisible(false);
            setToolTipText(null);
            if (o instanceof AppPrefs.SshFavorite f) {
                name.setText(fileName(f.remotePath()));
                setToolTipText(f.user() + "@" + f.host() + (f.port() != 22 ? ":" + f.port() : "")
                        + "  " + f.remotePath());
            } else if (o instanceof SavedFolder sf) {
                name.setText(folderLabel(sf.path()));
            } else {
                String h = o == null ? "" : o.toString();
                String named = hostNames.get(h);
                name.setText(named != null ? named : h);
                host.setText(h);
                host.setVisible(named != null);
            }
            // Servers get more room above and below, so each one starts a block. The room is
            // even on both sides, so a selected server's fill stays centered on its text.
            int pad = server ? 6 : 3;
            setBorder(BorderFactory.createEmptyBorder(pad, 0, pad, o instanceof AppPrefs.SshFavorite ? LOG_TOOLS : GROUP_TOOLS));
            return this;
        }
    }

    /**
     * Shows the name of the server in Host, or a dim note saying where it's named: on its row
     * in Saved connections (right-click, Rename), once it's saved there.
     */
    private void showName() {
        String host = hostField.getText().trim();
        String name = hostNames.get(host);
        if (name != null) {
            nameValue.setText(name);
            nameValue.setForeground(ClouseauColors.foreground());
        } else {
            boolean saved = favorites != null && favorites.stream().anyMatch(f -> f.host().equals(host));
            nameValue.setText(Messages.get(saved ? "ssh.dialog.name.none" : "ssh.dialog.name.none.unsaved"));
            nameValue.setForeground(ClouseauColors.dimForeground());
        }
    }

    private void loadFavorite(AppPrefs.SshFavorite f) {
        hostField.setText(f.host());
        portField.setText(String.valueOf(f.port()));
        userField.setText(f.user());
        remotePathField.setText(f.remotePath());

        if (AUTH_KEY.equals(f.authType())) {
            keyFileField.setText(f.keyFilePath() != null ? f.keyFilePath() : "");
            keyPassphraseField.setText(f.keyPassphrase() != null ? f.keyPassphrase() : "");
        } else if (!AUTH_NONE.equals(f.authType())) {
            passwordField.setText(f.password() != null ? f.password() : "");
        }
        switchAuth(AUTH_KEY.equals(f.authType()) || AUTH_NONE.equals(f.authType()) ? f.authType() : AUTH_PASSWORD);

        parserCombo.setSelectedIndex(0);
        if (f.parserName() != null) {
            for (int i = 0; i < parsers.size(); i++) {
                if (parsers.get(i).getName().equals(f.parserName())) {
                    parserCombo.setSelectedIndex(i + 1);
                    break;
                }
            }
        }
        // A loaded connection stays saved: connecting updates it with any changes.
        saveBox.setSelected(true);
    }

    private int indexOf(String name) {
        for (int i = 0; i < favorites.size(); i++) if (favorites.get(i).name().equals(name)) return i;
        return -1;
    }

    /**
     * Starts a new connection from a saved one: the form gets all its details, but no saved entry
     * is picked, so Connect saves a new one. The file name in Remote path is selected, ready to
     * type another file on the same server.
     */
    private void duplicate(AppPrefs.SshFavorite f) {
        savedTree.clearSelection();
        loadFavorite(f);
        saveBox.setSelected(true);
        String path = remotePathField.getText();
        int slash = path.lastIndexOf('/');
        remotePathField.requestFocusInWindow();
        remotePathField.select(slash + 1, path.length());
    }

    /**
     * Saves the connection. With a saved log picked in the sidebar, it replaces that entry in
     * place, whatever changed (host, user and path included), instead of adding a second one.
     */
    private void saveFavorite(SshConfig c, String authType, String parserName, AppPrefs.SshFavorite replacing) {
        String name = c.user() + "@" + c.host() + ":" + c.remotePath();
        AppPrefs.SshFavorite fav = switch (authType) {
            case AUTH_KEY  -> new AppPrefs.SshFavorite(name, c.host(), c.port(), c.user(), AUTH_KEY,
                    null, c.keyFilePath(), c.keyPassphrase(), c.remotePath(), parserName);
            case AUTH_NONE -> new AppPrefs.SshFavorite(name, c.host(), c.port(), c.user(), AUTH_NONE,
                    null, null, null, c.remotePath(), parserName);
            default        -> new AppPrefs.SshFavorite(name, c.host(), c.port(), c.user(), AUTH_PASSWORD,
                    c.password(), null, null, c.remotePath(), parserName);
        };
        int at = replacing == null ? -1 : indexOf(replacing.name());
        favorites.removeIf(f -> f.name().equals(name) || (replacing != null && f.name().equals(replacing.name())));
        favorites.add(at >= 0 ? Math.min(at, favorites.size()) : 0, fav);
        AppPrefs.saveSshFavorites(favorites);
    }

    // ── Key file ──────────────────────────────────────────────────────────────

    /** The first of ~/.ssh/id_ed25519, id_ecdsa, id_rsa that exists. */
    private static Optional<String> defaultKeyFile() {
        Path dir = Path.of(System.getProperty("user.home"), ".ssh");
        for (String name : DEFAULT_KEYS) {
            Path p = dir.resolve(name);
            if (Files.isRegularFile(p)) return Optional.of(p.toString());
        }
        return Optional.empty();
    }

    private void browseKeyFile() {
        JFileChooser fc = new JFileChooser(System.getProperty("user.home") + "/.ssh");
        fc.setDialogTitle(Messages.get("ssh.dialog.key.file"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            keyFileField.setText(f.getAbsolutePath());
        }
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    /** The connection the form describes, after moving a {@code user@host:port} in Host into its fields. */
    private SshConfig formConfig() {
        selfChange = true;
        try {
            applyHostSpec();
        } finally {
            selfChange = false;
        }
        String host       = hostField.getText().trim();
        int    port       = port();
        String user       = userField.getText().trim();
        String remotePath = remotePathField.getText().trim();
        return switch (AUTH_TYPES[authCombo.getSelectedIndex()]) {
            case AUTH_KEY  -> new SshConfig(host, port, user, null,
                    keyFileField.getText().trim(), new String(keyPassphraseField.getPassword()), remotePath);
            case AUTH_NONE -> new SshConfig(host, port, user, null, null, null, remotePath);
            default        -> new SshConfig(host, port, user,
                    new String(passwordField.getPassword()), null, null, remotePath);
        };
    }

    // ── Test (G-14) ───────────────────────────────────────────────────────────

    /** A field changed: a test of the old details no longer says anything, so it stops and its result goes. */
    private void formChanged() {
        if (selfChange) return;
        cancelTest();
        validateForm();
    }

    /** Stops the running test, if any; its result is then ignored. */
    private void cancelTest() {
        SshRemote r = testRemote;
        if (r == null) return;
        testRemote = null;
        closeQuietly(r);
    }

    /** Closes {@code r} off the EDT: a disconnect sends a last message to the server. */
    static void closeQuietly(SshRemote r) {
        Thread t = new Thread(r::close, "ssh-remote-close");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Signs in and checks the file with {@code test} on the server, reporting in the hint line
     * which step failed and why, or that the file is readable. Opens no tab, and closes the
     * connection when done. Each step waits at most {@link SshRemote#TIMEOUT_SECONDS}.
     */
    private void onTest() {
        cancelTest();
        SshConfig config = formConfig();
        String authType = AUTH_TYPES[authCombo.getSelectedIndex()];
        SshRemote remote = new SshRemote(config);
        testRemote = remote;
        testBtn.setEnabled(false);
        hintLabel.setText(java.text.MessageFormat.format(Messages.get("ssh.test.running"), config.host()));
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                try {
                    remote.connect();
                    remote.check(config.remotePath());
                    return null;
                } finally {
                    remote.close();
                }
            }

            @Override protected void done() {
                if (testRemote != remote) return;   // cancelled: the form changed or the dialog closed
                testRemote = null;
                validateForm();
                try {
                    get();
                    hintLabel.show(java.text.MessageFormat.format(Messages.get("ssh.test.ok"),
                            config.remotePath()), NoticeLabel.Kind.OK);
                    hintLabel.setToolTipText(null);
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    String text = cause instanceof SshRemote.Failure f
                            ? describe(f, config, authType)
                            : String.valueOf(cause.getMessage());
                    hintLabel.show(text, NoticeLabel.Kind.ERROR);
                    hintLabel.setToolTipText(cause.getMessage());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /** What went wrong, for the user: the step that failed and the likely fix. */
    static String describe(SshRemote.Failure f, SshConfig c, String authType) {
        String detail = f.getMessage();
        return switch (f.reason()) {
            case UNKNOWN_HOST -> Messages.format("ssh.test.unknown.host", c.host());
            case REFUSED      -> Messages.format("ssh.test.refused", c.host(), String.valueOf(c.port()));
            case TIMED_OUT    -> Messages.format("ssh.test.timed.out", c.host(), SshRemote.TIMEOUT_SECONDS);
            case REJECTED     -> Messages.format(switch (authType) {
                case AUTH_KEY  -> "ssh.test.rejected.key";
                case AUTH_NONE -> "ssh.test.rejected.none";
                default        -> "ssh.test.rejected.password";
            }, c.host(), c.user());
            case KEY_FILE     -> Messages.format("ssh.test.key.file", detail);
            case MISSING      -> Messages.format("ssh.test.missing", c.remotePath());
            case UNREADABLE   -> Messages.format("ssh.test.unreadable", c.remotePath(), c.user());
            case FOLDER       -> Messages.format("ssh.test.folder", c.remotePath());
            case NO_SFTP      -> Messages.format("ssh.browse.no.sftp", c.host());
            case OTHER        -> Messages.format(switch (f.step()) {
                case CONNECT -> "ssh.test.other.connect";
                case SIGN_IN -> "ssh.test.other.sign.in";
                case FILE    -> "ssh.test.other.file";
            }, detail);
        };
    }

    // ── Browse the server (G-13) ──────────────────────────────────────────────

    /** Lets the user pick Remote path from the server's folders, signed in with the form's details. */
    private void browseRemote() {
        SshConfig config = formConfig();
        new RemoteFileDialog(this, config, AUTH_TYPES[authCombo.getSelectedIndex()])
                .showDialog()
                .ifPresent(path -> {
                    remotePathField.setText(path);
                    remotePathField.requestFocusInWindow();
                });
    }

    private void onConnect() {
        SshConfig config = formConfig();
        String host     = config.host();
        String authType = AUTH_TYPES[authCombo.getSelectedIndex()];

        int parserIdx = parserCombo.getSelectedIndex();
        Optional<LogParser> parser = parserIdx > 0
                ? Optional.of(parsers.get(parserIdx - 1))
                : Optional.empty();

        if (saveBox.isSelected()) {
            saveFavorite(config, authType, parser.map(LogParser::getName).orElse(null), selectedFavorite());
        }
        AppPrefs.setSshSaveConnection(saveBox.isSelected());

        result = new Result(config, parser);
        dispose();
    }

    /** Lets only digits into a field, up to {@code max} of them. */
    private static final class DigitsOnly extends DocumentFilter {
        private final int max;
        DigitsOnly(int max) { this.max = max; }

        @Override public void insertString(FilterBypass fb, int off, String s, AttributeSet a)
                throws BadLocationException { replace(fb, off, 0, s, a); }

        @Override public void replace(FilterBypass fb, int off, int len, String s, AttributeSet a)
                throws BadLocationException {
            String digits = s == null ? "" : s.replaceAll("\\D", "");
            int room = max - (fb.getDocument().getLength() - len);
            if (digits.length() > room) digits = digits.substring(0, Math.max(0, room));
            super.replace(fb, off, len, digits, a);
        }
    }
}
