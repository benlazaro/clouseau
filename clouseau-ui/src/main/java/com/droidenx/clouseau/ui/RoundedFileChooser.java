package com.droidenx.clouseau.ui;

import com.formdev.flatlaf.ui.FlatRoundBorder;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * A file chooser whose Look In / Save In combo has the same 4px corners as every other
 * drop-down (DESIGN.md 6.13). Use it instead of {@code new JFileChooser()}.
 * <p>
 * Swing's file chooser marks that combo {@code JComboBox.isTableCellEditor} (so the arrow keys
 * move through the list without opening each folder), and FlatLaf takes that flag to mean a
 * table cell, which it draws square and without a focus ring. The flag stays, for the keys; the
 * combo gets a border that doesn't treat it as a cell.
 * <p>
 * FlatLaf's borders are UI resources, so the combo's own {@code updateUI()} puts the theme's
 * border back, and each install of the chooser's UI builds a new combo. A theme switch updates
 * the chooser first and its children after, so the border is set once that's done
 * ({@code invokeLater} from {@code updateUI}).
 */
public class RoundedFileChooser extends JFileChooser {

    public RoundedFileChooser() {
    }

    public RoundedFileChooser(File currentDirectory) {
        super(currentDirectory);
    }

    public RoundedFileChooser(String currentDirectoryPath) {
        super(currentDirectoryPath);
    }

    @Override public void updateUI() {
        super.updateUI();
        SwingUtilities.invokeLater(() -> roundLookIn(this));
    }

    private static void roundLookIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JComboBox<?> combo
                    && Boolean.TRUE.equals(combo.getClientProperty("JComboBox.isTableCellEditor"))) {
                if (!(combo.getBorder() instanceof LookInBorder)) combo.setBorder(new LookInBorder());
            } else if (child instanceof Container sub) {
                roundLookIn(sub);
            }
        }
    }

    /** The theme's combo border, except that it never takes the combo for a table cell. */
    static final class LookInBorder extends FlatRoundBorder {
        @Override protected boolean isCellEditor(Component c) { return false; }
    }
}
