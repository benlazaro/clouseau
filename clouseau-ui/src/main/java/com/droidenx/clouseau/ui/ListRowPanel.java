package com.droidenx.clouseau.ui;

import com.formdev.flatlaf.ui.FlatListUI;

import javax.swing.*;
import java.awt.*;

/**
 * A list cell renderer built from a panel, whose selection has the list's corners and insets
 * (DESIGN.md 6.13). FlatLaf rounds a list's selection by itself only for a
 * {@code DefaultListCellRenderer}, so this panel stays transparent and fills the selection
 * through the list's UI. Call {@link #prepare} from {@code getListCellRendererComponent}.
 */
abstract class ListRowPanel extends JPanel {
    private JList<?> list;
    private int row;
    private Color selection;

    ListRowPanel(LayoutManager layout) {
        super(layout);
        setOpaque(false);
    }

    /** The row about to be painted: its list, index, and selection color ({@code null} when not selected). */
    protected void prepare(JList<?> list, int row, Color selection) {
        this.list = list;
        this.row = row;
        this.selection = selection;
        setOpaque(false);
    }

    @Override protected void paintComponent(Graphics g) {
        if (selection != null && list != null) {
            g.setColor(selection);
            FlatListUI.paintCellSelection(list, g, row, 0, 0, getWidth(), getHeight());
        }
        super.paintComponent(g);
    }
}
