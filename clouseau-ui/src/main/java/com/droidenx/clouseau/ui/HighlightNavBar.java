package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;

/**
 * The highlight navigation bar of one tab: shown while any row is highlighted, with a
 * swatch per highlight color in use ("All" or one color) and buttons that step through
 * the highlighted rows. Must be used on the EDT.
 */
final class HighlightNavBar extends JPanel {

    private final JTable table;
    private final LogTableModel model;
    private final JPanel swatches = new JPanel(new MigLayout("insets 0, gapx 3", "", "[center]"));
    private final JLabel position = new JLabel("0 / 0");
    private final JButton prevBtn;
    private final JButton nextBtn;
    /** The color being navigated, or null for all highlighted rows. */
    private Color navColor = null;

    HighlightNavBar(JTable table, LogTableModel model) {
        super(new MigLayout("insets 3 8 3 8, gapy 0", "[][]10[]8[grow]", "[center]"));
        this.table = table;
        this.model = model;
        setBorder(new FindBar.BarSeparator());
        setVisible(false);

        JLabel label = new JLabel(Messages.get("highlight.nav.label"));
        ClouseauColors.themedForeground(label, ClouseauColors::dimForeground);
        label.setFont(label.getFont().deriveFont(12f));

        swatches.setOpaque(false);

        ClouseauColors.themedForeground(position, ClouseauColors::dimForeground);
        position.setFont(position.getFont().deriveFont(12f));

        prevBtn = FindBar.arrowButton("\u25b2");
        nextBtn = FindBar.arrowButton("\u25bc");
        prevBtn.setToolTipText(Messages.get("highlight.nav.prev.tooltip"));
        nextBtn.setToolTipText(Messages.get("highlight.nav.next.tooltip"));
        prevBtn.addActionListener(e -> navigate(-1));
        nextBtn.addActionListener(e -> navigate(+1));

        JPanel arrows = new JPanel(new MigLayout("insets 0", "[]4[]", "[]"));
        arrows.setOpaque(false);
        arrows.add(prevBtn);
        arrows.add(nextBtn);

        // One group on the left: the chips, the arrows right after them, then the count. Pick
        // a color and step through it without crossing the window, and the arrows stay put
        // while the count's text changes length.
        // Everything centered on one line, the chips included (a FlowLayout put them at the
        // top of their row, above the label's middle).
        add(label, "aligny center");
        add(swatches, "aligny center");
        add(arrows, "aligny center");
        add(position, "aligny center");
    }

    /** The find bar above draws its line only while this bar is under it. */
    private void repaintBarAbove() {
        if (getParent() != null) getParent().repaint();
    }

    /**
     * Rebuilds the chips after highlights or filters changed; hides the bar when nothing is
     * highlighted. Highlights the filters hide still count: they're saved and come back, so
     * the bar stays and says how many are hidden, and a color whose rows are all hidden
     * shows a dimmed chip.
     */
    void refresh() {
        Map<Color, int[]> counts = model.highlightCounts();
        if (counts.isEmpty()) {
            setVisible(false);
            navColor = null;
            shownChips = "";
            repaintBarAbove();
            return;
        }
        // If the selected color was removed, fall back to "All"
        if (navColor != null && !counts.containsKey(navColor)) navColor = null;
        if (!isVisible()) {
            setVisible(true);
            repaintBarAbove();
        }

        // Rebuild only when the chips would change: refresh also runs as live lines arrive.
        StringBuilder key = new StringBuilder(String.valueOf(navColor));
        Color[] palette = ClouseauColors.highlightColors();
        for (Color c : palette) {
            int[] n = counts.get(c);
            if (n != null) key.append('|').append(c.getRGB()).append(':').append(n[0]).append('/').append(n[1]);
        }
        if (!key.toString().equals(shownChips)) {
            shownChips = key.toString();
            buildChips(counts, palette);
        }
        updatePosition();
    }

    /** What the chips show now (colors, counts, selection), to skip rebuilding them. */
    private String shownChips = "";

    private void buildChips(Map<Color, int[]> counts, Color[] palette) {
        swatches.removeAll();

        final Color navColorSnap = navColor;
        JButton allBtn = new JButton(Messages.get("highlight.nav.all")) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(navColorSnap == null ? ClouseauColors.highlightNavSelected() : ClouseauColors.highlightNavUnselected());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                g2.setColor(navColorSnap == null ? Color.WHITE : ClouseauColors.dimForeground());
                FontMetrics fm = g2.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(getText())) / 2;
                int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(getText(), x, y);
                g2.dispose();
            }
        };
        allBtn.setPreferredSize(new Dimension(30, 16));
        allBtn.setBorderPainted(false);
        allBtn.setContentAreaFilled(false);
        allBtn.setFocusPainted(false);
        allBtn.setFont(allBtn.getFont().deriveFont(11f));
        allBtn.addActionListener(e -> { navColor = null; refresh(); });
        swatches.add(allBtn, "w 30!, h 16!");

        // Color swatches in highlight palette order
        for (int slot = 0; slot < palette.length; slot++) {
            final Color c = palette[slot];
            int[] n = counts.get(c);
            if (n == null) continue;
            final boolean selected = c.equals(navColor);
            final boolean allHidden = n[1] == 0;
            JButton swatch = new JButton() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    // A color whose rows the filters all hide reads faded, like a disabled control.
                    if (allHidden) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
                    g2.setColor(c);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                    if (selected) {
                        g2.setColor(Color.WHITE);
                        g2.setStroke(new BasicStroke(1.5f));
                        g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 4, 4);
                    }
                    g2.dispose();
                }
            };
            swatch.setPreferredSize(new Dimension(16, 16));
            swatch.setBorderPainted(false);
            swatch.setContentAreaFilled(false);
            swatch.setFocusPainted(false);
            swatch.setToolTipText(chipTip(slot, n[0], n[1]));
            swatch.addActionListener(e -> { navColor = c; refresh(); });
            swatches.add(swatch, "w 16!, h 16!");
        }

        swatches.revalidate();
        swatches.repaint();
    }

    /** "Blue: 4 highlighted", "… (1 hidden by filters)", "… all hidden by filters". */
    private static String chipTip(int slot, int highlighted, int shown) {
        String name = Messages.get("highlight.color." + slot);
        int hidden = highlighted - shown;
        String key = hidden == 0 ? "highlight.nav.chip.tooltip"
                   : shown == 0  ? "highlight.nav.chip.tooltip.allhidden"
                   : "highlight.nav.chip.tooltip.hidden";
        return MessageFormat.format(Messages.get(key), name, highlighted, hidden);
    }

    /**
     * Updates the position for the selected row: "2 / 4", "2 / 4 · 3 hidden" when the
     * filters hide some of the chosen color's rows, "3 hidden by filters" when they hide
     * them all (the arrows then have nowhere to go). Call when the selection changes.
     */
    void updatePosition() {
        if (!isVisible()) return;
        int[] n = new int[2];
        model.highlightCounts().forEach((c, counts) -> {
            if (navColor == null || navColor.equals(c)) { n[0] += counts[0]; n[1] += counts[1]; }
        });
        int shown = n[1], hidden = n[0] - n[1];
        prevBtn.setEnabled(shown > 0);
        nextBtn.setEnabled(shown > 0);
        if (shown == 0) {
            position.setText(MessageFormat.format(Messages.get("highlight.nav.allhidden"), hidden));
            return;
        }
        List<Integer> highlighted = model.getHighlightedModelRows(navColor);
        int currentModel = selectedModelRow();
        int pos = 0;
        for (int i = 0; i < highlighted.size(); i++) {
            if (highlighted.get(i) <= currentModel) pos = i + 1;
        }
        String at = pos + " / " + highlighted.size();
        position.setText(hidden == 0 ? at : MessageFormat.format(Messages.get("highlight.nav.somehidden"), at, hidden));
    }

    /** The position label's text ("2 / 5"); for tests. */
    String positionText() {
        return position.getText();
    }

    private int selectedModelRow() {
        int currentView = table.getSelectedRow();
        return currentView >= 0 ? table.convertRowIndexToModel(currentView) : -1;
    }

    void navigate(int direction) {
        List<Integer> highlighted = model.getHighlightedModelRows(navColor);
        if (highlighted.isEmpty()) return;
        int currentModel = selectedModelRow();
        int targetModel;
        if (direction > 0) {
            targetModel = highlighted.stream()
                    .filter(r -> r > currentModel)
                    .findFirst()
                    .orElse(highlighted.get(0));
        } else {
            targetModel = highlighted.stream()
                    .filter(r -> r < currentModel)
                    .reduce((a, b) -> b)
                    .orElse(highlighted.get(highlighted.size() - 1));
        }
        int viewRow = table.convertRowIndexToView(targetModel);
        if (viewRow >= 0) {
            table.setRowSelectionInterval(viewRow, viewRow);
            table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
            table.requestFocusInWindow();
            updatePosition();
        }
    }
}
