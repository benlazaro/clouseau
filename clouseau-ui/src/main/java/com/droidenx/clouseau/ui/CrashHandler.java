package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauFonts;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Catches every exception no code handled, on any thread, the Event Dispatch Thread
 * included: it writes it to Clouseau's log and tells the user, instead of leaving a window
 * that silently stopped working. Installed first thing in {@link ClouseauApp#main}.
 * <p>
 * The notice says Clouseau is still running, shows the details (version, Java, OS, thread,
 * stack trace), and offers Copy Details, Open Log Folder and Report Issue… (the project's
 * new-issue page; nothing is sent by Clouseau itself). Only one notice shows at a time, and
 * an error with the same stack as one already shown this run is only logged, so a failure
 * that repeats on every repaint can't bury the window in dialogs.
 */
@Slf4j
final class CrashHandler implements Thread.UncaughtExceptionHandler {

    static final String NEW_ISSUE_PAGE = "https://github.com/benlazaro/clouseau/issues/new";
    /** Stack frames that tell one error from another; deeper ones are mostly the same event loop. */
    private static final int SIGNATURE_FRAMES = 8;

    /** What the notice shows: the thread's name, the error, and the full text for the user to copy. */
    record Report(String thread, Throwable error, String details) {}

    private final Consumer<Report> notice;
    private final Set<String> shown = new HashSet<>();   // EDT only
    private boolean showing;                              // EDT only

    private CrashHandler(Consumer<Report> notice) {
        this.notice = notice;
    }

    /** Makes this the handler for every thread that has none of its own. */
    static void install() {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(CrashHandler::showNotice));
    }

    /** For tests: a handler that hands each report to {@code notice} (on the EDT) instead of a dialog. */
    static CrashHandler forTest(Consumer<Report> notice) {
        return new CrashHandler(notice);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        try {
            log.error("Unexpected error on thread {}", thread.getName(), error);
        } catch (Throwable ignored) {
            // Out of memory or logging is broken: still try to tell the user.
        }
        try {
            Report report = new Report(thread.getName(), error,
                    details(thread.getName(), error, AppPrefs.getAppVersion()));
            String signature = signature(error);
            SwingUtilities.invokeLater(() -> offer(report, signature));
        } catch (Throwable ignored) {
            // Nothing more to do: it's in the log if logging worked.
        }
    }

    /** On the EDT: shows the notice unless one is up already or this error was shown before. */
    private void offer(Report report, String signature) {
        if (showing || !shown.add(signature)) return;
        showing = true;
        try {
            notice.accept(report);
        } catch (Throwable t) {
            log.warn("Could not show the error notice", t);
        } finally {
            showing = false;
        }
    }

    /** The error's type and its top stack frames (the cause's too): equal for the same failure repeating. */
    static String signature(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            sb.append(t.getClass().getName()).append('|');
            StackTraceElement[] stack = t.getStackTrace();
            for (int i = 0; i < Math.min(SIGNATURE_FRAMES, stack.length); i++) sb.append(stack[i]).append('|');
            if (sb.length() > 4096) break;
        }
        return sb.toString();
    }

    /** What Copy Details copies, and the notice shows: enough to file an issue with. */
    static String details(String thread, Throwable error, String version) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        return AppInfo.lines(version) + "\n"
                + "Thread: " + thread + "\n\n"
                + trace.toString().strip() + "\n";
    }

    // ── The notice ──────────────────────────────────────────────────────────────

    /** The dialog: what happened, the details, and what the user can do. Modal, so it returns once closed. */
    private static void showNotice(Report report) {
        if (GraphicsEnvironment.isHeadless()) return;
        Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        JDialog dialog = new JDialog(owner, Messages.get("crash.title"), Dialog.ModalityType.APPLICATION_MODAL);

        JLabel headline = new JLabel(Messages.get("crash.headline"));
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, 14f));
        JLabel message = new JLabel("<html>" + Messages.get("crash.message") + "</html>");

        JTextArea details = new JTextArea(report.details());
        details.setEditable(false);
        details.setFont(ClouseauFonts.mono(Font.PLAIN, 12));
        details.setMargin(new Insets(8, 10, 8, 10));
        details.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(details);

        JPanel body = new JPanel(new MigLayout("insets 20 24 16 24, wrap 1, gapy 6", "[grow, fill]", "[][][grow, fill]"));
        body.add(headline);
        body.add(message, "wmin 0, gapbottom 6");
        body.add(scroll, "w 560:640, h 160:220");

        NoticeLabel copied = new NoticeLabel();
        JButton copy = new JButton(Messages.get("crash.button.copy"));
        JButton logs = new JButton(Messages.get("crash.button.logs"));
        JButton reportIssue = new JButton(Messages.get("crash.button.report"));
        JButton close = new JButton(Messages.get("crash.button.close"));
        copy.addActionListener(e -> {
            copyToClipboard(report.details());
            copied.show(Messages.get("crash.copied"), NoticeLabel.Kind.OK);
        });
        logs.addActionListener(e -> open(AppDirs.logsDir()));
        // Copies the details too, so the user can paste them into the issue. Opens the plain
        // new-issue page: nothing about the error goes into the address.
        reportIssue.addActionListener(e -> {
            copyToClipboard(report.details());
            copied.show(Messages.get("crash.copied.report"), NoticeLabel.Kind.OK);
            browse(NEW_ISSUE_PAGE);
        });
        close.addActionListener(e -> dialog.dispose());

        JPanel root = new JPanel(new BorderLayout());
        root.add(body, BorderLayout.CENTER);
        JPanel footer = DialogFooter.of(new JComponent[] { copy, logs }, reportIssue, close);
        JPanel south = new JPanel(new BorderLayout());
        JPanel noticeRow = new JPanel(new MigLayout("insets 0 24 8 24", "[grow, fill]"));
        noticeRow.add(copied, "wmin 0");
        south.add(noticeRow, BorderLayout.NORTH);
        south.add(footer, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(close);
        DialogFooter.closeOnEscape(dialog.getRootPane(), dialog::dispose);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setMinimumSize(dialog.getSize());
        dialog.setLocationRelativeTo(owner);
        close.requestFocusInWindow();
        dialog.setVisible(true);
    }

    private static void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Exception e) {
            log.warn("Could not copy the error details", e);
        }
    }

    private static void open(java.nio.file.Path folder) {
        try {
            Desktop.getDesktop().open(folder.toFile());
        } catch (Exception e) {
            log.warn("Could not open {}", folder, e);
        }
    }

    private static void browse(String url) {
        try {
            Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Exception e) {
            log.warn("Could not open {}", url, e);
        }
    }
}
