package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.Session;
import com.droidenx.clouseau.core.SessionStore;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Keeps {@code <data>/session.json} up to date (UC-17, D-19): what every window shows, so
 * the next start can reopen it. A change (any click or key, a window moved, a tab switched)
 * starts a short timer; when it fires the session is built and written only if it differs
 * from what was written last, off the EDT and atomically. Exit writes it at once.
 *
 * <p>EDT only, except the writes.
 */
@Slf4j
final class SessionManager {

    /** How long after the last change the session is saved. */
    static final int DEBOUNCE_MS = 2000;

    private final WindowManager windows;
    private final Path file;
    private final Timer timer;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "session-save");
        t.setDaemon(true);
        return t;
    });
    /** The session as last written (or read), without its header, to skip writes that change nothing. */
    private String written;
    private boolean saving;

    SessionManager(WindowManager windows, Path file) {
        this.windows = windows;
        this.file = file;
        timer = new Timer(DEBOUNCE_MS, e -> save());
        timer.setRepeats(false);
    }

    static Path lastSessionFile() {
        return AppDirs.dataDir().resolve("session.json");
    }

    Path file() { return file; }

    /** Records the session just read, so restoring it doesn't count as a change. */
    void restored(Session session) {
        written = SessionStore.toJson(session);
        active = session.activeSession();
        windows.updateTitles();
    }

    // ── Named sessions (UC-17) ────────────────────────────────────────────────

    /** The named session in use, shown in window titles, or null. */
    private String active;

    String active() { return active; }

    /** Makes {@code name} (or none) the named session in use. */
    void setActive(String name) {
        active = name;
        windows.updateTitles();
        changed();
    }

    static Path sessionsDir() {
        return AppDirs.dataDir().resolve("sessions");
    }

    List<SessionStore.Named> named() {
        return SessionStore.list(sessionsDir());
    }

    /** File → Sessions → Save Session: updates the active session with what is open now. */
    void saveActive(Component owner) {
        if (active != null) saveNamed(owner, active);
    }

    /** File → Sessions → Save Session As…: asks for a name and saves what is open now under it. */
    void saveAs(Component owner) {
        String name = (String) JOptionPane.showInputDialog(owner, Messages.get("session.save.as.message"),
                Messages.get("session.save.as.title"), JOptionPane.PLAIN_MESSAGE, null, null,
                active != null ? active : "");
        if (name == null || name.isBlank()) return;
        name = name.strip();
        var existing = SessionStore.find(sessionsDir(), name);
        if (existing.isPresent() && !existing.get().name().equalsIgnoreCase(String.valueOf(active))) {
            int answer = JOptionPane.showConfirmDialog(owner,
                    MessageFormat.format(Messages.get("session.save.replace.message"), existing.get().name()),
                    Messages.get("session.save.as.title"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
        }
        if (saveNamed(owner, name)) setActive(name);
    }

    private boolean saveNamed(Component owner, String name) {
        try {
            SessionStore.save(sessionsDir(), name, snapshot(), AppPrefs.getAppVersion());
            return true;
        } catch (IOException e) {
            log.warn("Could not save session '{}'", name, e);
            JOptionPane.showMessageDialog(owner,
                    MessageFormat.format(Messages.get("session.save.failed"), name, e.getMessage()),
                    Messages.get("session.save.as.title"), JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /**
     * Starts saving: from now on, input and window changes save the session. Off when the
     * user chose to start empty.
     */
    void start() {
        saving = true;
        if (listening) return;
        listening = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            int id = e.getID();
            if (id == MouseEvent.MOUSE_RELEASED || id == KeyEvent.KEY_RELEASED) changed();
        }, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
    }

    /** Stops saving: the user chose to start empty. */
    void stop() {
        saving = false;
        timer.stop();
    }

    private boolean listening;

    /** Something may have changed: save in a moment. */
    void changed() {
        if (saving) timer.restart();
    }

    /** What the windows show now. */
    Session snapshot() {
        return new Session(active, windows.windows().stream().map(MainFrame::sessionWindow).toList());
    }

    /** Saves in the background if the session changed since the last write. */
    void save() {
        if (!saving || windows.windows().isEmpty()) return;
        Session session = snapshot();
        String json = SessionStore.toJson(session);
        if (json.equals(written)) return;
        written = json;
        String version = AppPrefs.getAppVersion();
        writer.submit(() -> write(session, version));
    }

    /** Exit: saves now, and waits for any write in progress, before Clouseau quits. */
    void saveNow() {
        timer.stop();
        if (saving && !windows.windows().isEmpty()) {
            Session session = snapshot();
            String json = SessionStore.toJson(session);
            if (!json.equals(written)) {
                written = json;
                String version = AppPrefs.getAppVersion();
                writer.submit(() -> write(session, version));
            }
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) log.warn("Saving the session took too long");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(Session session, String version) {
        try {
            SessionStore.write(file, session, null, version);
            log.debug("Saved session: {} window(s), {} tab(s)", session.windows().size(), session.tabCount());
        } catch (IOException e) {
            log.warn("Could not save the session to {}", file, e);
        }
    }
}
