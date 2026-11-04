package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.ReviewBundle;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Locale;

/**
 * {@code File → Export for review…} (UC-8, D-10): asks where to save, then writes the
 * tab's log and annotations as a review bundle off the EDT, and reports the result.
 */
@Slf4j
final class ReviewExporter {

    private ReviewExporter() {}

    /** Exports the log shown in {@code panel} (null if no tab is open). Call on the EDT. */
    static void export(Component parent, LogPanel panel) {
        String title = Messages.get("export.review.title");
        if (panel == null || panel.getFile() == null) {
            JOptionPane.showMessageDialog(parent, Messages.get("export.review.nofile"), title,
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // Read on the EDT, where the store lives; only the copying happens in the background.
        String annotations = panel.annotationsJson();
        if (annotations == null) {
            JOptionPane.showMessageDialog(parent, Messages.get("export.review.loading"), title,
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Path file = panel.getFile();
        String entry = panel.getZipEntry();
        String logName = ReviewBundle.logName(file, entry);

        Path dest = chooseDestination(parent, file, logName);
        if (dest == null) return;

        Cursor cursor = parent.getCursor();
        parent.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                ReviewBundle.write(file, entry, annotations, AppPrefs.getAppVersion(), dest);
                return null;
            }

            @Override
            protected void done() {
                parent.setCursor(cursor);
                try {
                    get();
                    JOptionPane.showMessageDialog(parent,
                            MessageFormat.format(Messages.get("export.review.done"), logName, dest.toString()),
                            title, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.warn("Could not export {} for review to {}", logName, dest, cause);
                    JOptionPane.showMessageDialog(parent,
                            MessageFormat.format(Messages.get("export.review.error"), logName, cause.getMessage()),
                            title, JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** Asks for the bundle's path, suggesting {@code <log>-review.zip} next to the log. */
    private static Path chooseDestination(Component parent, Path file, String logName) {
        Path folder = file.toAbsolutePath().getParent();
        Path suggested = folder.resolve(suggestedName(logName));
        for (int n = 2; suggested.equals(file.toAbsolutePath()); n++) {
            suggested = folder.resolve(suggestedName(logName).replace(".zip", "-" + n + ".zip"));
        }
        JFileChooser chooser = new JFileChooser(folder.toFile());
        chooser.setDialogTitle(Messages.get("export.review.title"));
        chooser.setFileFilter(new FileNameExtensionFilter(Messages.get("export.review.filter"), "zip"));
        chooser.setSelectedFile(suggested.toFile());
        while (true) {
            if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
            Path dest = chooser.getSelectedFile().toPath().toAbsolutePath();
            if (!dest.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                dest = dest.resolveSibling(dest.getFileName() + ".zip");
            }
            if (dest.normalize().equals(file.toAbsolutePath().normalize())) {
                JOptionPane.showMessageDialog(parent,
                        MessageFormat.format(Messages.get("export.review.self"), dest.getFileName()),
                        Messages.get("export.review.title"), JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (Files.exists(dest) && JOptionPane.showConfirmDialog(parent,
                    MessageFormat.format(Messages.get("export.review.overwrite"), dest.getFileName()),
                    Messages.get("export.review.title"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                continue;
            }
            return dest;
        }
    }

    /** {@code app.log.gz} → {@code app-review.zip}. */
    static String suggestedName(String logName) {
        String base = logName;
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gz")) base = base.substring(0, base.length() - 3);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + "-review.zip";
    }
}
