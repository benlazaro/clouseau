package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.SessionStore;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;

/**
 * File → Sessions → Manage Sessions… (UC-17): the named sessions, to open, rename or
 * delete. Renaming or deleting the session in use updates the window titles.
 */
@Slf4j
final class ManageSessionsDialog extends JDialog {

    private final WindowManager manager;
    private final DefaultListModel<SessionStore.Named> model = new DefaultListModel<>();
    private final JList<SessionStore.Named> list = new JList<>(model);
    private final JButton openButton = new JButton(Messages.get("session.manage.open"));
    private final JButton renameButton = new JButton(Messages.get("session.manage.rename"));
    private final JButton deleteButton = new JButton(Messages.get("session.manage.delete"));

    ManageSessionsDialog(Frame owner, WindowManager manager) {
        super(owner, Messages.get("session.manage.title"), true);
        this.manager = manager;

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
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
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) open();
            }
        });

        openButton.addActionListener(e -> open());
        renameButton.addActionListener(e -> rename());
        deleteButton.addActionListener(e -> delete());
        JButton close = new JButton(Messages.get("session.manage.close"));
        close.addActionListener(e -> dispose());

        JPanel content = new JPanel(new MigLayout("insets 16, gap 8", "[grow, fill][]", "[grow, fill]"));
        content.add(new JScrollPane(list), "w 300!, h 240!, spany");
        JPanel buttons = new JPanel(new MigLayout("insets 0, wrap 1, gapy 6", "[fill]"));
        buttons.add(openButton);
        buttons.add(renameButton);
        buttons.add(deleteButton);
        buttons.add(close, "gaptop push");
        content.add(buttons, "growy");
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        reload(null);
        pack();
        setLocationRelativeTo(owner);
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

    private void rename() {
        SessionStore.Named n = list.getSelectedValue();
        if (n == null) return;
        String to = (String) JOptionPane.showInputDialog(this, Messages.get("session.manage.rename.message"),
                Messages.get("session.manage.rename.title"), JOptionPane.PLAIN_MESSAGE, null, null, n.name());
        if (to == null || to.isBlank() || to.strip().equals(n.name())) return;
        to = to.strip();
        var clash = SessionStore.find(SessionManager.sessionsDir(), to);
        if (clash.isPresent() && !clash.get().file().equals(n.file())) {
            JOptionPane.showMessageDialog(this, MessageFormat.format(Messages.get("session.manage.rename.exists"), to),
                    Messages.get("session.manage.rename.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }
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

    private void delete() {
        SessionStore.Named n = list.getSelectedValue();
        if (n == null) return;
        int answer = JOptionPane.showConfirmDialog(this,
                MessageFormat.format(Messages.get("session.manage.delete.message"), n.name()),
                Messages.get("session.manage.delete.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        try {
            SessionStore.delete(SessionManager.sessionsDir(), n.name());
            if (n.name().equalsIgnoreCase(String.valueOf(manager.sessions().active()))) manager.sessions().setActive(null);
        } catch (IOException e) {
            log.warn("Could not delete session '{}'", n.name(), e);
            JOptionPane.showMessageDialog(this, MessageFormat.format(Messages.get("session.manage.failed"), n.name(), e.getMessage()),
                    Messages.get("session.manage.title"), JOptionPane.ERROR_MESSAGE);
        }
        reload(null);
    }
}
