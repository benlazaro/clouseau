package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.SshConfig;
import com.droidenx.clouseau.core.SshRemote;
import com.droidenx.clouseau.core.SshSaved;
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
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SSH Connections window: saved connections on the left, and on the right a form for what's
 * picked there. Call {@link #showDialog()} to display it; returns the log to open if the user
 * connected.
 *
 * <p>A saved connection is one sign-in (user, host, port, and how to authenticate) with an
 * optional name and the logs saved on it ({@link SshSaved}). The list is a tree: connection ›
 * folder › log. The form has three modes, so it never mixes a connection's details with one
 * log's:
 * <ul>
 *   <li><b>A connection</b> picked: its Connection section only (Name, Host, Port, Username,
 *       Authentication), and the default button is <b>Save</b>.</li>
 *   <li><b>A log</b> picked (or New Log… on a connection): its Log section only, the connection
 *       shown read-only as its first line, and the default button is <b>Connect</b>, which saves
 *       the log, then connects.</li>
 *   <li><b>New connection</b> (the + beside the filter, Ctrl+N): both sections, with "Save this
 *       connection", and <b>Connect</b>.</li>
 * </ul>
 * Connect signs in and checks the file first, and closes only when that works. Laid out like the
 * Settings window: sections with a hairline title, labels on the left, fields indented under
 * them, and Cancel before the default button in a footer under the form.
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

    /** What the form shows: a new connection, a saved connection, or a log on a saved connection. */
    private enum Mode { NEW, CONNECTION, LOG }

    /** A saved log's row: the log, and the key of the connection it's saved on. */
    private record LogNode(String connKey, SshSaved.Log log) {
        @Override public String toString() { return fileName(log.remotePath()); }
    }

    /** A folder of saved logs on one connection: the tree's middle level. */
    private record SavedFolder(String connKey, String path) {
        @Override public String toString() { return folderLabel(path); }
    }

    // Connection
    private final JTextField nameField       = new JTextField(22);
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
    /** A log's connection, read-only, as the Log section's first line. */
    private final JLabel            logConnectionValue = new JLabel();
    private final JCheckBox         saveBox = new JCheckBox(Messages.get("ssh.dialog.save"));

    /** The form's rows, by mode, so {@link #showMode} can show the ones that apply. */
    private final List<JComponent> connectionRows = new ArrayList<>();
    private final List<JComponent> logConnectionRow = new ArrayList<>();

    // Saved connections: a sidebar tree, connection → folder → logs
    private final JTextField                   savedFilter = new JTextField(14);
    private final DefaultMutableTreeNode       savedRoot   = new DefaultMutableTreeNode();
    private final SavedTree                    savedTree   = new SavedTree(savedRoot);
    private final CardLayout                   savedCards  = new CardLayout();
    private final JPanel                       savedBody   = new JPanel(savedCards);
    private       List<SshSaved.Connection>    connections;
    /** Connections and folders the user folded (see {@link #foldKey}), remembered between visits. */
    private final java.util.Set<String>        collapsed   = AppPrefs.getSshCollapsed();
    /** Set while the tree is rebuilt, so the expanding and selecting it does isn't taken for the user's. */
    private boolean                            rebuilding;

    // What the form holds
    private Mode   mode = Mode.NEW;
    /** The saved connection the form holds (Connection and Log modes); null for a new one. */
    private String editingKey;
    /** The saved log the form holds (Log mode); null for a new log on the connection. */
    private SshSaved.Log editingLog;

    // Footer
    /** Save in Connection mode, Connect otherwise. */
    private final JButton actionBtn = new JButton(Messages.get("ssh.dialog.connect"));
    /** Lists the server's folders to pick Remote path from (G-13). */
    private final JButton remoteBrowseBtn = new JButton(Messages.get("ssh.dialog.remote.browse"));
    private final NoticeLabel hintLabel = new NoticeLabel();
    /**
     * The check Connect runs first (sign in, then the file), closed if the form changes or the
     * dialog closes; null if none is running (G-14).
     */
    private SshRemote testRemote;
    /** Set while a change comes from the dialog itself (filling the form, splitting user@host:port). */
    private boolean selfChange;
    /** The saved connections | the form; its divider is remembered. */
    private JSplitPane split;
    // Wide enough for a typical log row, three levels deep, with its tools' room beside it.
    private static final int SIDEBAR_WIDTH = 300, SIDEBAR_MIN = 160;

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

        connections = new ArrayList<>(AppPrefs.getSshConnections());
        saveBox.setSelected(AppPrefs.isSshSaveConnection());

        setContentPane(buildContent());
        rebuildSavedTree();
        getRootPane().setDefaultButton(actionBtn);
        // Esc, Cancel and the close box all ask first when the form has unsaved changes.
        getRootPane().registerKeyboardAction(
                e -> requestClose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        // Ctrl+S saves what the form holds without connecting: a connection, or a log.
        getRootPane().registerKeyboardAction(
                e -> saveWithoutConnecting(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(
                e -> newConnection(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                initialFocus.requestFocusInWindow();
            }
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                requestClose();
            }
        });
        switchAuth(AUTH_PASSWORD);
        showMode(Mode.NEW);
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
     * Fills the form with a restored tab's connection (UC-17, D-19), as a new connection that
     * isn't saved unless asked, so Reconnect only asks for the password or passphrase a session
     * never keeps; that field takes the focus.
     */
    void prefill(com.droidenx.clouseau.core.Session.Ssh ssh, String parserName) {
        showMode(Mode.NEW);
        fillConnection(new SshSaved.Connection(null, ssh.host(), ssh.port(), ssh.user(), ssh.auth(),
                null, ssh.keyFile(), null, List.of()));
        fillLog(new SshSaved.Log(ssh.remotePath(), parserName));
        saveBox.setSelected(false);
        if (AUTH_PASSWORD.equals(ssh.auth())) initialFocus = passwordField;
        else if (AUTH_KEY.equals(ssh.auth()) && ssh.keyPassphrase()) initialFocus = keyPassphraseField;
        validateForm();
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private JPanel buildContent() {
        // Two columns, labels and fields, with the Settings window's padding, section spacing
        // and indent. Fields fill their column; the dialog sizes to its widest row.
        content = new JPanel(new MigLayout("insets 24 28 20 28, gapy 10, hidemode 3",
                "[]24[grow, fill, 320::]"));

        // ── Connection ───────────────────────────────────────────────────
        JComponent connHeading = SettingsDialog.sectionLabel(Messages.get("ssh.dialog.section.connection"));
        content.add(connHeading, "span 2, growx, wrap, gapbottom 4");
        JLabel nameLabel = new JLabel(Messages.get("ssh.dialog.name"));
        content.add(nameLabel, SettingsDialog.INDENT);
        content.add(nameField, "wrap");
        nameField.putClientProperty("JTextField.placeholderText", Messages.get("ssh.dialog.name.placeholder"));
        JLabel hostLabel = new JLabel(Messages.get("ssh.dialog.host"));
        content.add(hostLabel, SettingsDialog.INDENT);
        JPanel hostRow = new JPanel(new MigLayout("insets 0, gapx 8", "[grow, fill][][]"));
        hostRow.setOpaque(false);
        hostRow.add(hostField);
        hostRow.add(new JLabel(Messages.get("ssh.dialog.port")), "gapleft 8");
        hostRow.add(portField, "w 64!");
        content.add(hostRow, "wrap");
        JLabel userLabel = new JLabel(Messages.get("ssh.dialog.user"));
        content.add(userLabel, SettingsDialog.INDENT);
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

        JLabel authLabel = new JLabel(Messages.get("ssh.dialog.auth.method"));
        content.add(authLabel, SettingsDialog.INDENT);
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
        // The rows a log's form hides (the password and key rows also follow the method).
        connectionRows.addAll(List.of(connHeading, nameLabel, nameField, hostLabel, hostRow,
                userLabel, userField, authLabel, authCombo));

        // ── Log ──────────────────────────────────────────────────────────
        content.add(logHeading = SettingsDialog.sectionLabel(Messages.get("ssh.dialog.section.log")),
                "span 2, growx, wrap, gapbottom 4");
        JLabel logConnLabel = new JLabel(Messages.get("ssh.dialog.log.connection"));
        content.add(logConnLabel, SettingsDialog.INDENT);
        content.add(logConnectionValue, "wmin 0, wrap");
        logConnectionRow.addAll(List.of(logConnLabel, logConnectionValue));
        logRows.add(logHeading);
        JLabel pathLabel = new JLabel(Messages.get("ssh.dialog.remote.path"));
        content.add(pathLabel, SettingsDialog.INDENT);
        JPanel remotePathRow = new JPanel(new MigLayout("insets 0, gapx 8", "[grow, fill][]"));
        remotePathRow.setOpaque(false);
        remotePathRow.add(remotePathField);
        remotePathRow.add(remoteBrowseBtn);
        content.add(remotePathRow, "wrap");
        remoteBrowseBtn.setToolTipText(Messages.get("ssh.dialog.remote.browse.tooltip"));
        remoteBrowseBtn.addActionListener(e -> browseRemote());
        remotePathField.putClientProperty("JTextField.placeholderText",
                Messages.get("ssh.dialog.remote.path.placeholder"));
        JLabel parserLabel = new JLabel(Messages.get("ssh.dialog.parser"));
        content.add(parserLabel, SettingsDialog.INDENT);
        content.add(parserCombo, "wrap");
        logRows.addAll(List.of(pathLabel, remotePathRow, parserLabel, parserCombo));
        // A new connection's last line, above Connect, like "Remember me" on a sign-in form:
        // a one-off connection isn't kept.
        content.add(saveBox, "skip 1, wrap, gaptop 4");
        saveBox.setToolTipText(Messages.get("ssh.dialog.save.tooltip"));

        // What the default button is waiting for, while it's disabled; then what Connect's check
        // found, or that Save worked. Always present, so the dialog doesn't change size as the
        // fields fill in.
        content.add(hintLabel, "span 2, gaptop 10, growx 0");

        // ── Footer, under the form only: Cancel · Save or Connect ────────
        actionBtn.addActionListener(e -> {
            if (mode == Mode.CONNECTION) saveConnection();
            else onConnect();
        });
        JButton cancelBtn = new JButton(Messages.get("ssh.dialog.cancel"));
        cancelBtn.addActionListener(e -> requestClose());
        JPanel footer = DialogFooter.of(cancelBtn, actionBtn);

        // Live validation. Any change also stops a check Connect is running, and drops its result.
        DocumentListener validator = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { formChanged(); }
            public void removeUpdate(DocumentEvent e)  { formChanged(); }
            public void changedUpdate(DocumentEvent e) { formChanged(); }
        };
        for (JTextField f : List.of(nameField, hostField, portField, userField, remotePathField,
                passwordField, keyFileField, keyPassphraseField)) {
            f.getDocument().addDocumentListener(validator);
        }
        authCombo.addActionListener(e -> formChanged());
        parserCombo.addActionListener(e -> formChanged());

        JPanel formPane = new JPanel(new BorderLayout());
        formPane.add(content, BorderLayout.CENTER);
        formPane.add(footer, BorderLayout.SOUTH);
        // Drag the divider to widen the saved connections; the form takes the rest.
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSavedSidebar(), formPane);
        split.setBorder(null);
        split.setResizeWeight(0);
        split.setContinuousLayout(true);
        JPanel root = new JPanel(new BorderLayout());
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    private JComponent logHeading;

    private void relayout() {
        if (content == null) return;
        hintLabel.invalidate();
        content.invalidate();
        content.revalidate();
        content.repaint();
    }
    /** The form, whose rows {@link #showMode} shows and hides. */
    private JPanel content;
    private final List<JComponent> logRows = new ArrayList<>();

    /**
     * Shows the rows {@code m} uses: a connection's section, a log's (with its connection as the
     * first line), or both and "Save this connection" for a new one. The default button follows:
     * Save for a connection, Connect otherwise.
     */
    private void showMode(Mode m) {
        mode = m;
        boolean conn = m != Mode.LOG, log = m != Mode.CONNECTION;
        connectionRows.forEach(c -> c.setVisible(conn));
        logRows.forEach(c -> c.setVisible(log));
        logConnectionRow.forEach(c -> c.setVisible(m == Mode.LOG));
        saveBox.setVisible(m == Mode.NEW);
        switchAuth(AUTH_TYPES[authCombo.getSelectedIndex()]);
        actionBtn.setText(Messages.get(m == Mode.CONNECTION ? "ssh.dialog.save.button" : "ssh.dialog.connect"));
        actionBtn.setToolTipText(Messages.get(m == Mode.CONNECTION ? "ssh.dialog.save.button.tooltip"
                                                                   : "ssh.dialog.connect.tooltip"));
        // A log's Connection line: the saved connection's name and address.
        SshSaved.Connection c = editingConnection();
        if (m == Mode.LOG && c != null) {
            logConnectionValue.setText(c.name() != null ? c.name() + "  (" + c.address() + ")" : c.address());
        }
        hintLabel.setText(" ");
        hintLabel.setToolTipText(null);
        // Rows that come back need the form laid out again, or MigLayout keeps them at no height.
        // Once more after this event, too: the notice's text is set after the switch, and MigLayout
        // would otherwise keep the width it measured for the empty notice.
        relayout();
        SwingUtilities.invokeLater(this::relayout);
    }

    private void switchAuth(String authType) {
        boolean conn   = mode != Mode.LOG;
        boolean usePw  = conn && AUTH_PASSWORD.equals(authType);
        boolean useKey = conn && AUTH_KEY.equals(authType);
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
            setMinimumSize(new Dimension(getMinimumSize().width, Math.min(getMinimumSize().height, pref.height)));
            setSize(Math.max(getWidth(), pref.width), Math.max(getHeight(), pref.height));
        }
    }

    /**
     * Enables the default button when the form has what it needs, and says what's missing while
     * it doesn't: a connection needs a host and user (and a valid port), a log a path, a new
     * connection all of them. Save on a connection also needs something to save.
     */
    private void validateForm() {
        boolean conn = mode != Mode.LOG, log = mode != Mode.CONNECTION;
        HostSpec spec = parseHostSpec(hostField.getText().trim());
        boolean hasHost = spec != null || !hostField.getText().isBlank();
        boolean hasUser = !userField.getText().isBlank() || (spec != null && spec.user() != null);
        boolean hasPath = !remotePathField.getText().isBlank();
        boolean portOk  = portField.getText().isBlank() || validPort(portField.getText());

        List<String> missing = new ArrayList<>();
        if (conn && !hasHost) missing.add(Messages.get("ssh.dialog.missing.host"));
        if (conn && !hasUser) missing.add(Messages.get("ssh.dialog.missing.user"));
        if (log && !hasPath)  missing.add(Messages.get("ssh.dialog.missing.path"));
        if (conn && !portOk)  missing.add(Messages.get("ssh.dialog.missing.port"));

        boolean checking = testRemote != null;
        actionBtn.setEnabled(missing.isEmpty() && !checking && (mode != Mode.CONNECTION || isEdited()));
        // Browsing needs a server to sign in to, not a path: a log's connection has one.
        remoteBrowseBtn.setEnabled(!checking && (mode == Mode.LOG || (hasHost && hasUser && portOk)));
        if (!checking) {
            hintLabel.setText(missing.isEmpty() ? " "
                    : java.text.MessageFormat.format(Messages.get("ssh.dialog.missing"), String.join(", ", missing)));
        }
        portField.putClientProperty("JComponent.outline", portOk ? null : "error");
        // A clash Save reported goes once the field changes (Save checks again).
        nameField.putClientProperty("JComponent.outline", null);
        hostField.putClientProperty("JComponent.outline", null);
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
     * The sidebar: a filter field with + (New connection) beside it, and the saved connections as
     * a tree, connection › folder › log (connections A–Z by what their row shows, folders and
     * logs A–Z), each connection and folder folding. A right-click offers what applies: Rename
     * and New Log… on a connection, Duplicate on a log, Forget on all three. With nothing saved,
     * it says how to save one instead. No heading: the window's title and the filter say what
     * the list is.
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
        JButton newBtn = new JButton("+");
        newBtn.setToolTipText(Messages.get("ssh.dialog.new.tooltip"));
        newBtn.addActionListener(e -> newConnection());

        savedTree.setRootVisible(false);
        savedTree.setShowsRootHandles(true);
        savedTree.setRowHeight(0);                       // rows size to their renderer
        savedTree.setCellRenderer(new SavedRenderer());
        // A connection is renamed on its row (F2, or Rename in its right-click menu); a click away
        // keeps the name typed, unless another connection has it.
        savedTree.setEditable(true);
        savedTree.setCellEditor(new ConnectionNameEditor());
        savedTree.setInvokesStopCellEditing(true);
        savedTree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
            @Override public void treeExpanded(javax.swing.event.TreeExpansionEvent e)  { folded(e.getPath(), false); }
            @Override public void treeCollapsed(javax.swing.event.TreeExpansionEvent e) { folded(e.getPath(), true); }
        });
        savedTree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        javax.swing.ToolTipManager.sharedInstance().registerComponent(savedTree);
        savedTree.registerKeyboardAction(e -> {
                    if (selectedObject() instanceof LogNode ln) duplicate(ln);
                },
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_FOCUSED);
        savedTree.addTreeSelectionListener(e -> {
            if (rebuilding) return;
            Object picked = selectedObject();
            if (!open(picked)) putSelectionBack(e.getOldLeadSelectionPath());
        });
        // Selection is the theme's standard list highlight, as in menus and the Loggers tree;
        // only the log views use the blue reading selection.
        savedTree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && selectedObject() instanceof LogNode && actionBtn.isEnabled()) onConnect();
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

        JPanel side = new JPanel(new MigLayout("insets 24 16 16 16, fill, gapy 10", "[grow, fill][]", "[][grow, fill]"));
        side.add(savedFilter, "growx");
        side.add(newBtn, "wrap");
        side.add(savedBody, "span 2, h 120:100%:");
        side.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 10));
        side.setMinimumSize(new Dimension(SIDEBAR_MIN, 10));
        // A hairline divides the sidebar from the form, as panels are divided elsewhere.
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Separator.foreground")));
        return side;
    }

    /** A folder as its row shows it: the path, or ~ for a path relative to the home folder. */
    static String folderLabel(String path) {
        return path == null || path.isEmpty() ? "~" : path;
    }

    /** The key a folded connection or folder is remembered by; null for a log. */
    private static String foldKey(Object o) {
        if (o instanceof SavedFolder f) return "f:" + f.connKey() + "\n" + f.path();
        return o instanceof SshSaved.Connection c ? "c:" + c.key() : null;
    }

    private SshSaved.Connection editingConnection() {
        return editingKey == null ? null : SshSaved.find(connections, editingKey);
    }

    private Object selectedObject() {
        return savedTree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;
    }

    /**
     * Rebuilds the tree from the saved connections that match the filter. What the user folded
     * stays folded, except while filtering, when every match shows. The row for what the form
     * holds is selected; a folder row stays selected.
     */
    private void rebuildSavedTree() {
        Object selected = selectedObject();
        String q = savedFilter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        List<SshSaved.Connection> shown = new ArrayList<>(connections);
        shown.sort(java.util.Comparator.comparing(SshSaved.Connection::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SshSaved.Connection::key));
        savedRoot.removeAllChildren();
        DefaultMutableTreeNode toSelect = null;
        List<DefaultMutableTreeNode> conns = new ArrayList<>();
        for (SshSaved.Connection c : shown) {
            String who = (c.label() + " " + c.address()).toLowerCase(java.util.Locale.ROOT);
            boolean all = q.isEmpty() || who.contains(q);
            java.util.TreeMap<String, List<SshSaved.Log>> byFolder = new java.util.TreeMap<>(
                    String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()));
            for (SshSaved.Log l : c.logs()) {
                if (all || l.remotePath().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                    byFolder.computeIfAbsent(folder(l.remotePath()), k -> new ArrayList<>()).add(l);
                }
            }
            if (!all && byFolder.isEmpty()) continue;
            DefaultMutableTreeNode connNode = new DefaultMutableTreeNode(c);
            if (mode == Mode.CONNECTION && c.key().equals(editingKey)) toSelect = connNode;
            for (var folder : byFolder.entrySet()) {
                SavedFolder sf = new SavedFolder(c.key(), folder.getKey());
                DefaultMutableTreeNode folderNode = new DefaultMutableTreeNode(sf);
                if (sf.equals(selected)) toSelect = folderNode;
                folder.getValue().sort(java.util.Comparator.comparing((SshSaved.Log l) -> fileName(l.remotePath()),
                        String.CASE_INSENSITIVE_ORDER).thenComparing(SshSaved.Log::remotePath));
                for (SshSaved.Log l : folder.getValue()) {
                    DefaultMutableTreeNode n = new DefaultMutableTreeNode(new LogNode(c.key(), l));
                    folderNode.add(n);
                    if (mode == Mode.LOG && c.key().equals(editingKey) && editingLog != null
                            && l.remotePath().equals(editingLog.remotePath())) toSelect = n;
                }
                connNode.add(folderNode);
            }
            savedRoot.add(connNode);
            conns.add(connNode);
        }
        rebuilding = true;
        try {
            ((javax.swing.tree.DefaultTreeModel) savedTree.getModel()).reload();
            boolean filtering = !q.isEmpty();
            for (DefaultMutableTreeNode connNode : conns) {
                // Folders first: expanding one opens its connection too, and the connection folds
                // after, the tree keeping its folders' state for when it opens again.
                for (int i = 0; i < connNode.getChildCount(); i++) {
                    DefaultMutableTreeNode folderNode = (DefaultMutableTreeNode) connNode.getChildAt(i);
                    if (filtering || !collapsed.contains(foldKey(folderNode.getUserObject()))) {
                        savedTree.expandPath(new TreePath(folderNode.getPath()));
                    }
                }
                TreePath connPath = new TreePath(connNode.getPath());
                if (!filtering && collapsed.contains(foldKey(connNode.getUserObject()))) savedTree.collapsePath(connPath);
                else savedTree.expandPath(connPath);
            }
            if (toSelect != null) {
                TreePath p = new TreePath(toSelect.getPath());
                savedTree.setSelectionPath(p);
                savedTree.scrollPathToVisible(p);
            } else {
                savedTree.clearSelection();
            }
        } finally {
            rebuilding = false;
        }
        savedCards.show(savedBody, connections.isEmpty() ? "empty" : "list");
        savedFilter.setEnabled(!connections.isEmpty());
    }

    /** Remembers a connection or folder the user folded or opened (not while filtering). */
    private void folded(TreePath path, boolean folded) {
        if (rebuilding || !savedFilter.getText().isBlank()) return;
        String key = foldKey(((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject());
        if (key != null && (folded ? collapsed.add(key) : collapsed.remove(key))) AppPrefs.setSshCollapsed(collapsed);
    }

    /** Puts the list's selection back, without opening anything. */
    private void putSelectionBack(TreePath path) {
        rebuilding = true;
        try {
            if (path != null) savedTree.setSelectionPath(path);
            else savedTree.clearSelection();
        } finally {
            rebuilding = false;
        }
    }

    /**
     * Shows a row picked in the list in the form: a connection's details, or a log with its
     * connection. A folder changes nothing. Asks first when the form has unsaved changes; returns
     * false if the user chose to keep editing.
     */
    private boolean open(Object picked) {
        if (picked instanceof SshSaved.Connection c) {
            if (mode == Mode.CONNECTION && c.key().equals(editingKey)) return true;
            if (!confirmLeave()) return false;
            SshSaved.Connection now = SshSaved.find(connections, c.key());
            if (now == null) return true;
            editingKey = now.key();
            editingLog = null;
            showMode(Mode.CONNECTION);
            fillConnection(now);
        } else if (picked instanceof LogNode ln) {
            if (mode == Mode.LOG && ln.connKey().equals(editingKey) && editingLog != null
                    && editingLog.remotePath().equals(ln.log().remotePath())) return true;
            if (!confirmLeave()) return false;
            SshSaved.Connection now = SshSaved.find(connections, ln.connKey());
            if (now == null) return true;
            editingKey = now.key();
            editingLog = ln.log();
            fillConnection(now);
            showMode(Mode.LOG);
            fillLog(ln.log());
        }
        validateForm();
        return true;
    }

    /** The saved logs at or under a row: one log, a folder's, or a connection's. */
    private static List<LogNode> logsUnder(DefaultMutableTreeNode node) {
        List<LogNode> logs = new ArrayList<>();
        for (var e = node.depthFirstEnumeration(); e.hasMoreElements(); ) {
            if (((DefaultMutableTreeNode) e.nextElement()).getUserObject() instanceof LogNode ln) logs.add(ln);
        }
        return logs;
    }

    /**
     * Forgets a saved log, a folder's logs, or a connection with its logs, asking first when
     * that's more than one thing (a connection with logs, a folder with several). A form showing
     * what was forgotten goes back to a new connection.
     */
    private void forget(DefaultMutableTreeNode node) {
        Object o = node.getUserObject();
        List<LogNode> logs = logsUnder(node);
        if (o instanceof SshSaved.Connection c) {
            SshSaved.Connection now = SshSaved.find(connections, c.key());
            int n = now == null ? 0 : now.logs().size();
            if (n > 0 && !confirmForget(Messages.format("ssh.dialog.saved.forget.connection.confirm", n, c.label()))) return;
            connections.removeIf(x -> x.key().equals(c.key()));
        } else {
            if (logs.isEmpty()) return;
            if (o instanceof SavedFolder sf && logs.size() > 1
                    && !confirmForget(Messages.format("ssh.dialog.saved.forget.folder.confirm", logs.size(), folderLabel(sf.path())))) {
                return;
            }
            for (LogNode ln : logs) {
                replace(ln.connKey(), c -> c.withLogs(c.logs().stream()
                        .filter(l -> !l.remotePath().equals(ln.log().remotePath())).toList()));
            }
        }
        AppPrefs.saveSshConnections(connections);
        // Nor does it keep the folding of connections and folders that are gone.
        java.util.Set<String> live = new java.util.HashSet<>();
        for (SshSaved.Connection c : connections) {
            live.add(foldKey(c));
            for (SshSaved.Log l : c.logs()) live.add(foldKey(new SavedFolder(c.key(), folder(l.remotePath()))));
        }
        if (collapsed.retainAll(live)) AppPrefs.setSshCollapsed(collapsed);
        boolean gone = editingKey != null && (editingConnection() == null
                || (mode == Mode.LOG && editingLog != null && editingConnection().logs().stream()
                        .noneMatch(l -> l.remotePath().equals(editingLog.remotePath()))));
        if (gone) clearForm();
        rebuildSavedTree();
        validateForm();
    }

    private boolean confirmForget(String message) {
        return JOptionPane.showConfirmDialog(this, message, Messages.get("ssh.dialog.saved.forget.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /** Replaces the saved connection with key {@code key} by {@code change} applied to it (not saved yet). */
    private void replace(String key, java.util.function.UnaryOperator<SshSaved.Connection> change) {
        for (int i = 0; i < connections.size(); i++) {
            if (connections.get(i).key().equals(key)) {
                connections.set(i, change.apply(connections.get(i)));
                return;
            }
        }
    }

    /** The problem with naming the connection {@code key} {@code name}: another connection has that name; else null. */
    private String renameProblem(String key, String name) {
        return SshSaved.named(connections, name, key) != null ? Messages.get("ssh.dialog.saved.rename.duplicate") : null;
    }

    /** Names a connection on its row, or forgets its name when {@code name} is blank. */
    private void renameConnection(String key, String name) {
        SshSaved.Connection c = SshSaved.find(connections, key);
        if (c == null || Objects.equals(c.name(), name.isBlank() ? null : name.strip()) || renameProblem(key, name) != null) return;
        replace(key, x -> x.withName(name));
        AppPrefs.saveSshConnections(connections);
        // A form showing it shows the new name, without counting it as an unsaved change.
        if (key.equals(editingKey)) {
            selfChange = true;
            try {
                nameField.setText(name.strip());
            } finally {
                selfChange = false;
            }
            if (mode == Mode.LOG) showMode(Mode.LOG);
        }
        // After the editor has closed: the connection may move, since connections sort by name.
        SwingUtilities.invokeLater(() -> { rebuildSavedTree(); validateForm(); });
    }

    /**
     * The row's right-click menu: Rename and New Log… on a connection, Duplicate on a log, then
     * Forget, which names what it forgets. The row is selected first, as in any list.
     */
    private void showSavedMenu(java.awt.event.MouseEvent e) {
        int row = savedTree.getClosestRowForLocation(e.getX(), e.getY());
        Rectangle r = row >= 0 ? savedTree.getRowBounds(row) : null;
        if (r == null || e.getY() < r.y || e.getY() >= r.y + r.height) return;
        savedTree.setSelectionRow(row);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) savedTree.getPathForRow(row).getLastPathComponent();
        Object o = node.getUserObject();
        JPopupMenu menu = new JPopupMenu();
        if (o instanceof SshSaved.Connection c) {
            JMenuItem rename = new JMenuItem(Messages.get("ssh.dialog.saved.menu.rename"));
            rename.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F2, 0));
            rename.addActionListener(a -> savedTree.startEditingAtPath(savedTree.getPathForRow(row)));
            menu.add(rename);
            JMenuItem newLog = new JMenuItem(Messages.get("ssh.dialog.saved.menu.newlog"));
            newLog.addActionListener(a -> newLog(c.key(), ""));
            menu.add(newLog);
            menu.addSeparator();
        } else if (o instanceof LogNode ln) {
            JMenuItem dup = new JMenuItem(Messages.get("ssh.dialog.saved.menu.duplicate"));
            dup.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_DOWN_MASK));
            dup.addActionListener(a -> duplicate(ln));
            menu.add(dup);
            menu.addSeparator();
        }
        String key = o instanceof SshSaved.Connection ? "ssh.dialog.saved.menu.forget.connection"
                   : o instanceof SavedFolder ? "ssh.dialog.saved.menu.forget.folder"
                   : "ssh.dialog.saved.menu.forget";
        // "…" when it will ask first.
        boolean asks = o instanceof SshSaved.Connection c2 ? !c2.logs().isEmpty() : logsUnder(node).size() > 1;
        JMenuItem forget = new JMenuItem(Messages.get(key) + (asks ? "…" : ""));
        forget.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0));
        forget.addActionListener(a -> forget(node));
        menu.add(forget);
        menu.show(savedTree, e.getX(), e.getY());
    }

    /**
     * Renames a connection on its row: its name in a field (empty shows its address as a hint),
     * Enter saves, Esc cancels. A name another connection has is refused, with the field outlined in red
     * and the reason on a line under it, which the row keeps while renaming. Only F2 and the row's Rename start it, never a click.
     */
    private final class ConnectionNameEditor extends AbstractCellEditor implements javax.swing.tree.TreeCellEditor {
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
        private String connKey;
        private DefaultMutableTreeNode node;

        ConnectionNameEditor() {
            panel.setOpaque(false);
            panel.add(field, BorderLayout.NORTH);
            panel.add(error, BorderLayout.SOUTH);
            field.addActionListener(e -> stopCellEditing());
            field.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e)  { showProblem(renameProblem(connKey, field.getText())); }
                public void removeUpdate(DocumentEvent e)  { showProblem(renameProblem(connKey, field.getText())); }
                public void changedUpdate(DocumentEvent e) { showProblem(renameProblem(connKey, field.getText())); }
            });
        }

        @Override public boolean isCellEditable(java.util.EventObject e) {
            return e == null;   // F2 and Rename start it with no event; clicks never do
        }

        @Override public Component getTreeCellEditorComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row) {
            node = (DefaultMutableTreeNode) value;
            SshSaved.Connection c = (SshSaved.Connection) node.getUserObject();
            connKey = c.key();
            error.setFont(tree.getFont().deriveFont(tree.getFont().getSize2D() * 0.92f));
            error.setForeground(ClouseauColors.statusError());
            field.setText(c.name() != null ? c.name() : "");
            field.putClientProperty("JTextField.placeholderText", c.address());
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
            String problem = renameProblem(connKey, field.getText());
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
     * the row under the mouse: on a log, a duplicate icon and a trash can; on a connection or a
     * folder, a trash can that forgets it. A press on a tool doesn't select the row.
     */
    private final class SavedTree extends JTree {
        static final int TOOL = 24;                // width of each tool's hit area
        private enum Tool { NONE, DUPLICATE, TRASH }
        private int hoverRow = -1;
        private Tool overTool = Tool.NONE;

        SavedTree(DefaultMutableTreeNode root) {
            super(root);
            // A rename comes back here as the edited text: it names the connection, not the node.
            setModel(new javax.swing.tree.DefaultTreeModel(root) {
                @Override public void valueForPathChanged(javax.swing.tree.TreePath path, Object newValue) {
                    if (((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof SshSaved.Connection c) {
                        renameConnection(c.key(), String.valueOf(newValue));
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

        /** Only a connection's row can be edited: renaming it. */
        @Override public boolean isPathEditable(javax.swing.tree.TreePath path) {
            return isEditable() && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof SshSaved.Connection;
        }

        private boolean isFolder(int row) {
            javax.swing.tree.TreePath path = row >= 0 ? getPathForRow(row) : null;
            return path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof SavedFolder;
        }

        private boolean isLog(int row) {
            javax.swing.tree.TreePath path = row >= 0 ? getPathForRow(row) : null;
            return path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()
                    instanceof LogNode;
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
                        else if (node.getUserObject() instanceof LogNode ln) duplicate(ln);
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
                                          : Messages.get("ssh.dialog.saved.forget.connection.tooltip");
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
     * Three kinds of row. A connection: its name in bold with its address dimmed beside it, or
     * its address in bold if it has no name ({@code user@host}, with {@code :port} when it isn't
     * 22), with more room above and below. A folder: its path, dimmed. A log: its file name.
     * Each row keeps room on its right for the row's tools (trash can, and duplicate on a log),
     * and the tree is never narrower than its widest row, so the tools, drawn at the tree's
     * right edge, never cover a name; a sidebar too narrow for a row scrolls sideways.
     */
    private static final class SavedRenderer extends JPanel implements javax.swing.tree.TreeCellRenderer {
        /** Room kept free on the right for the tools: two on a log, one on a connection or folder. */
        private static final int LOG_TOOLS = 2 * SavedTree.TOOL + 4, GROUP_TOOLS = SavedTree.TOOL + 4;
        private final javax.swing.tree.DefaultTreeCellRenderer colors = new javax.swing.tree.DefaultTreeCellRenderer();
        private final JLabel name = new JLabel(), address = new JLabel();

        SavedRenderer() {
            super(new BorderLayout(8, 0));
            setOpaque(false);
            add(name, BorderLayout.WEST);
            add(address, BorderLayout.CENTER);
        }

        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            colors.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            Color fg = colors.getForeground();
            Object o = value instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;
            // A connection is the level above everything under it: bold. Weight, not a fill:
            // only the selected row is filled.
            boolean connection = o instanceof SshSaved.Connection;
            name.setFont(connection ? tree.getFont().deriveFont(Font.BOLD) : tree.getFont());
            name.setForeground(fg);
            address.setFont(tree.getFont());
            address.setForeground(selected ? fg : ClouseauColors.dimForeground());
            address.setVisible(false);
            setToolTipText(null);
            if (o instanceof LogNode ln) {
                name.setText(fileName(ln.log().remotePath()));
                setToolTipText(ln.log().remotePath());
            } else if (o instanceof SavedFolder sf) {
                // Dim, so the eye goes from the connection to the logs, which are what you pick.
                name.setText(folderLabel(sf.path()));
                name.setForeground(selected ? fg : ClouseauColors.dimForeground());
            } else if (o instanceof SshSaved.Connection c) {
                name.setText(c.label());
                address.setText(c.address());
                address.setVisible(c.name() != null);
            } else {
                name.setText(o == null ? "" : o.toString());
            }
            // Connections get more room above and below, so each one starts a block. The room is
            // even on both sides, so a selected connection's fill stays centered on its text.
            int pad = connection ? 6 : 3;
            setBorder(BorderFactory.createEmptyBorder(pad, 0, pad, o instanceof LogNode ? LOG_TOOLS : GROUP_TOOLS));
            return this;
        }
    }

    // ── The form ──────────────────────────────────────────────────────────────

    /** Fills the Connection section from a saved connection (or a session's). */
    private void fillConnection(SshSaved.Connection c) {
        selfChange = true;
        try {
            nameField.setText(c.name() != null ? c.name() : "");
            hostField.setText(c.host());
            portField.setText(String.valueOf(c.port()));
            userField.setText(c.user());
            passwordField.setText(c.password() != null ? c.password() : "");
            keyFileField.setText(c.keyFile() != null ? c.keyFile() : "");
            keyPassphraseField.setText(c.keyPassphrase() != null ? c.keyPassphrase() : "");
            switchAuth(AUTH_KEY.equals(c.authType()) || AUTH_NONE.equals(c.authType()) ? c.authType() : AUTH_PASSWORD);
        } finally {
            selfChange = false;
        }
    }

    /** Fills the Log section from a saved log. */
    private void fillLog(SshSaved.Log l) {
        selfChange = true;
        try {
            remotePathField.setText(l.remotePath());
            parserCombo.setSelectedIndex(0);
            if (l.parserName() != null) {
                for (int i = 0; i < parsers.size(); i++) {
                    if (parsers.get(i).getName().equals(l.parserName())) {
                        parserCombo.setSelectedIndex(i + 1);
                        break;
                    }
                }
            }
        } finally {
            selfChange = false;
        }
    }

    /** Back to an empty New connection form, with nothing picked in the list. */
    private void clearForm() {
        editingKey = null;
        editingLog = null;
        showMode(Mode.NEW);
        fillConnection(new SshSaved.Connection(null, "", SshConfig.DEFAULT_PORT, "", AUTH_PASSWORD,
                null, null, null, List.of()));
        fillLog(new SshSaved.Log("", null));
        saveBox.setSelected(AppPrefs.isSshSaveConnection());
        putSelectionBack(null);
    }

    /** + or Ctrl+N: an empty form for a new connection, after asking about unsaved changes. */
    private void newConnection() {
        if (!confirmLeave()) return;
        clearForm();
        validateForm();
        hostField.requestFocusInWindow();
    }

    /** New Log… on a connection: its Log section with {@code path} in Remote path, ready to type. */
    private void newLog(String connKey, String path) {
        if (!confirmLeave()) return;
        SshSaved.Connection c = SshSaved.find(connections, connKey);
        if (c == null) return;
        editingKey = c.key();
        editingLog = null;
        fillConnection(c);
        showMode(Mode.LOG);
        fillLog(new SshSaved.Log(path, null));
        putSelectionBack(null);
        validateForm();
        remotePathField.requestFocusInWindow();
        int slash = path.lastIndexOf('/');
        remotePathField.select(slash + 1, path.length());
    }

    /**
     * Duplicate on a log: a new log on the same connection, Remote path filled in with the file
     * name selected, ready to type another file. Connect saves it as a new entry.
     */
    private void duplicate(LogNode ln) {
        newLog(ln.connKey(), ln.log().remotePath());
    }

    /** The parser chosen in the form, or none for Auto-detect. */
    private Optional<LogParser> chosenParser() {
        int i = parserCombo.getSelectedIndex();
        return i > 0 ? Optional.of(parsers.get(i - 1)) : Optional.empty();
    }

    /**
     * The connection the Connection section describes right now, read without touching the
     * fields (a {@code user@host:port} still in Host counts as split).
     */
    private SshSaved.Connection formConnection() {
        String typed = hostField.getText().trim();
        HostSpec spec = parseHostSpec(typed);
        String host = spec != null ? spec.host() : typed;
        String user = spec != null && spec.user() != null ? spec.user() : userField.getText().trim();
        int port = spec != null && spec.port() != null ? spec.port()
                 : validPort(portField.getText()) ? port() : SshConfig.DEFAULT_PORT;
        String authType = AUTH_TYPES[authCombo.getSelectedIndex()];
        return switch (authType) {
            case AUTH_KEY  -> new SshSaved.Connection(nameField.getText(), host, port, user, AUTH_KEY,
                    null, keyFileField.getText().trim(), new String(keyPassphraseField.getPassword()), List.of());
            case AUTH_NONE -> new SshSaved.Connection(nameField.getText(), host, port, user, AUTH_NONE,
                    null, null, null, List.of());
            default        -> new SshSaved.Connection(nameField.getText(), host, port, user, AUTH_PASSWORD,
                    new String(passwordField.getPassword()), null, null, List.of());
        };
    }

    /** The log the Log section describes right now. */
    private SshSaved.Log formLog() {
        return new SshSaved.Log(remotePathField.getText().trim(), chosenParser().map(LogParser::getName).orElse(null));
    }

    /** Whether the form has changes to the saved connection or log it holds (a new one has none to lose). */
    private boolean isEdited() {
        SshSaved.Connection saved = editingConnection();
        if (mode == Mode.CONNECTION && saved != null) {
            SshSaved.Connection f = formConnection();
            return !(Objects.equals(f.name(), saved.name()) && f.key().equals(saved.key())
                    && f.host().equals(saved.host()) && f.sameSignIn(saved));
        }
        if (mode == Mode.LOG && saved != null) {
            SshSaved.Log f = formLog();
            return editingLog == null ? !f.remotePath().isEmpty() : !f.equals(editingLog);
        }
        return false;
    }

    /**
     * Saves the Connection section to the saved connection the form holds, its logs kept: Save in
     * Connection mode. Refused, with the reason in the notice line and the field outlined, when
     * another saved connection has the same user, host and port (a connection is those three) or
     * the same name. Returns whether it saved.
     */
    private boolean saveConnection() {
        SshSaved.Connection saved = editingConnection();
        if (saved == null) return false;
        selfChange = true;
        try {
            applyHostSpec();
        } finally {
            selfChange = false;
        }
        SshSaved.Connection f = formConnection();
        if (!f.key().equals(saved.key())) {
            SshSaved.Connection other = SshSaved.find(connections, f.key());
            if (other != null) {
                showClash(other.name() != null
                        ? Messages.format("ssh.dialog.clash.address.named", f.address(), other.name())
                        : Messages.format("ssh.dialog.clash.address", f.address()), hostField);
                return false;
            }
        }
        SshSaved.Connection named = SshSaved.named(connections, f.name(), saved.key());
        if (named != null) {
            showClash(Messages.format("ssh.dialog.clash.name", named.name()), nameField);
            return false;
        }
        SshSaved.Connection updated = saved.withSignIn(f).withName(f.name());
        replace(saved.key(), x -> updated);
        AppPrefs.saveSshConnections(connections);
        if (!updated.key().equals(saved.key())) moveFolds(saved.key(), updated.key());
        editingKey = updated.key();
        rebuildSavedTree();
        validateForm();
        hintLabel.show(Messages.format("ssh.dialog.saved.ok", updated.label()), NoticeLabel.Kind.OK);
        return true;
    }

    /** Says why a save was refused, and outlines the field to fix. */
    private void showClash(String message, JComponent field) {
        hintLabel.show(message, NoticeLabel.Kind.ERROR);
        field.putClientProperty("JComponent.outline", "error");
        field.requestFocusInWindow();
    }

    /** Keeps a connection's folding when its key changes (its user, host or port was edited). */
    private void moveFolds(String oldKey, String newKey) {
        java.util.Set<String> moved = new java.util.HashSet<>();
        for (String k : collapsed) {
            if (k.equals("c:" + oldKey)) moved.add("c:" + newKey);
            else if (k.startsWith("f:" + oldKey + "\n")) moved.add("f:" + newKey + k.substring(("f:" + oldKey).length()));
            else moved.add(k);
        }
        collapsed.clear();
        collapsed.addAll(moved);
        AppPrefs.setSshCollapsed(collapsed);
    }

    /** Saves the Log section to the connection the form holds, replacing the log it holds if any. */
    private void saveLog() {
        SshSaved.Connection saved = editingConnection();
        if (saved == null) return;
        SshSaved.Log log = formLog();
        SshSaved.Log was = editingLog;
        replace(saved.key(), c -> {
            List<SshSaved.Log> logs = c.logs();
            if (was != null && !was.remotePath().equals(log.remotePath())) {
                logs = logs.stream().filter(l -> !l.remotePath().equals(was.remotePath())).toList();
            }
            return c.withLogs(SshSaved.withLog(logs, log));
        });
        AppPrefs.saveSshConnections(connections);
        editingLog = log;
        rebuildSavedTree();
    }

    /** Ctrl+S: saves what the form holds without connecting, when there's something to save. */
    private void saveWithoutConnecting() {
        if (mode == Mode.CONNECTION && actionBtn.isEnabled()) {
            saveConnection();
        } else if (mode == Mode.LOG && isEdited() && !remotePathField.getText().isBlank()) {
            saveLog();
            validateForm();
            hintLabel.show(Messages.format("ssh.dialog.saved.ok", fileName(editingLog.remotePath())), NoticeLabel.Kind.OK);
        }
    }

    /**
     * Before the form drops unsaved changes to a saved connection or log (another row picked, +,
     * Cancel, Esc, the close box): asks Cancel · Don't Save · Save. Returns whether to go on. Save
     * is offered only when the form could be saved.
     */
    private boolean confirmLeave() {
        if (!isEdited()) return true;
        boolean canSave = mode == Mode.CONNECTION ? actionBtn.isEnabled() : !remotePathField.getText().isBlank();
        String what = mode == Mode.CONNECTION
                ? Objects.requireNonNullElse(editingConnection(), formConnection()).label()
                : fileName(editingLog != null ? editingLog.remotePath() : remotePathField.getText());
        Object[] options = canSave
                ? new Object[]{ Messages.get("ssh.dialog.unsaved.save"), Messages.get("ssh.dialog.unsaved.discard"),
                                Messages.get("ssh.dialog.unsaved.keep") }
                : new Object[]{ Messages.get("ssh.dialog.unsaved.discard"), Messages.get("ssh.dialog.unsaved.keep") };
        int answer = JOptionPane.showOptionDialog(this, Messages.format("ssh.dialog.unsaved.message", what),
                Messages.get("ssh.dialog.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (canSave && answer == 0) {
            if (mode == Mode.CONNECTION) return saveConnection();
            saveLog();
            return true;
        }
        return answer == (canSave ? 1 : 0);
    }

    /** Cancel, Esc or the close box: closes, after asking about unsaved changes. */
    private void requestClose() {
        if (confirmLeave()) dispose();
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

    /**
     * Connects, after checking: signs in and checks the file with {@code test} on the server (each
     * step waits at most {@link SshRemote#TIMEOUT_SECONDS}), and closes only when that works. If
     * it doesn't, the dialog stays open with the step that failed and why.
     *
     * <p>A log on a saved connection is saved first, so its details aren't lost if the check
     * fails. A new connection is kept only when "Save this connection" is ticked, and only once
     * the check passes; if a connection with the same user, host and port is saved already, the
     * log is added to it (see {@link #keepNew}).
     */
    private void onConnect() {
        cancelTest();
        Optional<LogParser> parser = chosenParser();
        SshConfig config;
        String authType;
        SshSaved.Connection typed = null;
        if (mode == Mode.LOG) {
            SshSaved.Connection c = editingConnection();
            if (c == null) return;
            saveLog();
            config = c.config(remotePathField.getText().trim());
            authType = c.authType();
        } else {
            config = formConfig();
            authType = AUTH_TYPES[authCombo.getSelectedIndex()];
            typed = formConnection();
            if (saveBox.isSelected()) {
                SshSaved.Connection existing = SshSaved.find(connections, typed.key());
                SshSaved.Connection named = SshSaved.named(connections, typed.name(),
                        existing != null ? existing.key() : typed.key());
                if (named != null) {
                    showClash(Messages.format("ssh.dialog.clash.name", named.name()), nameField);
                    return;
                }
            }
        }
        SshSaved.Connection newConnection = typed;
        SshSaved.Log log = formLog();
        SshRemote remote = new SshRemote(config);
        testRemote = remote;
        validateForm();
        hintLabel.setText(Messages.format("ssh.connect.checking", config.host()));
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
                if (testRemote != remote) return;   // stopped: the form changed or the dialog closed
                testRemote = null;
                try {
                    get();
                } catch (java.util.concurrent.ExecutionException ex) {
                    validateForm();
                    Throwable cause = ex.getCause();
                    String text = cause instanceof SshRemote.Failure f
                            ? describe(f, config, authType)
                            : String.valueOf(cause.getMessage());
                    hintLabel.show(text, NoticeLabel.Kind.ERROR);
                    hintLabel.setToolTipText(cause.getMessage());
                    return;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (newConnection != null) {
                    AppPrefs.setSshSaveConnection(saveBox.isSelected());
                    if (saveBox.isSelected()) keepNew(newConnection, log);
                }
                result = new Result(config, parser);
                dispose();
            }
        }.execute();
    }

    /**
     * Saves a new connection that just connected, with its log. If one with the same user, host
     * and port is saved already, it's that connection again: the log is added to it, and if it
     * signs in another way (another method, a new password), the user is asked whether to update
     * the saved sign-in.
     */
    private void keepNew(SshSaved.Connection typed, SshSaved.Log log) {
        SshSaved.Connection existing = SshSaved.find(connections, typed.key());
        if (existing == null) {
            connections.add(typed.withLogs(List.of(log)));
        } else {
            SshSaved.Connection merged = existing;
            if (!existing.sameSignIn(typed)) {
                Object[] options = { Messages.get("ssh.dialog.update.signin.update"),
                                     Messages.get("ssh.dialog.update.signin.keep") };
                int answer = JOptionPane.showOptionDialog(this,
                        Messages.format("ssh.dialog.update.signin.message", existing.label()),
                        Messages.get("ssh.dialog.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                        null, options, options[0]);
                if (answer == 0) merged = existing.withSignIn(typed);
            }
            if (merged.name() == null && typed.name() != null) merged = merged.withName(typed.name());
            SshSaved.Connection done = merged.withLogs(SshSaved.withLog(merged.logs(), log));
            replace(existing.key(), x -> done);
        }
        AppPrefs.saveSshConnections(connections);
    }

    // ── Browse the server (G-13) ──────────────────────────────────────────────

    /**
     * Lets the user pick Remote path from the server's folders, signed in with the form's details,
     * or a log's saved connection.
     */
    private void browseRemote() {
        SshSaved.Connection saved = mode == Mode.LOG ? editingConnection() : null;
        SshConfig config = saved != null ? saved.config(remotePathField.getText().trim()) : formConfig();
        String authType = saved != null ? saved.authType() : AUTH_TYPES[authCombo.getSelectedIndex()];
        new RemoteFileDialog(this, config, authType)
                .showDialog()
                .ifPresent(path -> {
                    remotePathField.setText(path);
                    remotePathField.requestFocusInWindow();
                });
    }

    // ── Checking before connecting (G-14) ─────────────────────────────────────

    /** A field changed: a check of the old details no longer says anything, so it stops and its result goes. */
    private void formChanged() {
        if (selfChange) return;
        cancelTest();
        hintLabel.setToolTipText(null);
        validateForm();
    }

    /** Stops the running check, if any; its result is then ignored. */
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
