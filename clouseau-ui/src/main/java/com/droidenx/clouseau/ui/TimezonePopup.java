package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.time.ZoneId;

/**
 * The tab's timezones, opened from the status bar: how the log's times are read (the
 * source timezone, this tab's or every open tab's) and how all times are shown (the
 * display timezone, the same setting as in Settings). Apply makes the change; Esc or a
 * click elsewhere closes it unchanged.
 * <p>
 * A small window rather than a popup menu, so the drop-downs inside it can open their own
 * lists without closing it.
 */
final class TimezonePopup extends JDialog {

    /** What the user chose. {@code allTabs}: read every open tab in {@code source}. */
    record Choice(ZoneId source, boolean sourceChanged, boolean allTabs, ZoneId display, boolean displayChanged) {}

    interface Apply { void apply(Choice choice); }

    private TimezonePopup(Window owner, JComponent anchor, ZoneId source, boolean mixed, int openTabs, Apply apply) {
        super(owner, ModalityType.MODELESS);
        setUndecorated(true);
        ZoneId display = AppPrefs.getDisplayTimezone();
        ZoneCombo sourceCombo = new ZoneCombo(source);
        ZoneCombo displayCombo = new ZoneCombo(display);
        JCheckBox allTabs = new JCheckBox(MessageFormat.format(Messages.get("timezone.popup.all.tabs"), openTabs));
        allTabs.setEnabled(openTabs > 1);

        JPanel p = new JPanel(new MigLayout("insets 12 14 12 14, gapy 6, wrap 2", "[]12[grow, fill, 300::]"));
        p.add(SettingsDialog.sectionLabel(Messages.get("timezone.popup.read.title")), "span 2, growx, gapbottom 2");
        p.add(new JLabel(Messages.get("timezone.popup.read")));
        p.add(sourceCombo);
        if (mixed) p.add(hint(Messages.get("timezone.popup.read.mixed")), "skip 1");
        p.add(allTabs, "skip 1");
        p.add(SettingsDialog.sectionLabel(Messages.get("timezone.popup.show.title")), "span 2, growx, gaptop 10, gapbottom 2");
        p.add(new JLabel(Messages.get("timezone.popup.show")));
        p.add(displayCombo);
        p.add(hint(Messages.get("timezone.popup.show.hint")), "skip 1");

        JButton cancel = new JButton(Messages.get("timezone.popup.cancel"));
        JButton ok = new JButton(Messages.get("timezone.popup.apply"));
        cancel.addActionListener(e -> dispose());
        ok.addActionListener(e -> {
            ZoneId src = sourceCombo.selectedZone(), shown = displayCombo.selectedZone();
            boolean all = allTabs.isSelected();
            dispose();
            apply.apply(new Choice(src, !src.equals(source), all, shown, !shown.equals(display)));
        });
        JLabel reloadNote = hint(Messages.get("timezone.popup.reload"));
        reloadNote.setMinimumSize(new Dimension(0, 0));
        JPanel footer = DialogFooter.of(new JComponent[] { reloadNote }, cancel, ok);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createLineBorder(ClouseauColors.borderColor()));
        root.add(p, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        // A click elsewhere closes it, as a popup does.
        addWindowListener(new WindowAdapter() {
            @Override public void windowDeactivated(WindowEvent e) {
                Window to = e.getOppositeWindow();
                if (to == null || to.getOwner() != TimezonePopup.this) dispose();
            }
        });
        pack();
        // Above the badge, right edges aligned, kept on screen.
        Point at = anchor.getLocationOnScreen();
        Rectangle screen = owner.getGraphicsConfiguration().getBounds();
        int x = Math.max(screen.x, Math.min(at.x + anchor.getWidth() - getWidth(), screen.x + screen.width - getWidth()));
        int y = Math.max(screen.y, at.y - getHeight() - 4);
        setLocation(x, y);
        SwingUtilities.invokeLater(sourceCombo::requestFocusInWindow);
    }

    /** Opens the popup above {@code anchor}; {@code apply} runs on Apply. */
    static void show(JComponent anchor, ZoneId source, boolean mixed, int openTabs, Apply apply) {
        Window owner = SwingUtilities.getWindowAncestor(anchor);
        if (owner == null || !anchor.isShowing()) return;
        new TimezonePopup(owner, anchor, source, mixed, openTabs, apply).setVisible(true);
    }

    private static JLabel hint(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(ClouseauColors.dimForeground());
        l.setFont(l.getFont().deriveFont(11f));
        return l;
    }
}
