package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.core.ParserList;
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
        if (windows.isEmpty()) exit();
    }

    /** Quits Clouseau: File → Exit, or closing the last window. */
    void exit() {
        log.info("Exiting");
        System.exit(0);
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

    /** The usable bounds (without task bars) of every screen. */
    static List<Rectangle> screenBounds() {
        List<Rectangle> screens = new ArrayList<>();
        if (GraphicsEnvironment.isHeadless()) return screens;
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
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
