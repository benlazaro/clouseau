package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * A small modal form that asks for one thing, as every dialog is laid out: the form with
 * 20px around it, then a {@link DialogFooter} with Cancel before OK, OK the default button;
 * Esc or the close box cancels. Wide enough to show its whole title, which often names a file.
 * <p>
 * OK closes the dialog unless an {@link #onOk(java.util.function.BooleanSupplier) OK check}
 * says no (it then shows why in the form, on the field it's about).
 */
final class PromptDialog extends JDialog {

    private final JButton okBtn = new JButton(Messages.get("settings.button.ok"));
    private java.util.function.BooleanSupplier okCheck = () -> true;
    private boolean accepted;

    /**
     * @param form what to ask, laid out by the caller
     * @param left controls for the footer's left side, which don't close the dialog
     */
    PromptDialog(Component parent, String title, JComponent form, JComponent... left) {
        super(parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent), title,
                ModalityType.APPLICATION_MODAL);
        JButton cancelBtn = new JButton(Messages.get("settings.button.cancel"));
        cancelBtn.addActionListener(e -> dispose());
        okBtn.addActionListener(e -> {
            if (!okCheck.getAsBoolean()) return;
            accepted = true;
            dispose();
        });
        getRootPane().setDefaultButton(okBtn);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel body = new JPanel(new MigLayout("insets 20 20 12 20, fill", "[grow, fill]", "[grow, fill]"));
        body.add(form);
        JPanel root = new JPanel(new BorderLayout());
        root.add(body, BorderLayout.CENTER);
        root.add(DialogFooter.of(left, cancelBtn, okBtn), BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setSize(Math.max(getWidth(), titleWidth(title)), getHeight());
        setResizable(false);
        setLocationRelativeTo(getOwner());
    }

    /** Decides whether OK closes the dialog; when it doesn't, it shows why in the form. */
    void onOk(java.util.function.BooleanSupplier check) {
        okCheck = check;
    }

    JButton okButton() {
        return okBtn;
    }

    /** Shows the dialog and waits; true if it was closed with OK. */
    boolean ask() {
        setVisible(true);
        return accepted;
    }

    /** A hint under a field: dim, on the type scale. */
    static JLabel hint(String text) {
        JLabel l = new JLabel(text);
        l.setFont(UIManager.getFont("Label.font").deriveFont(12f));
        ClouseauColors.themedForeground(l, ClouseauColors::dimForeground);
        return l;
    }

    /**
     * Room for the whole title, plus the window's icon and close button, so a long file name
     * isn't cut short.
     */
    private static int titleWidth(String title) {
        Font font = UIManager.getFont("TitlePane.font");
        if (font == null) font = UIManager.getFont("Label.font");
        return new JLabel().getFontMetrics(font).stringWidth(title) + 120;
    }
}
