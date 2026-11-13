package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.SshConfig;
import com.droidenx.clouseau.core.SshRemote;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.ui.theme.ClouseauFonts;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Picks a log on the server for the SSH dialog's Remote path (G-13). Signs in with the
 * dialog's details, then lists folders over SFTP: folders first, then the files the user can
 * read; files the user can't read are left out and counted under the list. Starts in the
 * folder of the path already typed, else {@code /var/log}, else the home folder.
 *
 * <p>Every call to the server runs on the dialog's own thread, one at a time; a reply to a
 * folder the user has already left is ignored. Closing the dialog closes the connection.
 */
@Slf4j
final class RemoteFileDialog extends JDialog {

    private static final DateTimeFormatter MODIFIED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SshConfig config;
    private final String authType;
    private final SshRemote remote;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ssh-browse");
        t.setDaemon(true);
        return t;
    });

    private final JButton upBtn = new JButton(Messages.get("ssh.browse.up"));
    private final JTextField pathField = new JTextField(30);
    private final EntryModel model = new EntryModel();
    private final JTable table = new JTable(model);
    private final NoticeLabel status = new NoticeLabel();
    private final JButton chooseBtn = new JButton(Messages.get("ssh.browse.choose"));

    /** The folder shown, absolute; null until the first listing arrives. */
    private String current;
    /** Counts requests, so only the latest one's reply is shown. */
    private int request;
    private boolean signedIn;
    private String result;

    RemoteFileDialog(Window owner, SshConfig config, String authType) {
        super(owner, Messages.format("ssh.browse.title", config.host()), ModalityType.APPLICATION_MODAL);
        this.config = config;
        this.authType = authType;
        this.remote = new SshRemote(config);
        setContentPane(buildContent());
        getRootPane().setDefaultButton(chooseBtn);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setBusy(true);
        pack();
        setSize(Math.max(getWidth(), 640), Math.max(getHeight(), 460));
        setLocationRelativeTo(owner);
        signIn();
    }

    /** Shows the dialog; returns the chosen file's absolute path, or empty if cancelled. */
    Optional<String> showDialog() {
        setVisible(true);
        return Optional.ofNullable(result);
    }

    @Override public void dispose() {
        request++;
        worker.shutdownNow();
        SshConnectionDialog.closeQuietly(remote);
        super.dispose();
    }

    private JPanel buildContent() {
        JPanel p = new JPanel(new MigLayout("insets 16 16 12 16, fill, gapy 10", "[grow, fill]", "[][grow, fill][]"));

        pathField.putClientProperty("JTextField.placeholderText", "/var/log");
        pathField.setFont(ClouseauFonts.mono(Font.PLAIN, pathField.getFont().getSize()));
        pathField.addActionListener(e -> {
            String typed = pathField.getText().strip();
            if (!typed.isEmpty()) open(typed);
        });
        upBtn.setToolTipText(Messages.get("ssh.browse.up.tooltip"));
        upBtn.addActionListener(e -> up());
        p.add(upBtn, "split 2, growx 0");
        p.add(pathField, "growx, wrap");

        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setRowHeight(AppPrefs.getRowHeight());
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(1).setCellRenderer(right);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(130);
        table.getSelectionModel().addListSelectionListener(e -> updateChoose());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) activate();
            }
        });
        // Enter opens a folder or chooses a file; Backspace goes up, as in a file manager.
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "activate");
        table.getActionMap().put("activate", action(this::activate));
        table.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "up");
        table.getActionMap().put("up", action(this::up));
        getRootPane().registerKeyboardAction(e -> up(),
                KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.ALT_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        p.add(ClouseauColors.noFocusFrame(new JScrollPane(table)), "grow, wrap, h 200:100%:");

        p.add(status, "growx 0");

        chooseBtn.addActionListener(e -> activate());
        JButton cancel = new JButton(Messages.get("ssh.dialog.cancel"));
        cancel.addActionListener(e -> dispose());

        JPanel root = new JPanel(new BorderLayout());
        root.add(p, BorderLayout.CENTER);
        root.add(DialogFooter.of(cancel, chooseBtn), BorderLayout.SOUTH);
        return root;
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        };
    }

    // ── Server calls ──────────────────────────────────────────────────────────

    /** Signs in and lists the folder to start in. */
    private void signIn() {
        status.setText(Messages.format("ssh.test.running", config.host()));
        String typed = config.remotePath();
        run(() -> {
            remote.connect();
            return remote.list(remote.startFolder(typed));
        }, listing -> {
            signedIn = true;
            show(listing, SshConnectionDialog.fileName(typed));
        }, e -> {
            status.show(e instanceof SshRemote.Failure f
                    ? SshConnectionDialog.describe(f, config, authType)
                    : String.valueOf(e.getMessage()), NoticeLabel.Kind.ERROR);
            status.setToolTipText(e.getMessage());
        });
    }

    /** Lists {@code folder} (absolute, or from the home folder); the list stays as it was if that fails. */
    private void open(String folder) {
        if (!signedIn) return;
        status.setText(Messages.get("ssh.browse.loading"));
        run(() -> remote.list(folder), listing -> show(listing, null), e -> {
            setBusy(false);
            if (current != null) pathField.setText(current);
            status.show(Messages.format("ssh.browse.cant.open", folder, e.getMessage()), NoticeLabel.Kind.ERROR);
        });
    }

    private interface Call<T> { T call() throws Exception; }

    /** Runs {@code call} on the dialog's thread and hands its result or failure to the EDT, if still wanted. */
    private <T> void run(Call<T> call, Consumer<T> onResult, Consumer<Exception> onFailure) {
        int id = ++request;
        setBusy(true);
        try {
            worker.execute(() -> {
                try {
                    T value = call.call();
                    SwingUtilities.invokeLater(() -> { if (id == request) onResult.accept(value); });
                } catch (Exception e) {
                    if (id == request) log.info("SSH browse {}@{}: {}", config.user(), config.host(), e.toString());
                    SwingUtilities.invokeLater(() -> { if (id == request) onFailure.accept(e); });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // the dialog is closing
        }
    }

    // ── View ──────────────────────────────────────────────────────────────────

    /** Shows {@code listing}, selecting {@code select} if it's one of its files (else the first row). */
    private void show(SshRemote.Listing listing, String select) {
        current = listing.path();
        pathField.setText(current);
        List<SshRemote.Entry> rows = new ArrayList<>(listing.folders());
        rows.addAll(listing.files());
        model.set(rows);
        setBusy(false);
        int at = 0;
        for (int i = 0; select != null && i < rows.size(); i++) {
            if (!rows.get(i).folder() && rows.get(i).name().equals(select)) at = i;
        }
        if (!rows.isEmpty()) {
            table.setRowSelectionInterval(at, at);
            table.scrollRectToVisible(table.getCellRect(at, 0, true));
        }
        table.requestFocusInWindow();
        if (listing.unreadable() > 0) {
            status.setText(Messages.format("ssh.browse.hidden", listing.unreadable(), config.user()));
        } else if (rows.isEmpty()) {
            status.setText(Messages.get("ssh.browse.empty"));
        } else {
            status.setText(" ");
        }
        status.setToolTipText(null);
    }

    private void setBusy(boolean busy) {
        table.setEnabled(!busy);
        pathField.setEnabled(!busy || signedIn);
        upBtn.setEnabled(!busy && current != null && !current.equals("/"));
        updateChoose();
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
    }

    private SshRemote.Entry selected() {
        int row = table.getSelectedRow();
        return row >= 0 && table.isEnabled() ? model.rows.get(row) : null;
    }

    /** Choose is for a file; on a folder, the default button opens it instead. */
    private void updateChoose() {
        SshRemote.Entry e = selected();
        chooseBtn.setText(Messages.get(e != null && e.folder() ? "ssh.browse.open" : "ssh.browse.choose"));
        chooseBtn.setEnabled(e != null);
    }

    /** Opens the selected folder, or chooses the selected file. */
    private void activate() {
        SshRemote.Entry e = selected();
        if (e == null) return;
        if (e.folder()) {
            open(e.path());
        } else {
            result = e.path();
            dispose();
        }
    }

    private void up() {
        if (current == null || current.equals("/") || !table.isEnabled()) return;
        String parent = current.substring(0, Math.max(current.lastIndexOf('/'), 0));
        open(parent.isEmpty() ? "/" : parent);
    }

    private static final class EntryModel extends AbstractTableModel {
        private List<SshRemote.Entry> rows = List.of();

        void set(List<SshRemote.Entry> rows) {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        @Override public int getRowCount()    { return rows.size(); }
        @Override public int getColumnCount() { return 3; }

        @Override public String getColumnName(int col) {
            return Messages.get(switch (col) {
                case 0 -> "ssh.browse.col.name";
                case 1 -> "ssh.browse.col.size";
                default -> "ssh.browse.col.modified";
            });
        }

        @Override public Object getValueAt(int row, int col) {
            SshRemote.Entry e = rows.get(row);
            return switch (col) {
                case 0 -> e;
                case 1 -> e.folder() ? "" : ZipPickerDialog.formatSize(e.size());
                default -> e.modified().getEpochSecond() > 0 ? MODIFIED.format(e.modified()) : "";
            };
        }
    }

    /** A folder or file icon, then the name. */
    private static final class NameRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                boolean focus, int row, int col) {
            SshRemote.Entry e = (SshRemote.Entry) value;
            super.getTableCellRendererComponent(t, e.name(), selected, focus, row, col);
            setIcon(UIManager.getIcon(e.folder() ? "FileView.directoryIcon" : "FileView.fileIcon"));
            setIconTextGap(6);
            return this;
        }
    }
}
