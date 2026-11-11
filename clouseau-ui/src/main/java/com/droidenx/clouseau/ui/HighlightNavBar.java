package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Set;

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
    /** The color being navigated, or null for all highlighted rows. */
    private Color navColor = null;

    HighlightNavBar(JTable table, LogTableModel model) {
        super(new MigLayout("insets 3 8 3 8, gapy 0", "[][grow][]", "[center]"));
        this.table = table;
        this.model = model;
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()));
        setVisible(false);

        JLabel label = new JLabel(Messages.get("highlight.nav.label"));
        ClouseauColors.themedForeground(label, ClouseauColors::dimForeground);
        label.setFont(label.getFont().deriveFont(12f));

        swatches.setOpaque(false);

        ClouseauColors.themedForeground(position, ClouseauColors::dimForeground);
        position.setFont(position.getFont().deriveFont(12f));

        JButton prevBtn = FindBar.arrowButton("\u25b2");
        JButton nextBtn = FindBar.arrowButton("\u25bc");
        prevBtn.setToolTipText(Messages.get("highlight.nav.prev.tooltip"));
        nextBtn.setToolTipText(Messages.get("highlight.nav.next.tooltip"));
        prevBtn.addActionListener(e -> navigate(-1));
        nextBtn.addActionListener(e -> navigate(+1));

        JPanel rightPanel = new JPanel(new MigLayout("insets 0", "[]6[]4[]", "[]"));
        rightPanel.setOpaque(false);
        rightPanel.add(position);
        rightPanel.add(prevBtn);
        rightPanel.add(nextBtn);

        // Everything centered on one line, the chips included (a FlowLayout put them at the
        // top of their row, above the label's middle).
        add(label, "aligny center");
        add(swatches, "growx, aligny center");
        add(rightPanel, "aligny center");
    }

    /** Rebuilds the swatches after highlights changed; hides the bar when there are none. */
    void refresh() {
        Set<Color> activeColors = model.getActiveHighlightColors();
        if (activeColors.isEmpty()) {
            setVisible(false);
            navColor = null;
            return;
        }
        // If the selected color was removed, fall back to "All"
        if (navColor != null && !activeColors.contains(navColor)) navColor = null;
        setVisible(true);
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
        for (Color palette : ClouseauColors.highlightColors()) {
            if (!activeColors.contains(palette)) continue;
            final Color c = palette;
            final boolean selected = c.equals(navColor);
            JButton swatch = new JButton() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
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
            swatch.addActionListener(e -> { navColor = c; refresh(); });
            swatches.add(swatch, "w 16!, h 16!");
        }

        swatches.revalidate();
        swatches.repaint();
        updatePosition();
    }

    /** Updates "n / total" for the selected row; call when the table selection changes. */
    void updatePosition() {
        if (!isVisible()) return;
        List<Integer> highlighted = model.getHighlightedModelRows(navColor);
        if (highlighted.isEmpty()) { position.setText("0 / 0"); return; }
        int currentModel = selectedModelRow();
        int pos = 0;
        for (int i = 0; i < highlighted.size(); i++) {
            if (highlighted.get(i) <= currentModel) pos = i + 1;
        }
        position.setText(pos + " / " + highlighted.size());
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
