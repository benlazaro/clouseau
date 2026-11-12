package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauFonts;
import net.miginfocom.swing.MigLayout;

import com.droidenx.clouseau.core.AnnotationStore;

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

        // Laid out like Settings: labels left-aligned, and a footer below a divider with
        // Cancel before the default button.
        JPanel form = new JPanel(new MigLayout("fill, insets 20 20 16 20, gap 10", "[][grow]", "[][grow]"));
        form.add(new JLabel(Messages.get("annotation.dialog.author")));
        authorField = new JTextField(existing != null ? existing.author()
                                                      : System.getProperty("user.name", ""));
        form.add(authorField, "growx, wrap");

        form.add(new JLabel(Messages.get("annotation.dialog.note")), "top, gaptop 3");
        noteArea = new JTextArea(existing != null ? existing.note() : "", 8, 40);
        noteArea.setLineWrap(true);
        noteArea.setWrapStyleWord(true);
        noteArea.setFont(ClouseauFonts.mono(Font.PLAIN, 13));
        form.add(new JScrollPane(noteArea), "grow");

        JButton ok     = new JButton(Messages.get("settings.button.ok"));
        JButton cancel = new JButton(Messages.get("settings.button.cancel"));
        ok.addActionListener(e -> { confirmed = true; dispose(); });
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel footer = DialogFooter.of(cancel, ok);

        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);

        pack();
        setMinimumSize(new Dimension(420, 280));
        setLocationRelativeTo(owner);
        noteArea.requestFocusInWindow();
    }

    public boolean isConfirmed() { return confirmed; }
    public String  getNote()     { return noteArea.getText().trim(); }
    public String  getAuthor()   { return authorField.getText().trim(); }
}
