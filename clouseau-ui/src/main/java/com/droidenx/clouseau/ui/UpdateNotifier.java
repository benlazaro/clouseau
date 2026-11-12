package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.UpdateCheck;
import com.droidenx.clouseau.core.UpdateCheck.Release;
import com.droidenx.clouseau.core.UpdateCheck.Result;
import com.droidenx.clouseau.core.UpdateCheck.Version;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.text.MessageFormat;
import java.time.Duration;

/**
 * Tells the user when a new Clouseau is released (UC-18).
 * <p>
 * Shortly after startup, and then once a day while Clouseau runs, it asks GitHub for the
 * releases ({@link UpdateCheck}) and, if one is newer, shows a notice that doesn't block the
 * window: Download (opens the release page), Skip This Version, or Later. Settings → Updates,
 * or the check box on the notice, turns this off. Help → Check for Updates… checks at once,
 * whatever the setting, and says so when there's nothing new.
 * <p>
 * Development builds ({@code -SNAPSHOT}) never check by themselves.
 */
@Slf4j
final class UpdateNotifier {

    private static final long DAY_MS = Duration.ofDays(1).toMillis();
    /** Leave startup alone: check once the windows are up and the session has loaded. */
    private static final int FIRST_CHECK_DELAY_MS = 5_000;
    /** How often to see whether a day has passed; the last check's time is saved, so sleep doesn't matter. */
    private static final int TICK_MS = (int) Duration.ofHours(1).toMillis();

    private final WindowManager windows;
    private boolean checking;
    private JDialog notice;

    UpdateNotifier(WindowManager windows) {
        this.windows = windows;
    }

    /** Starts the automatic checks. Call on the EDT once the windows are shown. */
    void start() {
        Timer first = new Timer(FIRST_CHECK_DELAY_MS, e -> autoCheck());
        first.setRepeats(false);
        first.start();
        new Timer(TICK_MS, e -> autoCheck()).start();
    }

    private void autoCheck() {
        String current = AppPrefs.getAppVersion();
        if (!AppPrefs.isUpdateCheck() || UpdateCheck.isDevelopment(current)) return;
        if (checking || notice != null) return;
        long now = System.currentTimeMillis();
        long last = AppPrefs.getLastUpdateCheck();
        if (last <= now && now - last < DAY_MS) return;
        AppPrefs.setLastUpdateCheck(now);
        run(current, result -> {
            if (result instanceof Result.Available a && !skipped(a.release()) && AppPrefs.isUpdateCheck()) {
                showNotice(a.release(), current);
            }
        });
    }

    /** Help → Check for Updates…: checks now and always answers. */
    void checkNow(Window owner) {
        String current = AppPrefs.getAppVersion();
        if (checking) return;
        run(current, result -> {
            switch (result) {
                case Result.Available a -> showNotice(a.release(), current);
                case Result.UpToDate u -> JOptionPane.showMessageDialog(owner,
                        MessageFormat.format(Messages.get("update.uptodate.message"), current),
                        Messages.get("update.title"), JOptionPane.INFORMATION_MESSAGE);
                case Result.DevelopmentBuild d -> {
                    Object[] options = { Messages.get("update.button.close"), Messages.get("update.button.releases") };
                    int choice = JOptionPane.showOptionDialog(owner,
                            MessageFormat.format(Messages.get("update.development.message"), current),
                            Messages.get("update.title"), JOptionPane.DEFAULT_OPTION,
                            JOptionPane.INFORMATION_MESSAGE, null, options, options[1]);
                    if (choice == 1) browse(UpdateCheck.RELEASES_PAGE);
                }
                case Result.Failed f -> JOptionPane.showMessageDialog(owner,
                        MessageFormat.format(Messages.get("update.failed.message"), f.reason()),
                        Messages.get("update.title"), JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    /** Runs the check off the EDT and hands its result to {@code done} on the EDT. */
    private void run(String current, java.util.function.Consumer<Result> done) {
        checking = true;
        new SwingWorker<Result, Void>() {
            @Override protected Result doInBackground() {
                return UpdateCheck.check(current);
            }

            @Override protected void done() {
                checking = false;
                try {
                    done.accept(get());
                } catch (Exception e) {
                    log.warn("Update check failed", e);
                }
            }
        }.execute();   // short-lived: fine on the shared pool (D-22 is about workers that never end)
    }

    private static boolean skipped(Release release) {
        Version skipped = Version.parse(AppPrefs.getSkippedVersion());
        Version offered = Version.parse(release.version());
        return skipped != null && offered != null && offered.compareTo(skipped) <= 0;
    }

    /** The notice: what's new, and Download · Skip This Version · Later. Doesn't block the window. */
    private void showNotice(Release release, String current) {
        if (notice != null) notice.dispose();
        Window owner = windows.lastActive();
        JDialog dialog = new JDialog(owner, Messages.get("update.title"), Dialog.ModalityType.MODELESS);
        notice = dialog;

        JLabel headline = new JLabel(MessageFormat.format(Messages.get("update.available.headline"), release.version()));
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize2D() + 2f));
        JLabel detail = new JLabel(MessageFormat.format(Messages.get("update.available.detail"), current));
        JLabel name = new JLabel(release.name());

        JPanel body = new JPanel(new MigLayout("insets 20 24 16 24, wrap 1, gapy 6", "[grow, fill]"));
        body.add(headline);
        body.add(name, "gaptop 2");
        body.add(detail, "gaptop 6");

        JCheckBox notify = new JCheckBox(Messages.get("update.notify"), AppPrefs.isUpdateCheck());
        notify.addActionListener(e -> AppPrefs.setUpdateCheck(notify.isSelected()));

        JButton skip = new JButton(Messages.get("update.button.skip"));
        JButton later = new JButton(Messages.get("update.button.later"));
        JButton download = new JButton(Messages.get("update.button.download"));
        skip.addActionListener(e -> {
            AppPrefs.setSkippedVersion(release.version());
            dialog.dispose();
        });
        later.addActionListener(e -> dialog.dispose());
        download.addActionListener(e -> {
            browse(release.url());
            dialog.dispose();
        });

        JPanel root = new JPanel(new BorderLayout());
        root.add(body, BorderLayout.CENTER);
        root.add(DialogFooter.of(new JComponent[] { notify }, skip, later, download), BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(download);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                if (notice == dialog) notice = null;
            }
        });
        dialog.pack();
        // Packed, the footer is exactly as wide as the text, and the check box touches the
        // buttons; leave a gap between them.
        dialog.setSize(dialog.getWidth() + 48, dialog.getHeight());
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        log.info("Update available: {} (running {})", release.version(), current);
    }

    private static void browse(String url) {
        try {
            Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Exception e) {
            log.warn("Could not open {}", url, e);
        }
    }
}
