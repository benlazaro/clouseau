package com.droidenx.clouseau.ui;

import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Modal dialog for adding or editing a log-line annotation.
 */
public final class AnnotationDialog extends JDialog {

    private final JTextField authorField;
    private final JTextArea  noteArea;
    private boolean confirmed = false;

    public AnnotationDialog(Window owner, AnnotationStore.Annotation existing) {
        super(owner,
              existing != null ? Messages.get("annotation.dialog.edit.title")
                               : Messages.get("annotation.dialog.add.title"),
              ModalityType.APPLICATION_MODAL);

        setLayout(new MigLayout("fill, insets 12, gap 8", "[80!][grow]", "[][grow][]"));

        add(new JLabel(Messages.get("annotation.dialog.author")), "right");
        authorField = new JTextField(existing != null ? existing.author()
                                                      : System.getProperty("user.name", ""));
        add(authorField, "growx, wrap");

        add(new JLabel(Messages.get("annotation.dialog.note")), "top, right");
        noteArea = new JTextArea(existing != null ? existing.note() : "", 8, 40);
        noteArea.setLineWrap(true);
        noteArea.setWrapStyleWord(true);
        noteArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        add(new JScrollPane(noteArea), "grow, wrap");

        JButton ok     = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> { confirmed = true; dispose(); });
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(ok);
        buttons.add(cancel);
        add(buttons, "skip, growx");

        pack();
        setMinimumSize(new Dimension(420, 280));
        setLocationRelativeTo(owner);
        noteArea.requestFocusInWindow();
    }

    public boolean isConfirmed() { return confirmed; }
    public String  getNote()     { return noteArea.getText().trim(); }
    public String  getAuthor()   { return authorField.getText().trim(); }
}
