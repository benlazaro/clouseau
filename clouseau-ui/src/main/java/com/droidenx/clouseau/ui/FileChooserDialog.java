package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.filechooser.FileSystemView;
import javax.swing.plaf.basic.BasicFileChooserUI;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.ZoneId;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Custom file-chooser dialog with a favorites sidebar, file preview, and parser picker.
 * Use {@link #showDialog()} to display it; returns the chosen file and parser on approval.
 */
@Slf4j
final class FileChooserDialog extends JDialog {

    record Result(List<Path> files, Optional<LogParser> parser, int parserIndex, ZoneId sourceTimezone) {}
    private record PreviewResult(String previewText, String statusText, Color statusColor, boolean canOpen) {}

    private final List<LogParser> parsers;
    private final JFileChooser chooser = new JFileChooser();
    private final DefaultListModel<Path> favoritesModel = new DefaultListModel<>();
    private int selectedParserIndex;
    private ZoneId selectedSourceTimezone = ZoneId.systemDefault();
    private JButton openBtn;
    private Result result;
    private volatile SwingWorker<PreviewResult, Void> pendingWorker;
    private javax.swing.Timer debounceTimer;

    FileChooserDialog(Frame owner, List<LogParser> parsers, int initialParserIndex) {
        super(owner, Messages.get("filechooser.title"), true);
        this.parsers = parsers;
        this.selectedParserIndex = initialParserIndex;

        AppPrefs.getFavorites().forEach(favoritesModel::addElement);

        chooser.setFileFilter(new FileNameExtensionFilter(
                Messages.get("filechooser.filter.desc"), "log", "txt", "out", "gz", "zip"));
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setMultiSelectionEnabled(true);
        chooser.setControlButtonsAreShown(false);
        File lastDir = AppPrefs.getLastOpenDir();
        if (lastDir != null) chooser.setCurrentDirectory(lastDir);

        // Switch to details view
        Action detailsView = chooser.getActionMap().get("viewTypeDetails");
        if (detailsView != null)
            detailsView.actionPerformed(new ActionEvent(chooser, ActionEvent.ACTION_PERFORMED, null));

        // Approve/cancel fired by double-click, Enter, or our own buttons
        chooser.addActionListener(e -> {
            if (JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand())) doApprove();
            else if (JFileChooser.CANCEL_SELECTION.equals(e.getActionCommand())) dispose();
        });

        JSplitPane leftSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildFavoritesPanel(), chooser);
        leftSplit.setDividerLocation(180);
        leftSplit.setBorder(null);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                leftSplit, buildParserAccessory());
        mainSplit.setResizeWeight(1.0); // chooser absorbs resize, accessory stays fixed
        mainSplit.setBorder(null);
        // Set right divider after first layout when widths are known
        mainSplit.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                mainSplit.removeComponentListener(this);
                mainSplit.setDividerLocation(mainSplit.getWidth() - 280 - mainSplit.getDividerSize());
            }
        });

        JPanel content = new JPanel(new BorderLayout(0, 0));
        content.add(mainSplit,        BorderLayout.CENTER);
        content.add(buildButtonBar(), BorderLayout.SOUTH);
        setContentPane(content);

        setPreferredSize(new Dimension(1100, 650));
        pack();
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog modally and returns the result, or empty if cancelled. */
    Optional<Result> showDialog() {
        setVisible(true); // blocks until disposed
        return Optional.ofNullable(result);
    }

    private void doApprove() {
        File[] allSelected = chooser.getSelectedFiles();
        List<Path> files;
        if (allSelected != null && allSelected.length > 0) {
            files = Arrays.stream(allSelected).filter(File::isFile).map(File::toPath).toList();
        } else {
            File f = chooser.getSelectedFile();
            if (f == null || !f.isFile()) return;
            files = List.of(f.toPath());
        }
        if (files.isEmpty()) return;
        Optional<LogParser> chosen = selectedParserIndex == 0
                ? Optional.empty()
                : Optional.of(parsers.get(selectedParserIndex - 1));
        result = new Result(files, chosen, selectedParserIndex, selectedSourceTimezone);
        AppPrefs.setLastOpenDir(chooser.getCurrentDirectory());
        dispose();
    }

    // ── Favorites sidebar ─────────────────────────────────────────────────────

    private JPanel buildFavoritesPanel() {
        JList<Path> list = new JList<>(favoritesModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(28);
        FileSystemView fsv = FileSystemView.getFileSystemView();
        Map<Path, Icon> favIconCache = new HashMap<>();
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, foc);
                Path p = (Path) v;
                setText(p.getFileName() != null ? p.getFileName().toString() : p.toString());
                setToolTipText(p.toString());
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                setIcon(p.toFile().exists()
                        ? favIconCache.computeIfAbsent(p, k -> fsv.getSystemIcon(k.toFile())) : null);
                return this;
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 1) return;
                Path fav = list.getSelectedValue();
                if (fav == null) return;
                if (Files.isDirectory(fav)) {
                    chooser.setCurrentDirectory(fav.toFile());
                } else if (fav.getParent() != null && Files.isDirectory(fav.getParent())) {
                    chooser.setCurrentDirectory(fav.getParent().toFile());
                    chooser.setSelectedFile(fav.toFile());
                }
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        JButton addBtn = new JButton("+");
        addBtn.setToolTipText(Messages.get("filechooser.favorites.add.tooltip"));
        addBtn.setMargin(new Insets(2, 8, 2, 8));
        addBtn.addActionListener(e -> {
            File dir = chooser.getCurrentDirectory();
            if (dir == null) return;
            Path p = dir.toPath().toAbsolutePath();
            if (!favoritesModel.contains(p)) {
                favoritesModel.addElement(p);
                saveFavorites();
            }
        });

        JButton removeBtn = new JButton("\u2212");
        removeBtn.setToolTipText(Messages.get("filechooser.favorites.remove.tooltip"));
        removeBtn.setMargin(new Insets(2, 8, 2, 8));
        removeBtn.setEnabled(false);
        list.addListSelectionListener(e -> removeBtn.setEnabled(list.getSelectedValue() != null));
        removeBtn.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0) { favoritesModel.remove(idx); saveFavorites(); }
        });

        JLabel header = new JLabel(Messages.get("filechooser.favorites.label"));
        header.setIcon(new javax.swing.Icon() {
            private static final int S = 12;
            public int getIconWidth()  { return S; }
            public int getIconHeight() { return S; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double cx = x + S / 2.0, cy = y + S / 2.0;
                double outer = S / 2.0, inner = outer * 0.42;
                int[] px = new int[10], py = new int[10];
                for (int i = 0; i < 10; i++) {
                    double angle = Math.PI / 2 + i * Math.PI / 5;
                    double r = (i % 2 == 0) ? outer : inner;
                    px[i] = (int) Math.round(cx - r * Math.cos(angle));
                    py[i] = (int) Math.round(cy - r * Math.sin(angle));
                }
                g2.setColor(ClouseauColors.starColor());
                g2.fillPolygon(px, py, 10);
                g2.dispose();
            }
        });
        header.setIconTextGap(5);
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        btnRow.add(addBtn);
        btnRow.add(removeBtn);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setMinimumSize(new Dimension(80, 0));
        panel.setPreferredSize(new Dimension(180, 0));
        panel.add(header, BorderLayout.NORTH);
        panel.add(scroll,  BorderLayout.CENTER);
        panel.add(btnRow,  BorderLayout.SOUTH);
        return panel;
    }

    private void saveFavorites() {
        List<Path> list = new ArrayList<>(favoritesModel.size());
        for (int i = 0; i < favoritesModel.size(); i++) list.add(favoritesModel.get(i));
        AppPrefs.saveFavorites(list);
    }

    // ── Parser accessory + preview ────────────────────────────────────────────

    private JPanel buildParserAccessory() {
        String[] items = Stream.concat(
                Stream.of(Messages.get("filechooser.parser.autodetect")),
                parsers.stream().map(LogParser::getName)
        ).toArray(String[]::new);

        JComboBox<String> combo = new JComboBox<>(items);
        combo.setSelectedIndex(selectedParserIndex);

        JComboBox<SettingsDialog.TimezoneEntry> tzCombo =
                SettingsDialog.buildTimezoneCombo(selectedSourceTimezone);
        tzCombo.addActionListener(e -> {
            SettingsDialog.TimezoneEntry sel = (SettingsDialog.TimezoneEntry) tzCombo.getSelectedItem();
            if (sel != null) selectedSourceTimezone = sel.zone();
        });

        // When a single file is selected, pre-populate TZ combo from AppPrefs if a zone was saved.
        chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY, e -> {
            File f = chooser.getSelectedFile();
            if (f != null && f.isFile()) {
                ZoneId saved = AppPrefs.getSourceTimezone(f.toPath());
                if (saved != null) {
                    selectedSourceTimezone = saved;
                    // rebuild the combo selection without creating a new combo instance
                    for (int i = 0; i < tzCombo.getItemCount(); i++) {
                        SettingsDialog.TimezoneEntry entry = tzCombo.getItemAt(i);
                        if (entry.zone().equals(saved)) { tzCombo.setSelectedIndex(i); break; }
                    }
                }
            }
        });

        JLabel statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));

        JTextArea previewArea = new JTextArea();
        previewArea.setEditable(false);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        previewArea.setBackground(ClouseauColors.detailBackground());
        previewArea.setForeground(ClouseauColors.dimForeground());
        previewArea.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        previewArea.setLineWrap(false);
        previewArea.setRows(6);
        previewArea.setText(Messages.get("filechooser.preview.empty"));

        JScrollPane previewScroll = new JScrollPane(previewArea);
        previewScroll.setBorder(BorderFactory.createLineBorder(ClouseauColors.borderColor()));

        JLabel previewLabel = new JLabel(Messages.get("filechooser.preview.label"));
        previewLabel.setFont(previewLabel.getFont().deriveFont(11f));

        // Debounced: rapid selection changes restart the timer instead of stacking workers.
        Runnable scheduleValidation = () -> {
            if (debounceTimer != null && debounceTimer.isRunning()) {
                debounceTimer.restart();
                return;
            }
            debounceTimer = new javax.swing.Timer(150, ev -> {
                if (pendingWorker != null && !pendingWorker.isDone()) pendingWorker.cancel(true);

                File[] allSelected = chooser.getSelectedFiles();
                long fileCount = allSelected != null
                        ? Arrays.stream(allSelected).filter(File::isFile).count() : 0;
                File lead = chooser.getSelectedFile();
                boolean multiFile = fileCount > 1;

                // For multi-file, status is known immediately; preview still runs in background.
                if (multiFile) {
                    statusLabel.setForeground(ClouseauColors.statusOk());
                    statusLabel.setText(Messages.get("filechooser.status.files.selected").formatted(fileCount));
                    if (openBtn != null) openBtn.setEnabled(true);
                }

                File toPreview = (fileCount >= 1 && allSelected != null) ? allSelected[0] : lead;
                if (toPreview == null || !toPreview.isFile()) {
                    previewArea.setText(Messages.get("filechooser.preview.empty"));
                    if (!multiFile) {
                        statusLabel.setText(" ");
                        if (openBtn != null) openBtn.setEnabled(false);
                    }
                    return;
                }

                int idx = combo.getSelectedIndex();
                Optional<LogParser> chosen = idx == 0
                        ? Optional.empty() : Optional.of(parsers.get(idx - 1));
                Path filePath = toPreview.toPath();

                // Read file once off the EDT; derive both preview text and parser match.
                pendingWorker = new SwingWorker<>() {
                    @Override
                    protected PreviewResult doInBackground() {
                        List<String> lines = new ArrayList<>(50);
                        try (BufferedReader reader = LogPanel.openReader(filePath)) {
                            String line;
                            while ((line = reader.readLine()) != null && lines.size() < 50) {
                                if (isCancelled()) return null;
                                lines.add(line);
                            }
                        } catch (IOException e) {
                            log.warn("Preview read failed for {}", filePath, e);
                            return new PreviewResult(Messages.get("filechooser.preview.error"),
                                    " ", ClouseauColors.dimForeground(),
                                    chosen.or(() -> parsers.stream().findFirst()).isPresent());
                        }
                        if (isCancelled()) return null;

                        String previewText;
                        if (lines.isEmpty()) {
                            previewText = Messages.get("filechooser.preview.empty.file");
                        } else {
                            String joined = String.join("\n", lines);
                            long nonPrintable = joined.chars()
                                    .filter(c -> c < 32 && c != '\n' && c != '\r' && c != '\t')
                                    .count();
                            previewText = nonPrintable > joined.length() * 0.05
                                    ? Messages.get("filechooser.preview.binary") : joined;
                        }

                        // Multi-file: status is already set; only preview text needed.
                        if (multiFile) return new PreviewResult(previewText, null, null, true);
                        if (isCancelled()) return null;

                        Optional<LogParser> matched = Optional.empty();
                        for (String line : lines) {
                            if (isCancelled()) return null;
                            if (line.isBlank()) continue;
                            final String candidate = line;
                            matched = chosen.map(Stream::of)
                                    .orElseGet(parsers::stream)
                                    .filter(p -> p.canParse(candidate))
                                    .findFirst();
                            if (matched.isPresent()) break;
                        }

                        String statusText;
                        Color statusColor;
                        boolean canOpen;
                        if (matched.isPresent()) {
                            statusColor = ClouseauColors.statusOk();
                            statusText = chosen.isPresent()
                                    ? Messages.get("filechooser.status.compatible")
                                    : Messages.get("filechooser.status.compatible.detected")
                                            .formatted(matched.get().getName());
                            canOpen = true;
                        } else {
                            statusColor = ClouseauColors.statusError();
                            statusText = Messages.get("filechooser.status.incompatible")
                                    .formatted(chosen.map(LogParser::getName)
                                            .orElse(Messages.get("filechooser.parser.autodetect")));
                            canOpen = false;
                        }
                        return new PreviewResult(previewText, statusText, statusColor, canOpen);
                    }

                    @Override
                    protected void done() {
                        if (isCancelled()) return;
                        PreviewResult r;
                        try { r = get(); } catch (Exception ex) { return; }
                        if (r == null) return;
                        previewArea.setText(r.previewText());
                        previewArea.setCaretPosition(0);
                        if (!multiFile) {
                            statusLabel.setForeground(r.statusColor());
                            statusLabel.setText(r.statusText());
                            if (openBtn != null) openBtn.setEnabled(r.canOpen());
                        }
                    }
                };
                pendingWorker.execute();
            });
            debounceTimer.setRepeats(false);
            debounceTimer.start();
        };

        combo.addActionListener(e -> {
            selectedParserIndex = combo.getSelectedIndex();
            scheduleValidation.run();
        });
        chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY,
                e -> scheduleValidation.run());
        chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILES_CHANGED_PROPERTY,
                e -> scheduleValidation.run());
        chooser.addPropertyChangeListener(JFileChooser.DIRECTORY_CHANGED_PROPERTY, e -> {
            chooser.setSelectedFile(null);
            if (chooser.getUI() instanceof BasicFileChooserUI basicUI) basicUI.setFileName("");
            SwingUtilities.invokeLater(scheduleValidation);
        });

        JPanel panel = new JPanel(new MigLayout("insets 8, fill, wrap 1", "[280px,grow]", "[][]4[][]4[]8[][grow]"));
        panel.setMinimumSize(new Dimension(150, 0));
        panel.add(new JLabel(Messages.get("filechooser.parser.label")));
        panel.add(combo, "growx");
        panel.add(new JLabel(Messages.get("filechooser.source.timezone.label")));
        panel.add(tzCombo, "growx");
        panel.add(statusLabel, "growx");
        panel.add(previewLabel);
        panel.add(previewScroll, "grow");
        return panel;
    }

    // ── Button bar ────────────────────────────────────────────────────────────

    private JPanel buildButtonBar() {
        JButton cancelBtn = new JButton(Messages.get("filechooser.button.cancel"));
        cancelBtn.addActionListener(e -> dispose());

        openBtn = new JButton(Messages.get("filechooser.button.open"));
        openBtn.setEnabled(false);
        openBtn.addActionListener(e -> chooser.approveSelection());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ClouseauColors.borderColor()));
        bar.add(cancelBtn);
        bar.add(openBtn);
        getRootPane().setDefaultButton(openBtn);
        return bar;
    }
}
