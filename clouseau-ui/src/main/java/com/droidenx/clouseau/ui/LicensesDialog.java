package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import com.droidenx.clouseau.ui.theme.ClouseauFonts;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Help → About → Licenses: one tab per license (Clouseau's own first, then Apache 2.0, MIT,
 * …), each listing the components under it, then the license text. Both files are read from
 * the jar ({@code legal/}) and split by {@link LicenseNotices}; a release image also has them
 * as files in its {@code legal} folder. If the notices can't be split, the dialog shows
 * Clouseau's license and the notices as one text, as they are.
 */
@Slf4j
final class LicensesDialog {

    static final String LICENSE = "legal/LICENSE.txt";
    static final String NOTICES = "legal/THIRD-PARTY-NOTICES.txt";

    private LicensesDialog() {}

    static void show(Window owner) {
        JDialog dialog = new JDialog(owner, Messages.get("licenses.title"), Dialog.ModalityType.APPLICATION_MODAL);

        JButton close = new JButton(Messages.get("licenses.close"));
        close.addActionListener(e -> dialog.dispose());

        JPanel root = new JPanel(new BorderLayout());
        root.add(content(), BorderLayout.CENTER);
        root.add(DialogFooter.of(close), BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(close);
        DialogFooter.closeOnEscape(dialog.getRootPane(), dialog::dispose);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        ManagerDialogs.rememberSize(dialog, "licenses", new Dimension(760, 620), new Dimension(480, 320));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /** The tabs, or the whole text in one view when the notices are missing or can't be split. */
    private static JComponent content() {
        List<LicenseNotices.Tab> tabs = tabs();
        if (tabs.isEmpty()) return scroll(plainText(text()));
        JTabbedPane pane = new JTabbedPane();
        for (LicenseNotices.Tab tab : tabs) pane.addTab(tab.label(), scroll(tabView(tab)));
        return pane;
    }

    /** The tabs from the jar's files; empty if the notices are missing or have nothing to split. */
    static List<LicenseNotices.Tab> tabs() {
        String notices = read(NOTICES);
        if (notices == null) return List.of();
        try {
            return LicenseNotices.parse(read(LICENSE), notices);
        } catch (RuntimeException e) {
            log.warn("Could not split the third-party notices into tabs", e);
            return List.of();
        }
    }

    /** Clouseau's license, then the notices; a line saying they're missing if the build left them out. */
    static String text() {
        String license = read(LICENSE);
        String notices = read(NOTICES);
        if (license == null && notices == null) return Messages.get("licenses.missing");
        StringBuilder sb = new StringBuilder();
        if (license != null) sb.append("Clouseau\n========\n\n").append(license.strip()).append("\n\n\n");
        if (notices != null) sb.append(notices.strip()).append('\n');
        return sb.toString();
    }

    // ── A tab ───────────────────────────────────────────────────────────────────

    /** The note (Java runtime), each component (name, coordinates, copyright, website, notes), then the texts. */
    private static JComponent tabView(LicenseNotices.Tab tab) {
        JPanel view = new JPanel(new MigLayout("insets 16 20 16 20, wrap 1, gapy 0", "[grow, fill]"));
        view.setBackground(ClouseauColors.detailBackground());
        Color dim = ClouseauColors.dimForeground();

        boolean header = false;
        if (tab.note() != null) {
            view.add(bold(Messages.get("licenses.runtime")));
            view.add(textArea(tab.note(), UIManager.getFont("Label.font"), dim), "gaptop 2");
            header = true;
        }
        for (LicenseNotices.Component c : tab.components()) {
            // One module beside the name; several, one per line under it, so the tab needn't scroll sideways.
            String[] modules = c.coordinates().split(",\\s*");
            JPanel title = new JPanel(new MigLayout("insets 0, gapx 8, gapy 0", "[][grow]"));
            title.setOpaque(false);
            title.add(bold(c.name()), modules.length > 1 ? "wrap" : "");
            for (String module : modules) {
                JLabel label = new JLabel(module);
                label.setFont(ClouseauFonts.mono(Font.PLAIN, 11));
                label.setForeground(dim);
                title.add(label, modules.length > 1 ? "span, wrap" : "wmin 0");
            }
            view.add(title, header ? "gaptop 14" : "");
            if (!c.copyright().isEmpty()) view.add(new JLabel(c.copyright()), "gaptop 2");
            if (c.url() != null) view.add(link(c.url()), "gaptop 2");
            if (c.notes() != null) view.add(textArea(c.notes(), UIManager.getFont("Label.font"), dim), "gaptop 4");
            header = true;
        }
        boolean headings = tab.texts().size() > 1;
        for (int i = 0; i < tab.texts().size(); i++) {
            LicenseNotices.Text t = tab.texts().get(i);
            if (header || i > 0) view.add(new JSeparator(), "gaptop 16, gapbottom 12");
            if (headings) view.add(bold(t.title()), "gapbottom 8");
            view.add(textArea(t.body(), ClouseauFonts.mono(Font.PLAIN, 12), null));
        }
        return view;
    }

    private static JLabel bold(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    /**
     * Selectable text on the tab's background, with its own line breaks. Its caret never
     * scrolls: a tab holds several of these, and each would scroll the tab to itself when shown.
     */
    private static JTextArea textArea(String text, Font font, Color foreground) {
        JTextArea area = new JTextArea();
        ((javax.swing.text.DefaultCaret) area.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        area.setText(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(font);
        if (foreground != null) area.setForeground(foreground);
        return area;
    }

    /** A website: link-colored, opens in the browser. */
    private static JLabel link(String url) {
        JLabel label = new JLabel(url);
        label.setForeground(ClouseauColors.linkColor());
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new URI(url));
                } catch (Exception ex) {
                    log.warn("Could not open {}", url, ex);
                }
            }
        });
        return label;
    }

    private static JTextArea plainText(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(ClouseauFonts.mono(Font.PLAIN, 12));
        area.setMargin(new Insets(12, 16, 12, 16));
        area.setCaretPosition(0);
        return area;
    }

    private static JScrollPane scroll(JComponent view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private static String read(String resource) {
        try (InputStream in = LicensesDialog.class.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read {}", resource, e);
            return null;
        }
    }
}
