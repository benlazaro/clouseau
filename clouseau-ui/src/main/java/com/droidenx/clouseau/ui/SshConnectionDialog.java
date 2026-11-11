package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.SshConfig;
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
 * grouped by host (the way people look for a log: which machine, then which file), with a
 * filter on top, and a trash can on the row under the mouse; a connection is saved by ticking
 * "Save this connection", beside Connect, which remembers whether it was ticked last time.
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

    // Saved connections: a sidebar tree, host → logs
    private final JTextField                   savedFilter = new JTextField(14);
    private final DefaultMutableTreeNode       savedRoot   = new DefaultMutableTreeNode();
    private final SavedTree                    savedTree   = new SavedTree(savedRoot);
    private final CardLayout                   savedCards  = new CardLayout();
    private final JPanel                       savedBody   = new JPanel(savedCards);
    private final JCheckBox                    saveBox     = new JCheckBox(Messages.get("ssh.dialog.save"));
    private       List<AppPrefs.SshFavorite>   favorites;

    // Footer
    private final JButton connectBtn = new JButton(Messages.get("ssh.dialog.connect"));
    private final JLabel  hintLabel  = new JLabel(" ");

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
            @Override public void focusLost(FocusEvent e) { applyHostSpec(); }
        });

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
        content.add(remotePathField, "wrap");
        remotePathField.putClientProperty("JTextField.placeholderText",
                Messages.get("ssh.dialog.remote.path.placeholder"));
        content.add(new JLabel(Messages.get("ssh.dialog.parser")), SettingsDialog.INDENT);
        content.add(parserCombo, "wrap");

        // What Connect is waiting for, while it's disabled. Always present, so the dialog
        // doesn't change size as the fields fill in.
        hintLabel.setForeground(ClouseauColors.dimForeground());
        content.add(hintLabel, "span 2, gaptop 6, growx 0");

        // ── Footer: Save this connection · Cancel · Connect ──────────────
        connectBtn.addActionListener(e -> onConnect());
        JButton cancelBtn = new JButton(Messages.get("ssh.dialog.cancel"));
        cancelBtn.addActionListener(e -> dispose());
        saveBox.setToolTipText(Messages.get("ssh.dialog.save.tooltip"));

        // Saving belongs to connecting, so the box sits right before the buttons.
        JPanel buttons = new JPanel(new MigLayout("insets 14 28 14 28", "push[]16[][]"));
        buttons.add(saveBox);
        buttons.add(cancelBtn);
        buttons.add(connectBtn);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(new JSeparator(), BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.CENTER);

        // Live validation
        DocumentListener validator = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { validateForm(); }
            public void removeUpdate(DocumentEvent e)  { validateForm(); }
            public void changedUpdate(DocumentEvent e) { validateForm(); }
        };
        hostField.getDocument().addDocumentListener(validator);
        portField.getDocument().addDocumentListener(validator);
        userField.getDocument().addDocumentListener(validator);
        remotePathField.getDocument().addDocumentListener(validator);

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildSavedSidebar(), BorderLayout.WEST);
        root.add(content, BorderLayout.CENTER);
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
        if (isDisplayable()) pack();
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
     * The sidebar: a filter field, the saved connections grouped by host (hosts A–Z, logs by
     * file name, the folder dimmed under it), and Remove. With nothing saved, it says how to
     * save one instead.
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
        savedTree.setCellRenderer(new SavedRenderer());
        savedTree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        javax.swing.ToolTipManager.sharedInstance().registerComponent(savedTree);
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
        side.setPreferredSize(new Dimension(250, 10));
        // A hairline divides the sidebar from the form, as panels are divided elsewhere.
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Separator.foreground")));
        return side;
    }

    /** Rebuilds the tree from the saved connections that match the filter, all hosts expanded. */
    private void rebuildSavedTree() {
        AppPrefs.SshFavorite selected = selectedFavorite();
        String q = savedFilter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        java.util.TreeMap<String, List<AppPrefs.SshFavorite>> byHost =
                new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (AppPrefs.SshFavorite f : favorites) {
            String hay = (f.host() + " " + f.remotePath() + " " + f.user()).toLowerCase(java.util.Locale.ROOT);
            if (q.isEmpty() || hay.contains(q)) byHost.computeIfAbsent(f.host(), h -> new ArrayList<>()).add(f);
        }
        savedRoot.removeAllChildren();
        DefaultMutableTreeNode toSelect = null;
        for (var entry : byHost.entrySet()) {
            DefaultMutableTreeNode hostNode = new DefaultMutableTreeNode(entry.getKey());
            entry.getValue().sort(java.util.Comparator.comparing((AppPrefs.SshFavorite f) -> fileName(f.remotePath()),
                    String.CASE_INSENSITIVE_ORDER).thenComparing(AppPrefs.SshFavorite::remotePath));
            for (AppPrefs.SshFavorite f : entry.getValue()) {
                DefaultMutableTreeNode n = new DefaultMutableTreeNode(f);
                hostNode.add(n);
                if (selected != null && f.name().equals(selected.name())) toSelect = n;
            }
            savedRoot.add(hostNode);
        }
        ((javax.swing.tree.DefaultTreeModel) savedTree.getModel()).reload();
        for (int i = 0; i < savedTree.getRowCount(); i++) savedTree.expandRow(i);
        if (toSelect != null) savedTree.setSelectionPath(new javax.swing.tree.TreePath(toSelect.getPath()));
        savedCards.show(savedBody, favorites.isEmpty() ? "empty" : "list");
        savedFilter.setEnabled(!favorites.isEmpty());
    }

    private AppPrefs.SshFavorite selectedFavorite() {
        Object last = savedTree.getLastSelectedPathComponent();
        return last instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof AppPrefs.SshFavorite f ? f : null;
    }

    /**
     * Forgets a saved log, or every saved log on a host (after asking, since that's several at
     * once). The form keeps what it shows.
     */
    private void forget(DefaultMutableTreeNode node) {
        List<AppPrefs.SshFavorite> gone = new ArrayList<>();
        if (node.getUserObject() instanceof AppPrefs.SshFavorite f) {
            gone.add(f);
        } else {
            for (int i = 0; i < node.getChildCount(); i++) {
                if (((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject() instanceof AppPrefs.SshFavorite f) gone.add(f);
            }
            if (gone.isEmpty()) return;
            if (gone.size() > 1) {
                int answer = JOptionPane.showConfirmDialog(this,
                        java.text.MessageFormat.format(Messages.get("ssh.dialog.saved.forget.host.confirm"),
                                gone.size(), node.getUserObject()),
                        Messages.get("ssh.dialog.saved.forget.host.title"),
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (answer != JOptionPane.OK_OPTION) return;
            }
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        gone.forEach(f -> names.add(f.name()));
        AppPrefs.SshFavorite selected = selectedFavorite();
        favorites.removeIf(x -> names.contains(x.name()));
        AppPrefs.saveSshFavorites(favorites);
        if (selected != null && names.contains(selected.name())) savedTree.clearSelection();
        rebuildSavedTree();
    }

    /**
     * The saved-connections tree. It fills the sidebar's width and shows a trash can at the right
     * of the row under the mouse: on a log it forgets that log, on a host every log under it.
     * A press on the trash can doesn't select the row.
     */
    private final class SavedTree extends JTree {
        private static final int TRASH_ZONE = 26;
        private int hoverRow = -1;
        private boolean overTrash;

        SavedTree(DefaultMutableTreeNode root) {
            super(root);
            java.awt.event.MouseAdapter hover = new java.awt.event.MouseAdapter() {
                @Override public void mouseMoved(java.awt.event.MouseEvent e)  { track(e.getPoint()); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { hoverRow = -1; overTrash = false; repaint(); }
            };
            addMouseListener(hover);
            addMouseMotionListener(hover);
        }

        private void track(Point p) {
            int row = getClosestRowForLocation(p.x, p.y);
            Rectangle r = row >= 0 ? getRowBounds(row) : null;
            int newRow = r != null && p.y >= r.y && p.y < r.y + r.height ? row : -1;
            boolean newOver = newRow >= 0 && p.x >= getWidth() - TRASH_ZONE;
            if (newRow != hoverRow || newOver != overTrash) {
                hoverRow = newRow;
                overTrash = newOver;
                repaint();
            }
        }

        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        @Override protected void processMouseEvent(java.awt.event.MouseEvent e) {
            if (e.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e)) {
                track(e.getPoint());
                if (overTrash && hoverRow >= 0) {
                    javax.swing.tree.TreePath path = getPathForRow(hoverRow);
                    e.consume();
                    if (path != null) SwingUtilities.invokeLater(() -> forget((DefaultMutableTreeNode) path.getLastPathComponent()));
                    return;
                }
            }
            if (e.getID() == java.awt.event.MouseEvent.MOUSE_CLICKED && overTrash) return;
            super.processMouseEvent(e);
        }

        @Override public String getToolTipText(java.awt.event.MouseEvent e) {
            track(e.getPoint());
            if (overTrash && hoverRow >= 0) {
                Object n = getPathForRow(hoverRow).getLastPathComponent();
                return n instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof AppPrefs.SshFavorite
                        ? Messages.get("ssh.dialog.saved.remove.tooltip")
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
                Color c = overTrash ? ClouseauColors.levelColor(com.droidenx.clouseau.api.LogEntry.LogLevel.ERROR)
                        : isRowSelected(hoverRow) ? UIManager.getColor("Tree.selectionForeground") : ClouseauColors.dimForeground();
                paintTrash(g2, getWidth() - TRASH_ZONE + 7, r.y + (r.height - 14) / 2, c);
            }
            g2.dispose();
        }

        /** A 12×14 outline trash can: lid with a handle, body with two ribs. */
        private void paintTrash(Graphics2D g, int x, int y, Color c) {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x, y + 3, x + 12, y + 3);                       // lid
            g.drawLine(x + 4, y + 3, x + 4, y + 1);                    // handle
            g.drawLine(x + 4, y + 1, x + 8, y + 1);
            g.drawLine(x + 8, y + 1, x + 8, y + 3);
            java.awt.geom.Path2D.Float body = new java.awt.geom.Path2D.Float();
            body.moveTo(x + 1.5f, y + 3);
            body.lineTo(x + 2.5f, y + 14);
            body.lineTo(x + 9.5f, y + 14);
            body.lineTo(x + 10.5f, y + 3);
            g.draw(body);
            g.drawLine(x + 5, y + 6, x + 5, y + 11);                   // ribs
            g.drawLine(x + 7, y + 6, x + 7, y + 11);
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
     * Host rows show the host; log rows show the file name with its folder dimmed below, as one
     * HTML label so the tree sizes each row to its text.
     */
    private static final class SavedRenderer extends javax.swing.tree.DefaultTreeCellRenderer {
        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            setIcon(null);
            setOpaque(false);
            setBackgroundNonSelectionColor(null);
            Object o = value instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;
            // Leave room on the right for the trash can.
            setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 22));
            if (o instanceof AppPrefs.SshFavorite f) {
                Color dim = selected ? getForeground() : ClouseauColors.dimForeground();
                setText("<html>" + esc(fileName(f.remotePath()))
                        + "<br><span style='color:" + hex(dim) + ";font-size:0.92em'>"
                        + esc(folder(f.remotePath())) + "</span></html>");
                setToolTipText(f.user() + "@" + f.host() + (f.port() != 22 ? ":" + f.port() : "")
                        + "  " + f.remotePath());
            } else {
                setText(o == null ? "" : o.toString());
                setToolTipText(null);
            }
            return this;
        }

        private static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }
        private static String esc(String t) { return t.replace("&", "&amp;").replace("<", "&lt;"); }
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

    private void saveFavorite(SshConfig c, String authType, String parserName) {
        String name = c.user() + "@" + c.host() + ":" + c.remotePath();
        AppPrefs.SshFavorite fav = switch (authType) {
            case AUTH_KEY  -> new AppPrefs.SshFavorite(name, c.host(), c.port(), c.user(), AUTH_KEY,
                    null, c.keyFilePath(), c.keyPassphrase(), c.remotePath(), parserName);
            case AUTH_NONE -> new AppPrefs.SshFavorite(name, c.host(), c.port(), c.user(), AUTH_NONE,
                    null, null, null, c.remotePath(), parserName);
            default        -> new AppPrefs.SshFavorite(name, c.host(), c.port(), c.user(), AUTH_PASSWORD,
                    c.password(), null, null, c.remotePath(), parserName);
        };
        favorites.removeIf(f -> f.name().equals(name));
        favorites.add(0, fav);
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

    private void onConnect() {
        applyHostSpec();
        String host       = hostField.getText().trim();
        int    port       = port();
        String user       = userField.getText().trim();
        String remotePath = remotePathField.getText().trim();
        String authType   = AUTH_TYPES[authCombo.getSelectedIndex()];

        SshConfig config = switch (authType) {
            case AUTH_KEY  -> new SshConfig(host, port, user, null,
                    keyFileField.getText().trim(), new String(keyPassphraseField.getPassword()), remotePath);
            case AUTH_NONE -> new SshConfig(host, port, user, null, null, null, remotePath);
            default        -> new SshConfig(host, port, user,
                    new String(passwordField.getPassword()), null, null, remotePath);
        };

        int parserIdx = parserCombo.getSelectedIndex();
        Optional<LogParser> parser = parserIdx > 0
                ? Optional.of(parsers.get(parserIdx - 1))
                : Optional.empty();

        if (saveBox.isSelected()) saveFavorite(config, authType, parser.map(LogParser::getName).orElse(null));
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
