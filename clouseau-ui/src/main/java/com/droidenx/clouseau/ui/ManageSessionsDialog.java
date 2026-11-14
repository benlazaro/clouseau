package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.core.SessionStore;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;

/**
 * File → Sessions → Manage Sessions… (UC-17): the named sessions, to open, rename or
 * delete. Renaming or deleting the session in use updates the window titles.
 * <p>
 * A manager like the others: the list's actions on the footer's left, Delete removes after
 * asking, Close is plain, and the size is remembered. Rename (F2, the button or the row's menu)
 * edits the name in place: Enter saves, Esc cancels, and a name another session has is refused
 * with the reason under the field.
 */
@Slf4j
final class ManageSessionsDialog extends JDialog {

    private final WindowManager manager;
    private final DefaultListModel<SessionStore.Named> model = new DefaultListModel<>();
    private final JList<SessionStore.Named> list = new JList<>(model);
    private final JButton openButton = new JButton(Messages.get("session.manage.open"));
    private final JButton renameButton = new JButton(Messages.get("session.manage.rename"));
    private final JButton deleteButton = new JButton(Messages.get("session.manage.delete"));

    /** The in-place name editor, laid over the row being renamed. */
    private final JTextField renameField = new JTextField();
    /** Why the typed name can't be used, just under the editor. */
    private final NoticeLabel renameNotice = new NoticeLabel();
    private final JPanel renameNoticeHolder = new JPanel(new BorderLayout());
    /** The session being renamed, or null. */
    private SessionStore.Named renaming;

    ManageSessionsDialog(Frame owner, WindowManager manager) {
        super(owner, Messages.get("session.manage.title"), true);
        this.manager = manager;

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(26);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                                    boolean selected, boolean focus) {
                String name = ((SessionStore.Named) value).name();
                if (name.equalsIgnoreCase(String.valueOf(manager.sessions().active()))) {
                    name = MessageFormat.format(Messages.get("session.manage.active"), name);
                }
                return super.getListCellRendererComponent(l, name, index, selected, focus);
            }
        });
        list.addListSelectionListener(e -> updateButtons());
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && list.getSelectedValue() != null) open();
            }
            @Override public void mousePressed(java.awt.event.MouseEvent e)  { maybeMenu(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { maybeMenu(e); }
        });
        ManagerDialogs.onDeleteKey(list, this::delete);
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "clouseau.rename");
        list.getActionMap().put("clouseau.rename", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { rename(); }
        });
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "clouseau.open");
        list.getActionMap().put("clouseau.open", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { open(); }
        });
        setUpRenameField();

        openButton.addActionListener(e -> open());
        renameButton.addActionListener(e -> rename());
        deleteButton.addActionListener(e -> delete());
        JButton close = new JButton(Messages.get("session.manage.close"));
        close.addActionListener(e -> dispose());

        JPanel content = new JPanel(new MigLayout("fill, insets 16, wrap 1, gapy 8", "[grow, fill]", "[][grow, fill]"));
        content.add(ManagerDialogs.sectionTitle(Messages.get("session.manage.list.label")), "growx");
        content.add(ClouseauColors.noFocusFrame(new JScrollPane(list)), "grow");

        // The list's actions on the left, Close on the right, as in the Plugin Manager.
        JPanel root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        root.add(DialogFooter.of(new JComponent[]{ openButton, renameButton, deleteButton }, close), BorderLayout.SOUTH);
        setContentPane(root);
        // No default button: the actions apply at once, and Close shouldn't be the amber one.
        // Escape closes (while renaming, it cancels the rename instead: the field takes it first).
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        reload(null);
        pack();
        ManagerDialogs.rememberSize(this, "sessions", new Dimension(440, 360), new Dimension(380, 260));
        setLocationRelativeTo(owner);
    }

    private void maybeMenu(java.awt.event.MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = list.locationToIndex(e.getPoint());
        if (row < 0 || !list.getCellBounds(row, row).contains(e.getPoint())) return;
        list.setSelectedIndex(row);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem openItem = new JMenuItem(Messages.get("session.manage.open"));
        openItem.addActionListener(a -> open());
        JMenuItem renameItem = new JMenuItem(Messages.get("session.manage.rename"));
        renameItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0));
        renameItem.addActionListener(a -> rename());
        JMenuItem deleteItem = new JMenuItem(Messages.get("session.manage.delete.item"));
        deleteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
        deleteItem.addActionListener(a -> delete());
        menu.add(openItem);
        menu.add(renameItem);
        menu.addSeparator();
        menu.add(deleteItem);
        menu.show(list, e.getX(), e.getY());
    }

    private void reload(String select) {
        List<SessionStore.Named> named = manager.sessions().named();
        model.clear();
        named.forEach(model::addElement);
        int index = 0;
        for (int i = 0; i < named.size(); i++) {
            if (named.get(i).name().equalsIgnoreCase(String.valueOf(select))) index = i;
        }
        if (!named.isEmpty()) list.setSelectedIndex(index);
        updateButtons();
    }

    private void updateButtons() {
        boolean any = list.getSelectedValue() != null;
        openButton.setEnabled(any);
        renameButton.setEnabled(any);
        deleteButton.setEnabled(any);
    }

    private void open() {
        SessionStore.Named n = list.getSelectedValue();
        if (n == null) return;
        dispose();
        manager.openNamedSession(n, getOwner());
    }

    // ── Rename in place ───────────────────────────────────────────────────────

    private void setUpRenameField() {
        // JList has no layout manager, so the editor and its notice are placed by hand.
        renameNoticeHolder.setOpaque(true);
        ClouseauColors.themedBackground(renameNoticeHolder, () -> list.getBackground());
        renameNoticeHolder.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        renameNoticeHolder.add(renameNotice, BorderLayout.WEST);

        InputMap im = renameField.getInputMap(JComponent.WHEN_FOCUSED);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "clouseau.commit");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clouseau.cancel");
        renameField.getActionMap().put("clouseau.commit", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { commitRename(); }
        });
        renameField.getActionMap().put("clouseau.cancel", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { stopRename(true); }
        });
        renameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { checkRename(); }
            @Override public void removeUpdate(DocumentEvent e)  { checkRename(); }
            @Override public void changedUpdate(DocumentEvent e) { checkRename(); }
        });
        // Clicking elsewhere ends the rename: saved if the name can be used, dropped if not.
        renameField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                if (renaming == null || e.isTemporary()) return;
                if (renameProblem(renameField.getText()) == null) commitRename();
                else stopRename(false);
            }
        });
    }

    private void rename() {
        if (renaming != null) return;
        int row = list.getSelectedIndex();
        if (row < 0) return;
        list.ensureIndexIsVisible(row);
        renaming = model.get(row);
        Rectangle cell = list.getCellBounds(row, row);
        renameField.setText(renaming.name());
        renameField.putClientProperty("JComponent.outline", null);
        renameField.setBounds(cell.x + 2, cell.y, cell.width - 4, cell.height);
        list.add(renameField);
        renameNotice.setText(" ");
        list.add(renameNoticeHolder);
        renameNoticeHolder.setVisible(false);
        renameField.selectAll();
        list.revalidate();
        list.repaint();
        renameField.requestFocusInWindow();
        renameButton.setEnabled(false);
    }

    /** Why {@code text} can't be the new name, or null when it can (or is the name it has). */
    private String renameProblem(String text) {
        String to = text.strip();
        if (renaming == null || to.isEmpty()) return null;
        var clash = SessionStore.find(SessionManager.sessionsDir(), to);
        if (clash.isPresent() && !clash.get().file().equals(renaming.file())) {
            return MessageFormat.format(Messages.get("session.manage.rename.exists"), to);
        }
        return null;
    }

    /** Shows, under the field, why the typed name can't be used; hides the notice when it can. */
    private void checkRename() {
        if (renaming == null) return;
        String problem = renameProblem(renameField.getText());
        renameField.putClientProperty("JComponent.outline", problem != null ? "error" : null);
        if (problem == null) {
            renameNoticeHolder.setVisible(false);
        } else {
            renameNotice.show(problem, NoticeLabel.Kind.ERROR);
            Rectangle f = renameField.getBounds();
            Dimension pref = renameNoticeHolder.getPreferredSize();
            int w = Math.min(pref.width, list.getWidth() - f.x);
            // Under the field, or over it when the row is the last one in view.
            Rectangle view = list.getVisibleRect();
            int y = f.y + f.height + pref.height <= view.y + view.height ? f.y + f.height : f.y - pref.height;
            renameNoticeHolder.setBounds(f.x, y, w, pref.height);
            renameNoticeHolder.setVisible(true);
            renameNoticeHolder.revalidate();
        }
        list.repaint();
    }

    private void commitRename() {
        SessionStore.Named n = renaming;
        if (n == null) return;
        String to = renameField.getText().strip();
        if (to.isEmpty() || to.equals(n.name())) {
            stopRename(true);
            return;
        }
        if (renameProblem(to) != null) {
            checkRename();
            UIManager.getLookAndFeel().provideErrorFeedback(renameField);
            return;
        }
        stopRename(true);
        try {
            SessionStore.rename(SessionManager.sessionsDir(), n.name(), to);
            if (n.name().equalsIgnoreCase(String.valueOf(manager.sessions().active()))) manager.sessions().setActive(to);
        } catch (IOException e) {
            log.warn("Could not rename session '{}'", n.name(), e);
            JOptionPane.showMessageDialog(this, MessageFormat.format(Messages.get("session.manage.failed"), n.name(), e.getMessage()),
                    Messages.get("session.manage.title"), JOptionPane.ERROR_MESSAGE);
        }
        reload(to);
    }

    /** Takes the editor away; {@code refocus} gives the list the focus back. */
    private void stopRename(boolean refocus) {
        if (renaming == null) return;
        renaming = null;
        list.remove(renameField);
        list.remove(renameNoticeHolder);
        list.revalidate();
        list.repaint();
        updateButtons();
        if (refocus) list.requestFocusInWindow();
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void delete() {
        if (renaming != null) return;
        SessionStore.Named n = list.getSelectedValue();
        if (n == null) return;
        int row = list.getSelectedIndex();
        if (!ManagerDialogs.confirmDelete(this, Messages.get("session.manage.delete.title"),
                MessageFormat.format(Messages.get("session.manage.delete.message"), n.name()))) return;
        try {
            SessionStore.delete(SessionManager.sessionsDir(), n.name());
            if (n.name().equalsIgnoreCase(String.valueOf(manager.sessions().active()))) manager.sessions().setActive(null);
        } catch (IOException e) {
            log.warn("Could not delete session '{}'", n.name(), e);
            JOptionPane.showMessageDialog(this, MessageFormat.format(Messages.get("session.manage.failed"), n.name(), e.getMessage()),
                    Messages.get("session.manage.title"), JOptionPane.ERROR_MESSAGE);
        }
        reload(null);
        if (!model.isEmpty()) list.setSelectedIndex(Math.min(row, model.size() - 1));
        list.requestFocusInWindow();
    }
}
