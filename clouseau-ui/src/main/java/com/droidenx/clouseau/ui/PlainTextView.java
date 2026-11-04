package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.CopyingInputStream;
import com.droidenx.clouseau.core.FileFollower;
import com.droidenx.clouseau.core.LineScanner;
import com.droidenx.clouseau.core.LogReaders;
import com.droidenx.clouseau.core.TextLines;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A file no parser recognizes, shown as read-only plain text (UC-13): every line as
 * written, blank ones included, with line numbers, Find and copy, and following while the
 * file grows. A banner offers to create a parser for it.
 *
 * <p>Lines are read back from disk on demand ({@link TextLines}), so a large file costs
 * 12 bytes of heap per line. A compressed file is read from its unpacked copy and isn't
 * followed. When a followed file is rotated, the view reloads it from the start. Must be
 * used on the EDT.
 */
@Slf4j
final class PlainTextView extends JPanel {

    /** Follow mode's backstop poll, as in {@code LogPanel}. */
    private static final long FOLLOW_SAFETY_POLL_MS = 2_000;
    /** Widest the text column gets, in pixels; longer lines are cut off. */
    private static final int MAX_TEXT_WIDTH = 40_000;

    private final JLabel bannerText = new JLabel();
    private final JToggleButton followBtn = new JToggleButton(Messages.get("toolbar.follow"));
    private final LinesModel model = new LinesModel();
    private final JTable table = new JTable(model);
    private final FindBar findBar;
    private final Runnable onLinesChanged;
    private final ExecutorService findExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clouseau-plain-find");
        t.setDaemon(true);
        return t;
    });

    private TextLines lines;
    private Path file;
    private String entry;
    private Path copy;
    private boolean follow;
    private SwingWorker<?, ?> loader;
    private SwingWorker<?, ?> follower;
    /** Where following resumes: the end of the last complete line read. */
    private volatile long position;
    private int maxLineChars;
    private Future<?> findFuture;
    private long findGeneration;

    /**
     * @param onCreateParser runs when the user asks to create a parser for the file
     * @param onFollow       runs when the user turns following on or off
     * @param onLinesChanged runs on the EDT after lines were added (for the status bar)
     */
    PlainTextView(Runnable onCreateParser, Consumer<Boolean> onFollow, Runnable onLinesChanged) {
        super(new BorderLayout());
        this.onLinesChanged = onLinesChanged;

        JPanel banner = new JPanel(new MigLayout("insets 6 10 6 10, gap 10", "[grow][][]", "[center]"));
        ClouseauColors.themedBackground(banner, ClouseauColors::loadingBackground);
        banner.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()));
        bannerText.setIcon(UIManager.getIcon("OptionPane.informationIcon") instanceof Icon i
                ? new ScaledIcon(i, 18) : null);
        JButton create = new JButton(Messages.get("plain.create.parser"));
        create.addActionListener(e -> onCreateParser.run());
        followBtn.setFocusPainted(false);
        FilterBar.applyToggleStyle(followBtn);   // dim when off, as in the filter bar
        followBtn.setToolTipText(Messages.get("toolbar.follow.tooltip"));
        followBtn.addActionListener(e -> onFollow.accept(followBtn.isSelected()));
        banner.add(bannerText, "growx");
        banner.add(create);
        banner.add(followBtn);

        findBar = new FindBar(table, new FindBar.Search() {
            @Override public void find(String lowerQuery, IntConsumer progress, Consumer<int[]> done) {
                findLines(lowerQuery, progress, done);
            }
            @Override public void cancel() { cancelFind(); }
        });

        table.setTableHeader(null);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, table.getFont().getSize());
        table.setFont(mono);
        table.setRowHeight(table.getFontMetrics(mono).getHeight() + 2);
        table.setTransferHandler(new TransferHandler() {
            @Override public int getSourceActions(JComponent c) { return COPY; }
            @Override protected Transferable createTransferable(JComponent c) {
                return new StringSelection(selectedText());
            }
        });
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus,
                                                           int row, int col) {
                super.getTableCellRendererComponent(t, value, sel, false, row, col);
                setFont(mono);
                if (col == 0) {
                    setHorizontalAlignment(RIGHT);
                    setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 10));
                    if (!sel) setForeground(ClouseauColors.dimForeground());
                    if (!sel) setBackground(t.getBackground());
                } else {
                    setHorizontalAlignment(LEFT);
                    setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
                    // setForeground/setBackground stick on this renderer: set both for every cell.
                    if (!sel) {
                        setForeground(t.getForeground());
                        setBackground(findBar.isMatch(row) ? ClouseauColors.searchMatchBackground() : t.getBackground());
                    }
                }
                return this;
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        ClouseauColors.themedBackground(scroll.getViewport(), ClouseauColors::viewportBackground);
        // The text column is at least as wide as the view, which is only known once laid out.
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { sizeColumns(); }
        });

        JPanel top = new JPanel(new MigLayout("insets 0, gap 0, fillx", "[grow]", "[][]"));
        top.add(banner, "growx, wrap");
        top.add(findBar, "growx, hidemode 3");
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    // ── Loading and following ────────────────────────────────────────────────

    /**
     * Shows {@code file} (or the zip {@code entry} in it). A compressed log is unpacked to
     * {@code copy} while it's read; a plain one is followed while {@code follow} is on.
     */
    void load(Path file, String entry, Path copy, boolean follow, String banner) {
        stop();
        this.file = file;
        this.entry = entry;
        this.copy = copy;
        this.follow = follow;
        boolean compressed = entry != null || LogReaders.isCompressed(file);
        followBtn.setSelected(follow && !compressed);
        followBtn.setEnabled(!compressed);
        setBanner(banner);
        findBar.cancel();
        maxLineChars = 0;
        model.setCount(0);

        Path source = compressed ? copy : file;
        if (source == null) {
            log.warn("No unpacked copy of {} to show as plain text", file);
            return;
        }
        try {
            lines = TextLines.open(source);
        } catch (IOException e) {
            log.warn("Can't open {} as plain text", source, e);
            return;
        }
        TextLines target = lines;
        SwingWorker<Long, Integer> worker = new SwingWorker<>() {
            int widest;

            @Override
            protected Long doInBackground() throws Exception {
                InputStream in = compressed
                        ? new CopyingInputStream(LogReaders.openBytes(file, entry), Files.newOutputStream(copy))
                        : Files.newInputStream(file);
                try (InputStream s = in) {
                    long[] lastPublish = {System.nanoTime()};
                    return LineScanner.scanAllLines(s, 0, (offset, length, text) -> {
                        target.add(offset, length);
                        widest = Math.max(widest, text.length());
                        long now = System.nanoTime();
                        if (now - lastPublish[0] > 100_000_000L) {   // ten updates a second
                            lastPublish[0] = now;
                            publish(widest);
                        }
                    }, this::isCancelled);
                }
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (isCancelled() || lines != target) return;
                showLines(target, chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                if (isCancelled() || lines != target) return;
                try {
                    position = get();
                } catch (Exception e) {
                    log.warn("Failed to read {} as plain text", file, e);
                }
                showLines(target, widest);
                if (PlainTextView.this.follow && !compressed) startFollowing();
            }
        };
        loader = worker;
        worker.execute();
    }

    void setBanner(String text) {
        bannerText.setText(text);
    }

    /** Turns following on or off; ignored for a compressed file. */
    void setFollow(boolean on) {
        follow = on;
        if (followBtn.isEnabled()) followBtn.setSelected(on);
        if (!on) stopFollowing();
        else if (follower == null && loader != null && loader.isDone() && lines != null
                && entry == null && !LogReaders.isCompressed(file)) startFollowing();
    }

    private void startFollowing() {
        stopFollowing();
        TextLines target = lines;
        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            volatile boolean rotated;

            @Override
            protected Void doInBackground() {
                try {
                    FileFollower.follow(file, position, new FileFollower.Listener() {
                        int widest;

                        @Override public void line(long offset, int length, String text) {
                            target.add(offset, length);
                            widest = Math.max(widest, text.length());
                            publish(widest);
                        }

                        @Override public void rotated(FileFollower.Rotation how) {
                            rotated = true;
                        }

                        @Override public void position(long p) {
                            if (!rotated) position = p;
                        }
                    }, () -> isCancelled() || rotated, FOLLOW_SAFETY_POLL_MS, true);
                } catch (InterruptedException e) {
                    // following stopped
                }
                return null;
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (isCancelled() || lines != target) return;
                showLines(target, chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                // Rotated: the lines held point into the old file; read the new one from the start.
                if (rotated && !isCancelled() && lines == target) {
                    log.info("{} was rotated; reloading it as plain text", file);
                    load(file, entry, copy, follow, bannerText.getText());
                }
            }
        };
        follower = worker;
        worker.execute();
    }

    private void stopFollowing() {
        if (follower != null) follower.cancel(true);
        follower = null;
    }

    private void showLines(TextLines target, int widest) {
        boolean atBottom = isScrolledToBottom();
        maxLineChars = Math.max(maxLineChars, widest);
        model.setCount(target.size());
        sizeColumns();
        if (follow && atBottom) scrollToBottom();
        onLinesChanged.run();
    }

    private boolean isScrolledToBottom() {
        if (!(table.getParent() instanceof JViewport vp)) return true;
        Rectangle view = vp.getViewRect();
        return view.y + view.height >= table.getHeight() - table.getRowHeight();
    }

    private void scrollToBottom() {
        int last = model.getRowCount() - 1;
        if (last >= 0) table.scrollRectToVisible(table.getCellRect(last, 0, true));
    }

    private void sizeColumns() {
        if (table.getColumnCount() < 2) return;
        FontMetrics fm = table.getFontMetrics(table.getFont());
        TableColumn number = table.getColumnModel().getColumn(0);
        int numberWidth = fm.stringWidth(String.valueOf(Math.max(1, model.getRowCount()))) + 20;
        number.setMinWidth(numberWidth);
        number.setMaxWidth(numberWidth);
        number.setPreferredWidth(numberWidth);
        int textWidth = Math.min(MAX_TEXT_WIDTH, fm.charWidth('m') * (maxLineChars + 2) + 8);
        int viewport = table.getParent() != null ? table.getParent().getWidth() - numberWidth : 0;
        table.getColumnModel().getColumn(1).setPreferredWidth(Math.max(textWidth, viewport));
    }

    /** Stops loading, following and finding; the view keeps what it shows. */
    void stop() {
        if (loader != null) loader.cancel(true);
        loader = null;
        stopFollowing();
        cancelFind();
        if (lines != null) {
            try {
                lines.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
        lines = null;
    }

    /** Stops everything for good; the tab is closing. */
    void dispose() {
        stop();
        findExecutor.shutdownNow();
    }

    int lineCount() {
        return model.getRowCount();
    }

    void openFind() {
        findBar.open();
    }

    // ── Find ─────────────────────────────────────────────────────────────────

    private void findLines(String lowerQuery, IntConsumer progress, Consumer<int[]> done) {
        cancelFind();
        long generation = findGeneration;
        TextLines target = lines;
        int count = model.getRowCount();
        if (target == null) {
            done.accept(new int[0]);
            return;
        }
        findFuture = findExecutor.submit(() -> {
            List<Integer> found = new ArrayList<>();
            int[] lastPct = {-1};
            try {
                target.scan(0, count, (n, text) -> {
                    if (text.toLowerCase().contains(lowerQuery)) found.add(n);
                    int pct = (int) ((n + 1) * 100L / count);
                    if (pct != lastPct[0] && pct % 5 == 0) {
                        lastPct[0] = pct;
                        SwingUtilities.invokeLater(() -> { if (findGeneration == generation) progress.accept(pct); });
                    }
                }, () -> Thread.currentThread().isInterrupted());
            } catch (IOException e) {
                log.warn("Find failed in {}", file, e);
            }
            if (Thread.currentThread().isInterrupted()) return;
            int[] rows = found.stream().mapToInt(Integer::intValue).toArray();
            SwingUtilities.invokeLater(() -> { if (findGeneration == generation) done.accept(rows); });
        });
    }

    private void cancelFind() {
        if (findFuture != null) findFuture.cancel(true);
        findGeneration++;
    }

    // ── Copy ─────────────────────────────────────────────────────────────────

    /** The selected lines' text, one per line, without line numbers. */
    String selectedText() {
        StringBuilder sb = new StringBuilder();
        for (int row : table.getSelectedRows()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(lines != null ? lines.line(row) : "");
        }
        return sb.toString();
    }

    FindBar findBar() {
        return findBar;
    }

    JTable table() {
        return table;
    }

    // ── Model ────────────────────────────────────────────────────────────────

    /** Line number and text; rows are read from {@link #lines} as they are painted. */
    private final class LinesModel extends AbstractTableModel {
        private int count;

        void setCount(int newCount) {
            int old = count;
            count = newCount;
            if (newCount > old) fireTableRowsInserted(old, newCount - 1);
            else if (newCount < old) fireTableDataChanged();
        }

        @Override public int getRowCount() { return count; }
        @Override public int getColumnCount() { return 2; }

        @Override
        public Object getValueAt(int row, int col) {
            if (col == 0) return row + 1;
            String text = lines != null && row < lines.size() ? lines.line(row) : "";
            return text.indexOf('\t') >= 0 ? text.replace("\t", "    ") : text;
        }
    }

    /** An icon drawn at {@code size} pixels. */
    private record ScaledIcon(Icon icon, int size) implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.translate(x, y);
            g2.scale(size / (double) icon.getIconWidth(), size / (double) icon.getIconHeight());
            icon.paintIcon(c, g2, 0, 0);
            g2.dispose();
        }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }
}
