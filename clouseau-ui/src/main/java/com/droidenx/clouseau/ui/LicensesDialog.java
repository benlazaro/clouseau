package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauFonts;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Help → About → Licenses: Clouseau's own license, then the third-party notices (every
 * library and font it ships, with their license texts). Both are read from the jar
 * ({@code legal/}); a release image also has them as files in its {@code legal} folder.
 */
@Slf4j
final class LicensesDialog {

    static final String LICENSE = "legal/LICENSE.txt";
    static final String NOTICES = "legal/THIRD-PARTY-NOTICES.txt";

    private LicensesDialog() {}

    static void show(Window owner) {
        JDialog dialog = new JDialog(owner, Messages.get("licenses.title"), Dialog.ModalityType.APPLICATION_MODAL);

        JTextArea text = new JTextArea(text());
        text.setEditable(false);
        text.setFont(ClouseauFonts.mono(Font.PLAIN, 12));
        text.setMargin(new Insets(12, 16, 12, 16));
        text.setCaretPosition(0);

        JButton close = new JButton(Messages.get("licenses.close"));
        close.addActionListener(e -> dialog.dispose());

        JPanel root = new JPanel(new BorderLayout());
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        root.add(scroll, BorderLayout.CENTER);
        root.add(DialogFooter.of(close), BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(close);
        DialogFooter.closeOnEscape(dialog.getRootPane(), dialog::dispose);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        ManagerDialogs.rememberSize(dialog, "licenses", new Dimension(760, 620), new Dimension(480, 320));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
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

    private static String read(String resource) {
        try (InputStream in = LicensesDialog.class.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read {}", resource, e);
            return null;
        }
    }
}
