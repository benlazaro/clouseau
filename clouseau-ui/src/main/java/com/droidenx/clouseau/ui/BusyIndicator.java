package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import javax.swing.plaf.LayerUI;
import java.awt.*;

/**
 * Shows that a filter or a find is still reading the file (DESIGN.md 6.16): a thin bar
 * that fills along the top of the rows, under the column headers, and the rows faded, so the
 * wait is plain to see wherever the eye is. Both appear only once the work has run for
 * {@link #DELAY_MS}, so the quick filters
 * and finds most files get don't flicker it. It only paints: the rows can still be
 * scrolled, selected and read.
 * <p>
 * Wraps a table's scroll pane ({@link #component()} goes where the scroll pane went), and is
 * fed the percentages the filter and the find already report: 0–100 while running, -1 when
 * done. Colors are read when painted, so a theme switch needs nothing. EDT only.
 */
final class BusyIndicator {

    /** How long work runs before it shows. */
    static final int DELAY_MS = 250;
    /** The bar's height, in pixels. */
    static final int BAR_HEIGHT = 3;
    /** How much of the table's background covers the rows while a filter or find runs. */
    static final float FADE = 0.75f;

    private final JScrollPane scroll;
    private final JLayer<JScrollPane> layer;
    private final Timer delay;
    private int filterPercent = -1;
    private int findPercent = -1;
    /** True once the work has run for {@link #DELAY_MS}; false again when it ends. */
    private boolean shown;

    BusyIndicator(JScrollPane scroll) {
        this.scroll = scroll;
        this.layer = new JLayer<>(scroll, new Ui());
        delay = new Timer(DELAY_MS, e -> {
            shown = busy();
            layer.repaint();
        });
        delay.setRepeats(false);
    }

    /** The scroll pane with the indicator over it; add this in its place. */
    JComponent component() { return layer; }

    /** The running filter's progress while it reads the file, or -1 when it's done. */
    void filterProgress(int percent) {
        filterPercent = percent;
        changed();
    }

    /** The running find's progress while it reads the file, or -1 when it's done or cancelled. */
    void findProgress(int percent) {
        findPercent = percent;
        changed();
    }

    /** Whether the bar shows; for tests. */
    boolean showsBar() { return shown && busy(); }

    /** Whether the rows are faded; for tests. */
    boolean fadesRows() { return shown && busy(); }

    /** The percentage the bar shows: a running filter's, else a running find's; -1 with neither. */
    int percent() { return filterPercent >= 0 ? filterPercent : findPercent; }

    private boolean busy() { return filterPercent >= 0 || findPercent >= 0; }

    private void changed() {
        if (!busy()) {
            delay.stop();
            shown = false;
        } else if (!shown && !delay.isRunning()) {
            delay.start();
        }
        if (shown || !busy()) layer.repaint();
    }

    private final class Ui extends LayerUI<JScrollPane> {
        @Override
        public void paint(Graphics g, JComponent c) {
            super.paint(g, c);
            if (!shown || !busy()) return;
            JViewport viewport = scroll.getViewport();
            Rectangle rows = SwingUtilities.convertRectangle(viewport.getParent(), viewport.getBounds(), c);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                Color bg = viewport.getView() != null ? viewport.getView().getBackground() : c.getBackground();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, FADE));
                g2.setColor(bg);
                g2.fillRect(rows.x, rows.y, rows.width, rows.height);
                g2.setComposite(AlphaComposite.SrcOver);
                g2.setColor(ClouseauColors.progressBackground());
                g2.fillRect(rows.x, rows.y, rows.width, BAR_HEIGHT);
                g2.setColor(ClouseauColors.progressForeground());
                int filled = (int) ((long) rows.width * Math.max(0, Math.min(100, percent())) / 100);
                g2.fillRect(rows.x, rows.y, filled, BAR_HEIGHT);
            } finally {
                g2.dispose();
            }
        }
    }
}
