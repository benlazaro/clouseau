package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.AnnotationStore;
import com.droidenx.clouseau.core.MergeLoad;
import com.droidenx.clouseau.runtime.ClouseauPluginManager;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Dev-only: recreates the user guide's screenshot scenes and saves each window as a PNG.
 * Not part of the app. Run it with {@code ./gradlew :clouseau-ui:screenshots}; it needs a
 * desktop, takes over the screen for about a minute, and must not run beside another
 * Clouseau.
 *
 * <p>Arguments: the folder with the logs to show ({@code app.log}, {@code build-output.txt},
 * {@code gateway.log}, {@code orders-service.log}, {@code payments-service.log}) and the
 * folder to write the PNGs to. Run it with {@code -Dclouseau.data.dir} set to an empty
 * folder, so the user's own settings, session and parsers are neither used nor changed.
 */
public final class ScreenshotTool {

    private static Path logs;
    private static Path out;

    public static void main(String[] args) throws Exception {
        logs = Path.of(args[0]).toAbsolutePath();
        out = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(out);
        seedDataDir();
        writeHighlights();

        Method applyTheme = ClouseauApp.class.getDeclaredMethod("applyTheme");
        applyTheme.setAccessible(true);
        onEdt(() -> applyTheme.invoke(null));

        ClouseauPluginManager plugins = new ClouseauPluginManager(AppDirs.dataDir().resolve("plugins"));
        plugins.loadAll();
        MainFrame frame = onEdt(() -> {
            WindowManager windows = new WindowManager(plugins);
            MainFrame f = windows.createFirstWindow();
            Rectangle work = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int w = Math.min(1600, work.width), h = Math.min(1000, work.height);
            f.setBounds(work.x + (work.width - w) / 2, work.y + (work.height - h) / 2, w, h);
            f.setAlwaysOnTop(true);
            f.setVisible(true);
            f.toFront();
            return f;
        });
        try {
            mainView(frame);
            loggerFilter(frame);
            plainText(frame);
            mergedView(frame);
            openDialog(frame);
            parserEditor(frame);
            lightTheme(frame);
        } finally {
            System.exit(0);
        }
    }

    // ── Scenes ────────────────────────────────────────────────────────────────

    private static void mainView(MainFrame frame) throws Exception {
        onEdt(() -> { frame.openFile(logs.resolve("app.log")); return null; });
        onEdt(() -> { frame.openFile(logs.resolve("build-output.txt")); return null; });
        settle(1500);
        selectTab(frame, 0);
        TabPanel tab = waitForRows(frame, 0);
        selectRow(tab, "Payment gateway response");
        settle(800);
        capture(frame, "main-view.png");
    }

    private static void loggerFilter(MainFrame frame) throws Exception {
        TabPanel tab = onEdt(() -> tabAt(frame, 0));
        onEdt(() -> { tab.getLogTable().clearSelection(); return null; });
        JButton loggers = onEdt(() -> find(tab, JButton.class,
                b -> b.getText() != null && b.getText().startsWith(Messages.get("filter.loggers.all").split(" ")[0])));
        onEdt(() -> { loggers.doClick(); return null; });
        settle(500);
        JTree tree = onEdt(() -> findInWindows(JTree.class, t -> t.isShowing()));
        onEdt(() -> { untick(tree, "app"); untick(tree, "db"); return null; });
        settle(800);
        capture(frame, "logger-filter.png");
        onEdt(() -> { MenuSelectionManager.defaultManager().clearSelectedPath(); return null; });
        settle(300);
    }

    private static void plainText(MainFrame frame) throws Exception {
        selectTab(frame, 1);
        settle(800);
        // Focus the text, not the notice strip's first button.
        onEdt(() -> {
            JComponent text = find(tabAt(frame, 1), JComponent.class, c -> c.isShowing()
                    && (c instanceof javax.swing.text.JTextComponent || c instanceof JTable || c instanceof JList));
            if (text != null) text.requestFocusInWindow();
            return null;
        });
        settle(300);
        capture(frame, "plain-text.png");
    }

    private static void mergedView(MainFrame frame) throws Exception {
        Method openMerged = MainFrame.class.getDeclaredMethod("openMerged", List.class, Optional.class);
        openMerged.setAccessible(true);
        List<MergeLoad.File> files = List.of(
                new MergeLoad.File(logs.resolve("gateway.log"), null),
                new MergeLoad.File(logs.resolve("orders-service.log"), null),
                new MergeLoad.File(logs.resolve("payments-service.log"), null));
        onEdt(() -> openMerged.invoke(frame, files, Optional.<LogParser>empty()));
        settle(1500);
        TabPanel tab = waitForRows(frame, 2);
        selectRow(tab, "Checkout failed");
        settle(800);
        capture(frame, "merged-view.png");
    }

    private static void openDialog(MainFrame frame) throws Exception {
        Method open = MainFrame.class.getDeclaredMethod("openFile");
        open.setAccessible(true);
        SwingUtilities.invokeLater(() -> {
            try { open.invoke(frame); } catch (Exception e) { throw new RuntimeException(e); }
        });
        JDialog dialog = waitForDialog();
        onEdt(() -> {
            JFileChooser chooser = find(dialog, JFileChooser.class, c -> true);
            // Three services: each file's verdict, Open as, and a preview as the table shows it.
            chooser.setCurrentDirectory(logs.toFile());
            chooser.setSelectedFiles(new java.io.File[] {
                    logs.resolve("gateway.log").toFile(), logs.resolve("orders-service.log").toFile(),
                    logs.resolve("payments-service.log").toFile() });
            return null;
        });
        settle(1500);
        capture(dialog, "open-dialog.png");
        onEdt(() -> { dialog.dispose(); return null; });
        settle(300);
    }

    private static void parserEditor(MainFrame frame) throws Exception {
        SwingUtilities.invokeLater(() -> new ParserEditorDialog(frame, null).setVisible(true));
        JDialog dialog = waitForDialog();
        onEdt(() -> {
            // The seeded Edge Router parser: the first under YOURS (row 0 is the group title).
            @SuppressWarnings("unchecked")
            JList<Object> list = find(dialog, JList.class, l -> true);
            list.setSelectedIndex(1);
            return null;
        });
        settle(500);
        onEdt(() -> {
            // The Test section's sample line: the first empty single-line field.
            JTextField sample = find(dialog, JTextField.class,
                    t -> t.isShowing() && t.isEditable() && t.getText().isEmpty());
            sample.setText("2026-03-27 08:15:02.480 INFO --- [gw-eu-1] [edge-router-7] [io-worker-2] "
                    + "com.example.edge.Forwarder - Forwarded 128 bytes to 10.0.4.22:51234");
            sample.setCaretPosition(0);
            return null;
        });
        settle(800);
        capture(dialog, "parser-editor.png");
        onEdt(() -> { dialog.dispose(); return null; });
    }

    /**
     * The README's main view again in the Light theme, switched live as View → Theme does:
     * {@code main-view-light.png}.
     */
    private static void lightTheme(MainFrame frame) throws Exception {
        onEdt(() -> {
            com.droidenx.clouseau.ui.theme.ThemeManager.applyTheme("Light");
            com.droidenx.clouseau.ui.theme.ThemeManager.updateUI();
            return null;
        });
        settle(1000);
        selectTab(frame, 0);
        settle(500);
        // Undo the logger filter set for logger-filter.png, so the scene matches main-view.png.
        TabPanel tab = onEdt(() -> tabAt(frame, 0));
        onEdt(() -> {
            find(tab, JButton.class, b -> Messages.get("filter.clear").equals(b.getText())).doClick();
            return null;
        });
        settle(800);
        selectRow(waitForRows(frame, 0), "Payment gateway response");
        settle(800);
        capture(frame, "main-view-light.png");
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    /** Favorites and the open dialog's folder point at the logs; one user parser to edit. */
    private static void seedDataDir() throws Exception {
        AppPrefs.saveFavorites(List.of(logs));
        AppPrefs.setLastOpenDir(logs.toFile());
        Files.writeString(AppDirs.dataDir().resolve("parsers.json"), """
                [
                  {
                    "name": "Edge Router",
                    "type": "template",
                    "source": "TIMESTAMP{yyyy-MM-dd HH:mm:ss.SSS} LEVEL --- [{region}] [{node}] [THREAD] LOGGER - MESSAGE",
                    "pattern": "^(?<timestamp>\\\\d{4}-\\\\d{1,2}-\\\\d{1,2} \\\\d{1,2}:\\\\d{1,2}:\\\\d{1,2}\\\\.\\\\d{3})\\\\s+(?<level>TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\\\s+---\\\\s+\\\\[(?<region>[^\\\\]]*)\\\\]\\\\s+\\\\[(?<node>[^\\\\]]*)\\\\]\\\\s+\\\\[(?<thread>[^\\\\]]+)\\\\]\\\\s+(?<logger>\\\\S+)\\\\s+-\\\\s+(?<message>.*)$",
                    "timestampFormat": "yyyy-MM-dd HH:mm:ss.SSS"
                  }
                ]
                """, StandardCharsets.UTF_8);
    }

    /** Highlights three rows of app.log, as a user would from the row menu. */
    private static void writeHighlights() throws Exception {
        List<String> lines = Files.readAllLines(logs.resolve("app.log"), StandardCharsets.UTF_8);
        List<String> entries = lines.stream().filter(l -> !l.isBlank()).toList();
        StringBuilder marks = new StringBuilder();
        String[][] wanted = {
                { "Order ORD-20260327-00441 created", "4" },
                { "Payment gateway response", "4" },
                { "Heap: used=", "1" },
        };
        for (String[] w : wanted) {
            for (int i = 0; i < entries.size(); i++) {
                if (!entries.get(i).contains(w[0])) continue;
                if (!marks.isEmpty()) marks.append(",\n");
                marks.append("    {\"lineHash\": \"").append(AnnotationStore.hashOf(entries.get(i)))
                        .append("\", \"line\": ").append(i + 1).append(", \"color\": ").append(w[1]).append("}");
                break;
            }
        }
        Files.writeString(logs.resolve("app.log.clouseau"),
                "{\n  \"version\": 2,\n  \"annotations\": [],\n  \"highlights\": [\n" + marks + "\n  ]\n}\n",
                StandardCharsets.UTF_8);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static TabPanel tabAt(MainFrame frame, int index) {
        JTabbedPane tabs = find(frame, JTabbedPane.class, t -> t.getTabCount() > 0);
        return (TabPanel) tabs.getComponentAt(index);
    }

    private static void selectTab(MainFrame frame, int index) throws Exception {
        onEdt(() -> {
            find(frame, JTabbedPane.class, t -> t.getTabCount() > 0).setSelectedIndex(index);
            return null;
        });
    }

    private static TabPanel waitForRows(MainFrame frame, int index) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            TabPanel tab = onEdt(() -> {
                JTabbedPane tabs = find(frame, JTabbedPane.class, t -> t.getTabCount() > index);
                return tabs == null ? null : (TabPanel) tabs.getComponentAt(index);
            });
            if (tab != null && onEdt(() -> tab.getLogTable().getRowCount() > 0)) {
                settle(500);
                return tab;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("tab " + index + " has no rows");
    }

    private static void selectRow(TabPanel tab, String text) throws Exception {
        onEdt(() -> {
            JTable table = tab.getLogTable();
            LogTableModel model = (LogTableModel) table.getModel();
            for (int v = 0; v < table.getRowCount(); v++) {
                var e = model.getEntry(table.convertRowIndexToModel(v));
                if (e != null && e.message() != null && e.message().contains(text)) {
                    table.setRowSelectionInterval(v, v);
                    table.scrollRectToVisible(table.getCellRect(Math.max(0, v - 15), 0, true));
                    table.requestFocusInWindow();
                    return null;
                }
            }
            throw new IllegalStateException("no row with " + text);
        });
    }

    /** Clicks a logger node's checkbox, as the popup's mouse listener expects. */
    private static void untick(JTree tree, String segment) {
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            Object user = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (!String.valueOf(user).contains("segment=" + segment + ",")) continue;
            Rectangle r = tree.getRowBounds(row);
            int x = r.x + 8, y = r.y + r.height / 2;
            long now = System.currentTimeMillis();
            for (int id : new int[] { MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED }) {
                tree.dispatchEvent(new MouseEvent(tree, id, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
            }
            return;
        }
        throw new IllegalStateException("no logger node " + segment);
    }

    private static JDialog waitForDialog() throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            JDialog d = onEdt(() -> {
                for (Window w : Window.getWindows()) {
                    if (w instanceof JDialog jd && jd.isShowing()) return jd;
                }
                return null;
            });
            if (d != null) {
                onEdt(() -> { d.setAlwaysOnTop(true); d.toFront(); return null; });
                settle(800);
                return d;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("no dialog");
    }

    private static <T extends Component> T findInWindows(Class<T> type, Predicate<T> test) {
        for (Window w : Window.getWindows()) {
            T found = find(w, type, test);
            if (found != null) return found;
        }
        return null;
    }

    private static <T extends Component> T find(Component root, Class<T> type, Predicate<T> test) {
        if (type.isInstance(root) && test.test(type.cast(root))) return type.cast(root);
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                T found = find(child, type, test);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Saves the window's area of the screen, at the screen's full resolution. */
    private static void capture(Window window, String name) throws Exception {
        // The content, not the window: on Windows a window's bounds include an invisible
        // resize border, which would capture whatever is behind it.
        Rectangle bounds = onEdt(() -> {
            JRootPane root = ((RootPaneContainer) window).getRootPane();
            // Less the translucent 1-pixel outline Windows 11 draws around a window.
            Rectangle r = new Rectangle(root.getLocationOnScreen(), root.getSize());
            r.grow(-1, -1);
            return r;
        });
        MultiResolutionImage shot = new Robot().createMultiResolutionScreenCapture(bounds);
        List<Image> variants = new ArrayList<>(shot.getResolutionVariants());
        Image biggest = variants.get(variants.size() - 1);
        BufferedImage img = new BufferedImage(biggest.getWidth(null), biggest.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(biggest, 0, 0, null);
        g.dispose();
        File file = out.resolve(name).toFile();
        ImageIO.write(img, "png", file);
        System.err.println("Saved " + file + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }

    private static void settle(long millis) throws Exception {
        Thread.sleep(millis);
        onEdt(() -> null);   // let queued repaints run
        Thread.sleep(200);
    }

    private static <T> T onEdt(Callable<T> c) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { result.set(c.call()); } catch (Exception e) { failure.set(e); }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
}
