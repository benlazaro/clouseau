package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.core.ParserList;
import com.droidenx.clouseau.core.Session;
import com.droidenx.clouseau.runtime.ClouseauPluginManager;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The application's windows (UC-16, D-18): every {@link MainFrame}, the one used last, and
 * what all of them share: the parser, formatter, highlighter and source lists, the parser
 * last picked in the open dialog, and the lookup behind "one tab per file".
 *
 * <p>EDT only.
 */
@Slf4j
final class WindowManager {

    /** How far a new window is placed from the one it came from. */
    static final int CASCADE = 30;

    private final ClouseauPluginManager pluginManager;
    private final List<MainFrame> windows = new ArrayList<>();
    private MainFrame lastActive;

    private List<LogParser> parsers = List.of();
    private List<LogFormatter> formatters = List.of();
    private List<LogSyntaxHighlighter> syntaxHighlighters = List.of();
    private List<LogSourceProvider> sourceProviders = List.of();
    private int lastParserIndex = 0;

    WindowManager(ClouseauPluginManager pluginManager) {
        this.pluginManager = pluginManager;
        reloadExtensions();
    }

    ClouseauPluginManager pluginManager() { return pluginManager; }

    // ── Extensions ────────────────────────────────────────────────────────────

    List<LogParser> parsers()                       { return parsers; }
    List<LogFormatter> formatters()                 { return formatters; }
    List<LogSyntaxHighlighter> syntaxHighlighters() { return syntaxHighlighters; }
    List<LogSourceProvider> sourceProviders()       { return sourceProviders; }
    int lastParserIndex()                           { return lastParserIndex; }
    void setLastParserIndex(int index)              { lastParserIndex = index; }

    /**
     * Rebuilds the parser list: JSON-defined parsers (built-in and {@code parsers.json})
     * first, then plugin parsers (see {@link ParserList}). Called at startup, after
     * parsers.json is saved, and after a plugin refresh.
     */
    void reloadParsers() {
        parsers = ParserList.combine(UserParsersLoader.load(), pluginManager.getExtensions(LogParser.class));
        if (lastParserIndex > parsers.size()) lastParserIndex = 0;
        log.info("Loaded {} parser(s): {}", parsers.size(), parsers.stream().map(LogParser::getName).toList());
    }

    /**
     * Fetches parsers, formatters, highlighters and sources, including those from plugins.
     * Called at startup and after a plugin refresh. Open tabs keep the extensions they
     * were created with; new tabs, in any window, get the new ones.
     */
    void reloadExtensions() {
        reloadParsers();

        sourceProviders = List.copyOf(pluginManager.getExtensions(LogSourceProvider.class));
        log.info("Loaded {} source provider(s)", sourceProviders.size());

        List<LogFormatter> f = new ArrayList<>();
        f.add(new JsonMessageFormatter());
        f.addAll(pluginManager.getExtensions(LogFormatter.class));
        formatters = List.copyOf(f);
        log.info("Loaded {} formatter(s): {}", formatters.size(), formatters.stream().map(LogFormatter::getName).toList());

        List<LogSyntaxHighlighter> h = new ArrayList<>();
        h.add(new JsonSyntaxHighlighter());
        h.addAll(pluginManager.getExtensions(LogSyntaxHighlighter.class));
        syntaxHighlighters = List.copyOf(h);
        log.info("Loaded {} syntax highlighter(s): {}", syntaxHighlighters.size(),
                syntaxHighlighters.stream().map(LogSyntaxHighlighter::getName).toList());
    }

    // ── Windows ───────────────────────────────────────────────────────────────

    /** The open windows, in the order they were opened. */
    List<MainFrame> windows() { return List.copyOf(windows); }

    /** The window that was active last; where files from outside a window open. */
    MainFrame lastActive() {
        if (lastActive != null && windows.contains(lastActive)) return lastActive;
        return windows.isEmpty() ? null : windows.get(windows.size() - 1);
    }

    void activated(MainFrame window) { lastActive = window; }

    /** Whether a window is on screen yet, so files can open in it. */
    boolean isShown() {
        MainFrame w = lastActive();
        return w != null && w.isVisible();
    }

    /** The first window, centred on the primary screen. Not shown yet. */
    MainFrame createFirstWindow() {
        MainFrame window = new MainFrame(this);
        window.setSize(1280, 800);
        window.setLocationRelativeTo(null);
        windows.add(window);
        lastActive = window;
        return window;
    }

    /** A new, empty window cascaded from {@code from}, shown and in front. */
    MainFrame newWindow(MainFrame from) {
        Rectangle b = from.getBounds();
        if ((from.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
            // A maximized window's bounds are the screen; cascade from a normal size inside it.
            b = new Rectangle(b.x, b.y, Math.max(800, b.width * 3 / 4), Math.max(500, b.height * 3 / 4));
        }
        return newWindowAt(new Rectangle(b.x + CASCADE, b.y + CASCADE, b.width, b.height));
    }

    /** A new, empty window at {@code bounds}, moved onto a screen if needed, shown and in front. */
    MainFrame newWindowAt(Rectangle bounds) {
        MainFrame window = new MainFrame(this);
        window.setBounds(fitOnScreen(bounds, screenBounds()));
        windows.add(window);
        window.setVisible(true);
        window.toFront();
        lastActive = window;
        return window;
    }

    /** Called by a window once it has closed itself; quits after the last one. */
    void closed(MainFrame window) {
        windows.remove(window);
        if (lastActive == window) lastActive = null;
        sessionChanged();
        if (windows.isEmpty()) exit();
    }

    /** Quits Clouseau: File → Exit, or closing the last window. Saves the session first. */
    void exit() {
        log.info("Exiting");
        if (session != null) session.saveNow();
        System.exit(0);
    }

    // ── Session (UC-17, D-19) ─────────────────────────────────────────────────

    private SessionManager session;

    void setSessionManager(SessionManager session) { this.session = session; }

    /** A window or tab changed; the session is saved shortly. */
    void sessionChanged() {
        if (session != null) session.changed();
    }

    /** Settings → On startup changed: keep saving the session only while it will be restored. */
    void restoreSettingChanged() {
        if (session == null) return;
        if (AppPrefs.isRestoreSession()) {
            session.start();
            session.changed();
        } else {
            session.stop();
        }
    }

    /**
     * Opens a session's windows and tabs. Windows are built before they are shown, so only
     * each one's selected tab loads now. A window whose usual place is on a monitor that
     * isn't connected opens on the primary screen, cascaded; it keeps its usual place for
     * next time unless the user moves it. Returns the windows, not shown yet.
     */
    List<MainFrame> restore(Session s, String sessionName) {
        List<Rectangle> screens = screenBounds();
        List<MainFrame> opened = new ArrayList<>();
        int displaced = 0;
        for (Session.Window sw : s.windows()) {
            Session.Bounds b = sw.bounds();
            Rectangle usual = b != null
                    ? new Rectangle(b.x(), b.y(), Math.max(400, b.width()), Math.max(300, b.height()))
                    : defaultBounds(screens);
            Rectangle at = placeRestored(usual, screens, displaced);
            if (!isReachable(usual, screens)) displaced++;
            MainFrame window = new MainFrame(this);
            window.placeRestored(at, usual, sw.screen());
            for (Session.Tab t : sw.tabs()) {
                try {
                    window.restoreTab(t, sessionName);
                } catch (RuntimeException e) {
                    log.warn("Could not restore a {} tab", t.kind(), e);
                }
            }
            window.selectRestoredTab(sw.selectedTab());
            if (sw.maximized()) window.setExtendedState(Frame.MAXIMIZED_BOTH);
            windows.add(window);
            opened.add(window);
        }
        if (!opened.isEmpty()) lastActive = opened.get(0);
        log.info("Restored {} window(s) with {} tab(s)", opened.size(), s.tabCount());
        return opened;
    }

    /** How much of a window's top edge must be on a screen for it to be reachable. */
    static final int GRAB_WIDTH = 100;
    static final int GRAB_HEIGHT = 20;

    /** Whether enough of {@code r}'s title bar is on some screen to grab it with the mouse. */
    static boolean isReachable(Rectangle r, List<Rectangle> screens) {
        Rectangle titleBar = new Rectangle(r.x, r.y, r.width, GRAB_HEIGHT);
        for (Rectangle s : screens) {
            Rectangle i = s.intersection(titleBar);
            if (!i.isEmpty() && i.width >= GRAB_WIDTH && i.height >= GRAB_HEIGHT) return true;
        }
        return false;
    }

    /**
     * Where a restored window goes: its usual place if its title bar is on a screen (only
     * shrunk to fit that screen), otherwise the primary screen ({@code screens.get(0)}),
     * cascaded by {@code displaced}, the number of windows already moved there.
     */
    static Rectangle placeRestored(Rectangle usual, List<Rectangle> screens, int displaced) {
        if (screens.isEmpty()) return new Rectangle(usual);
        if (isReachable(usual, screens)) {
            Rectangle screen = screens.stream().filter(s -> s.intersects(usual))
                    .max(java.util.Comparator.comparingLong(s -> area(s.intersection(usual))))
                    .orElse(screens.get(0));
            if (usual.width <= screen.width && usual.height <= screen.height) return new Rectangle(usual);
            return fitOnScreen(usual, List.of(screen));
        }
        Rectangle primary = screens.get(0);
        int w = Math.min(usual.width, primary.width);
        int h = Math.min(usual.height, primary.height);
        int step = CASCADE * (displaced + 1);
        Rectangle at = new Rectangle(primary.x + step, primary.y + step, w, h);
        return fitOnScreen(at, List.of(primary));
    }

    /** A first window's place: 1280×800 centred on the primary screen. */
    private static Rectangle defaultBounds(List<Rectangle> screens) {
        if (screens.isEmpty()) return new Rectangle(0, 0, 1280, 800);
        Rectangle p = screens.get(0);
        int w = Math.min(1280, p.width), h = Math.min(800, p.height);
        return new Rectangle(p.x + (p.width - w) / 2, p.y + (p.height - h) / 2, w, h);
    }

    private static long area(Rectangle r) {
        return r.isEmpty() ? 0 : (long) r.width * r.height;
    }

    /** The Clouseau window under a point on the screen other than {@code except}, if any. */
    Optional<MainFrame> windowAt(Point screen, MainFrame except) {
        for (MainFrame w : windows) {
            if (w != except && w.isShowing() && w.getBounds().contains(screen)) return Optional.of(w);
        }
        return Optional.empty();
    }

    /** Each window's 1-based number, as the Move to menu shows it. */
    int numberOf(MainFrame window) { return windows.indexOf(window) + 1; }

    // ── Tabs across windows ───────────────────────────────────────────────────

    /** Every log tab in every window. */
    List<LogPanel> allPanels() {
        List<LogPanel> all = new ArrayList<>();
        for (MainFrame w : windows) all.addAll(w.panels());
        return all;
    }

    /** The tab that shows {@code file} (and {@code zipEntry}), in any window ("one tab per file"). */
    Optional<LogPanel> findTab(Path file, String zipEntry) {
        Path abs = file.toAbsolutePath();
        return allPanels().stream()
                .filter(lp -> abs.equals(lp.getFile()) && Objects.equals(zipEntry, lp.getZipEntry()))
                .findFirst();
    }

    /** The window that holds {@code c}, or null if it isn't in one (yet). */
    static MainFrame windowOf(Component c) {
        return SwingUtilities.getWindowAncestor(c) instanceof MainFrame w ? w : null;
    }

    void refreshRecentMenus() { windows.forEach(MainFrame::refreshRecentMenu); }

    // ── Opening from outside a window ─────────────────────────────────────────

    /** Opens a file sent by a second launch or the OS, in the window used last. */
    void openFile(Path file) {
        MainFrame w = lastActive();
        if (w != null) w.openFile(file);
    }

    /** Opens files given on the command line, in the window used last. */
    void openFiles(List<Path> files) {
        MainFrame w = lastActive();
        if (w != null) w.openFiles(files);
    }

    // ── Placement ─────────────────────────────────────────────────────────────

    /** The usable bounds (without task bars) of every screen, the primary screen first. */
    static List<Rectangle> screenBounds() {
        List<Rectangle> screens = new ArrayList<>();
        if (GraphicsEnvironment.isHeadless()) return screens;
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        List<GraphicsDevice> devices = new ArrayList<>(List.of(ge.getScreenDevices()));
        GraphicsDevice primary = ge.getDefaultScreenDevice();
        if (devices.remove(primary)) devices.add(0, primary);
        for (GraphicsDevice d : devices) {
            GraphicsConfiguration gc = d.getDefaultConfiguration();
            Rectangle r = gc.getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            screens.add(new Rectangle(r.x + in.left, r.y + in.top,
                    r.width - in.left - in.right, r.height - in.top - in.bottom));
        }
        return screens;
    }

    /**
     * {@code bounds} moved, and shrunk if needed, to lie inside the screen that contains its
     * top-left corner (or the first screen), so a new window never opens off screen.
     */
    static Rectangle fitOnScreen(Rectangle bounds, List<Rectangle> screens) {
        if (screens.isEmpty()) return new Rectangle(bounds);
        Rectangle screen = screens.stream()
                .filter(s -> s.contains(bounds.getLocation()))
                .findFirst().orElse(screens.get(0));
        int w = Math.min(bounds.width, screen.width);
        int h = Math.min(bounds.height, screen.height);
        int x = Math.max(screen.x, Math.min(bounds.x, screen.x + screen.width - w));
        int y = Math.max(screen.y, Math.min(bounds.y, screen.y + screen.height - h));
        return new Rectangle(x, y, w, h);
    }
}
