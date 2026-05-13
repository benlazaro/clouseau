package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.runtime.ClouseauPluginManager;
import com.droidenx.clouseau.runtime.ClouseauPluginManager.PluginInfo;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class PluginManagerDialog extends JDialog {

    private final ClouseauPluginManager pluginManager;
    private final Runnable onRefresh;
    private final PluginTableModel tableModel;

    public PluginManagerDialog(JFrame owner, ClouseauPluginManager pluginManager, Runnable onRefresh) {
        super(owner, Messages.get("plugins.dialog.title"), true);
        this.pluginManager = pluginManager;
        this.onRefresh     = onRefresh;
        this.tableModel    = new PluginTableModel(pluginManager, onRefresh);
        buildUI();
        pack();
        setMinimumSize(new Dimension(580, 320));
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        setLayout(new MigLayout("fill, insets 16", "[grow]", "[][grow][][]"));

        // ── Plugins directory ─────────────────────────────────────────────────
        Path root = pluginManager.getPluginsRoot();

        JTextField dirField = new JTextField(root.toString());
        dirField.setEditable(false);

        JButton openFolder = new JButton(Messages.get("plugins.dialog.open.folder"));
        openFolder.addActionListener(e -> openFolder(root));

        JPanel dirPanel = new JPanel(new MigLayout("insets 0, gap 6", "[][grow][]"));
        dirPanel.add(new JLabel(Messages.get("plugins.dialog.dir.label")));
        dirPanel.add(dirField, "growx");
        dirPanel.add(openFolder);
        add(dirPanel, "growx, wrap");

        // ── Plugin table ──────────────────────────────────────────────────────
        JTable table = new JTable(tableModel);
        table.setRowHeight(28);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));

        // Column widths
        table.getColumnModel().getColumn(0).setMinWidth(60);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(2).setMinWidth(110);
        table.getColumnModel().getColumn(2).setMaxWidth(110);
        table.getColumnModel().getColumn(3).setMinWidth(90);
        table.getColumnModel().getColumn(3).setMaxWidth(90);
        table.getColumnModel().getColumn(4).setMinWidth(80);
        table.getColumnModel().getColumn(4).setMaxWidth(80);

        // Center-align Type, Version, Status columns
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        table.getColumnModel().getColumn(2).setCellRenderer(centerRenderer);
        table.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);
        table.getColumnModel().getColumn(4).setCellRenderer(new StatusCellRenderer());

        JScrollPane tableScroll = new JScrollPane(table);
        add(tableScroll, "grow, wrap");

        // ── Extension detail ──────────────────────────────────────────────────
        JTextArea detailArea = new JTextArea(3, 0);
        detailArea.setEditable(false);
        detailArea.setLineWrap(false);
        detailArea.setOpaque(false);
        detailArea.setFont(UIManager.getFont("Label.font"));
        detailArea.setText(Messages.get("plugins.detail.none"));
        add(detailArea, "growx, wrap");

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row < 0) {
                detailArea.setText(Messages.get("plugins.detail.none"));
                return;
            }
            String id = (String) tableModel.getValueAt(row, 1);
            List<String> details = pluginManager.getExtensionDetails(id);
            detailArea.setText(details.isEmpty()
                    ? Messages.get("plugins.detail.no.extensions")
                    : String.join("\n", details));
        });

        // ── Buttons ───────────────────────────────────────────────────────────
        JButton deleteBtn = new JButton(Messages.get("plugins.dialog.delete"));
        deleteBtn.setEnabled(false);
        deleteBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            String id = (String) tableModel.getValueAt(row, 1);
            int confirm = JOptionPane.showConfirmDialog(this,
                    Messages.get("plugins.dialog.delete.confirm.message").formatted(id),
                    Messages.get("plugins.dialog.delete.confirm.title"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
            pluginManager.deletePlugin(id);
            onRefresh.run();
            tableModel.reload();
        });

        table.getSelectionModel().addListSelectionListener(
                e -> deleteBtn.setEnabled(table.getSelectedRow() >= 0));

        JButton downloadBtn = new JButton(Messages.get("plugins.dialog.download"));
        downloadBtn.addActionListener(e -> {
            new DownloadPluginDialog(this, pluginManager.getPluginsRoot(), () -> {
                pluginManager.refresh();
                onRefresh.run();
                tableModel.reload();
            }).setVisible(true);
        });

        JButton refreshBtn = new JButton(Messages.get("plugins.dialog.refresh"));
        refreshBtn.addActionListener(e -> {
            pluginManager.refresh();
            onRefresh.run();
            tableModel.reload();
        });

        JButton closeBtn = new JButton(Messages.get("plugins.dialog.close"));
        closeBtn.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new MigLayout("insets 0", "[][]push[][]", "[]"));
        buttons.add(deleteBtn);
        buttons.add(downloadBtn);
        buttons.add(refreshBtn);
        buttons.add(closeBtn);
        add(buttons, "growx");
    }

    private void openFolder(Path path) {
        try {
            Files.createDirectories(path);
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    Messages.get("plugins.dialog.open.folder.error").formatted(path),
                    Messages.get("plugins.dialog.open.folder.error.title"),
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    // ── Status cell renderer ──────────────────────────────────────────────────

    private static final class StatusCellRenderer extends DefaultTableCellRenderer {

        StatusCellRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                String state = value == null ? "" : value.toString();
                if (state.equals(Messages.get("plugins.state.enabled"))) {
                    setForeground(ClouseauColors.pluginEnabled());
                } else if (state.equals(Messages.get("plugins.state.disabled"))) {
                    setForeground(ClouseauColors.pluginDisabled());
                } else if (state.equals(Messages.get("plugins.state.failed"))) {
                    setForeground(ClouseauColors.pluginFailed());
                } else {
                    setForeground(table.getForeground());
                }
            }
            return this;
        }
    }

    // ── Table model ───────────────────────────────────────────────────────────

    private static final class PluginTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {
            Messages.get("plugins.table.col.enabled"),
            Messages.get("plugins.table.col.id"),
            Messages.get("plugins.table.col.type"),
            Messages.get("plugins.table.col.version"),
            Messages.get("plugins.table.col.status"),
        };

        private final ClouseauPluginManager pluginManager;
        private final Runnable onRefresh;
        private List<PluginInfo> rows;

        PluginTableModel(ClouseauPluginManager pluginManager, Runnable onRefresh) {
            this.pluginManager = pluginManager;
            this.onRefresh     = onRefresh;
            this.rows          = new ArrayList<>(pluginManager.getPluginInfos());
        }

        void reload() {
            rows = new ArrayList<>(pluginManager.getPluginInfos());
            fireTableDataChanged();
        }

        @Override public int getRowCount()    { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            return col == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            // Only the enabled toggle is editable, and not for failed plugins
            return col == 0 && !"Failed".equals(rows.get(row).state());
        }

        @Override
        public Object getValueAt(int row, int col) {
            PluginInfo p = rows.get(row);
            return switch (col) {
                case 0 -> p.enabled();
                case 1 -> p.id();
                case 2 -> translateTypes(p.types());
                case 3 -> p.version();
                case 4 -> translateState(p.state());
                default -> null;
            };
        }

        private static String translateState(String state) {
            return switch (state) {
                case "Enabled"  -> Messages.get("plugins.state.enabled");
                case "Stopped"  -> Messages.get("plugins.state.stopped");
                case "Disabled" -> Messages.get("plugins.state.disabled");
                case "Failed"   -> Messages.get("plugins.state.failed");
                default         -> state;
            };
        }

        private static String translateTypes(String types) {
            if ("\u2014".equals(types) || types.isBlank()) return types;
            return Arrays.stream(types.split(", "))
                    .map(t -> switch (t) {
                        case "Parser"      -> Messages.get("plugins.type.parser");
                        case "Formatter"   -> Messages.get("plugins.type.formatter");
                        case "Highlighter" -> Messages.get("plugins.type.highlighter");
                        default            -> t;
                    })
                    .collect(Collectors.joining(", "));
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col != 0) return;
            String id = rows.get(row).id();
            if ((boolean) value) {
                pluginManager.enablePlugin(id);
            } else {
                pluginManager.disablePlugin(id);
            }
            onRefresh.run();
            reload();
        }
    }
}
