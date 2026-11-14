package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.LogSniffer;
import com.droidenx.clouseau.core.MergeLoad;
import com.droidenx.clouseau.core.MergeOrder;
import com.droidenx.clouseau.core.MergedEntries;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How each file of a merged tab is read (D-17 step 6, G-10): its time offset, source
 * timezone and parser, and the dialogs that change them. Kept across reloads of the tab.
 * EDT only.
 */
@Slf4j
final class MergedFileSettings {

    /** What the settings need from the tab that owns them. */
    interface Host {
        /** The merged tab's files, in order, or null if the tab isn't merged. */
        List<MergeLoad.File> files();
        /** The merged view the tab shows, or null. */
        MergedEntries view();
        /** The tab's source timezone, for files without their own. */
        ZoneId tabZone();
        /** The tab's parser, for files without their own (empty: per-line detection). */
        Optional<LogParser> tabParser();
        /** The parsers the user can choose from. */
        List<LogParser> parsers();
        /** Loads the files again, after a timezone or parser change. */
        void reload();
        /** A file's timezone changed, so the status bar's badge may too. */
        void zonesChanged();
        /** A re-merge after an offset change is ready to show. */
        void reordered(MergedEntries view, MergeOrder before, MergeOrder after);
    }

    private final Host host;
    /** Time offsets the user set on the files. */
    private final Map<MergeLoad.File, Long> offsets = new HashMap<>();
    /** Parsers chosen for single files (G-10); the others use the tab's. */
    private final Map<MergeLoad.File, LogParser> parsers = new HashMap<>();
    /** Source timezones a review bundle gave the files; they win over saved ones. */
    private final Map<MergeLoad.File, ZoneId> zones = new HashMap<>();
    /** Counts offset changes, so only the last one's re-merge is shown. */
    private long offsetChanges;

    MergedFileSettings(Host host) {
        this.host = host;
    }

    /**
     * Sets how the files are read before they load: per file, a timezone (if not null), a
     * time offset and a parser (if not null), as a review bundle gives them (D-17).
     */
    void preset(Map<MergeLoad.File, MergeLoad.PerFile> setup) {
        setup.forEach((f, p) -> {
            if (p.zone() != null) zones.put(f, p.zone());
            if (p.offsetMillis() != 0) offsets.put(f, p.offsetMillis());
            if (p.parser() != null) parsers.put(f, p.parser());
        });
    }

    /** How a merged load reads one file: its timezone, offset and own parser (or null). */
    MergeLoad.PerFile perFile(MergeLoad.File f) {
        return new MergeLoad.PerFile(zoneOf(f), offsetOf(f), parsers.get(f));
    }

    /** The source timezone a file is read in: from a bundle, else its saved one, else the tab's. */
    ZoneId zoneOf(MergeLoad.File f) {
        ZoneId preset = zones.get(f);
        if (preset != null) return preset;
        ZoneId saved = AppPrefs.getSourceTimezone(f.path());
        return saved != null ? saved : host.tabZone();
    }

    /** The time offset set on a file, 0 if none. */
    long offsetOf(MergeLoad.File f) {
        return offsets.getOrDefault(f, 0L);
    }

    /** The parser chosen for this file alone, or null if it uses the tab's. */
    LogParser ownParser(MergeLoad.File f) {
        return parsers.get(f);
    }

    /** The parser a file is read with: its own, else the tab's (empty: per-line detection). */
    Optional<LogParser> parserOf(MergeLoad.File f) {
        LogParser own = parsers.get(f);
        return own != null ? Optional.of(own) : host.tabParser();
    }

    /** The timezone file {@code source} is read in, or the tab's if there's no such file. */
    ZoneId zoneOf(int source) {
        List<MergeLoad.File> files = host.files();
        return files != null && source >= 0 && source < files.size() ? zoneOf(files.get(source)) : host.tabZone();
    }

    /** Whether the files are read in more than one timezone. */
    boolean zonesDiffer() {
        List<MergeLoad.File> files = host.files();
        return files != null && files.stream().map(this::zoneOf).distinct().count() > 1;
    }

    /** The user set one timezone for the whole tab: it replaces those a bundle gave. */
    void clearZones() {
        zones.clear();
    }

    /** Asks for a new time offset for one file, then re-merges. */
    void showTimeOffsetDialog(Window parent, int source) {
        MergedEntries view = host.view();
        if (view == null || source < 0 || source >= view.sourceCount()) return;
        Long millis = TimeOffsetDialog.ask(parent, view.sourceName(source), view.offsetMillis(source));
        if (millis != null) applyTimeOffset(source, millis);
    }

    /**
     * Sets one file's time offset and re-merges the whole order in the background; the
     * rows followed meanwhile are placed too before the new order is shown.
     */
    void applyTimeOffset(int source, long millis) {
        MergedEntries view = host.view();
        List<MergeLoad.File> files = host.files();
        if (view == null || files == null) return;
        offsets.put(files.get(source), millis);
        long[] all = new long[view.sourceCount()];
        for (int s = 0; s < all.length; s++) all[s] = offsetOf(files.get(s));
        int[] counts = view.rowCounts();
        long change = ++offsetChanges;
        new SwingWorker<MergeOrder, Void>() {
            @Override
            protected MergeOrder doInBackground() {
                return MergeOrder.build(view.inputs(counts, all), this::isCancelled, n -> {});
            }

            @Override
            protected void done() {
                if (change != offsetChanges || host.view() != view) return;
                MergeOrder built;
                try {
                    built = get();
                } catch (Exception ex) {
                    log.error("Re-merging {} failed", view, ex);
                    return;
                }
                if (built == null) return;
                MergeOrder before = view.order();
                MergeOrder after = built.extend(view.inputs(view.rowCounts(), all));
                view.setOrder(after, all);
                host.reordered(view, before, after);
            }
        }.execute();
    }

    /** Asks for the source timezone of one file, saves it, and reloads. */
    void showSourceTimezoneDialog(Component parent, int source) {
        List<MergeLoad.File> files = host.files();
        if (files == null || source < 0 || source >= files.size()) return;
        MergeLoad.File f = files.get(source);
        ZoneCombo combo = SettingsDialog.buildTimezoneCombo(zoneOf(f));
        String title = MessageFormat.format(Messages.get("merge.timezone.title"), sourceName(source));
        JPanel form = new JPanel(new MigLayout("insets 0, wrap 1, gapy 6", "[grow, fill]"));
        form.add(new JLabel(Messages.get("source.timezone.dialog.label")));
        form.add(combo);
        form.add(PromptDialog.hint(Messages.get("source.timezone.dialog.reload.note")));
        PromptDialog dialog = new PromptDialog(parent, title, form);
        focusOnOpen(dialog, combo);
        if (!dialog.ask()) return;
        ZoneId zone = combo.selectedZone();
        if (zones.containsKey(f)) zones.put(f, zone);   // from a bundle: this tab only
        else AppPrefs.setSourceTimezone(f.path(), zone);
        host.zonesChanged();
        host.reload();
    }

    /**
     * Asks which parser one file is read with (G-10), and reloads. A parser that doesn't
     * recognize the file's first lines is refused: the file would be left out.
     */
    void showParserDialog(Component parent, int source) {
        List<MergeLoad.File> files = host.files();
        if (files == null || source < 0 || source >= files.size()) return;
        MergeLoad.File f = files.get(source);
        String name = sourceName(source);
        List<LogParser> choices = host.parsers();
        String tabChoice = host.tabParser().map(LogParser::getName)
                .orElse(Messages.get("filechooser.parser.autodetect"));
        JComboBox<Object> combo = new JComboBox<>();
        String same = MessageFormat.format(Messages.get("merge.parser.tab"), tabChoice);
        combo.addItem(same);
        choices.forEach(combo::addItem);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof LogParser p ? p.getName() : value,
                        index, isSelected, cellHasFocus);
            }
        });
        LogParser own = parsers.get(f);
        combo.setSelectedItem(own != null ? own : same);
        String title = MessageFormat.format(Messages.get("merge.parser.title"), name);
        // A parser that doesn't fit the file is said under the list, with the list outlined,
        // and OK waits for one that does: the file would be left out of the view otherwise.
        NoticeLabel notice = new NoticeLabel();
        JPanel form = new JPanel(new MigLayout("insets 0, wrap 1, gapy 6", "[grow, fill]"));
        form.add(new JLabel(Messages.get("merge.parser.label")));
        form.add(combo);
        form.add(PromptDialog.hint(Messages.get("merge.parser.note")));
        form.add(notice, "wmin 0, gaptop 6");
        PromptDialog dialog = new PromptDialog(parent, title, form);
        focusOnOpen(dialog, combo);

        SwingWorker<?, ?>[] checking = { null };
        Runnable check = () -> {
            if (checking[0] != null) checking[0].cancel(true);
            checking[0] = null;
            combo.putClientProperty("JComponent.outline", null);
            notice.setText(" ");
            notice.setToolTipText(null);
            LogParser chosen = combo.getSelectedItem() instanceof LogParser p ? p : null;
            if (chosen == null || chosen == own) {
                dialog.okButton().setEnabled(true);
                return;
            }
            dialog.okButton().setEnabled(false);       // until the file's first lines are read
            SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
                @Override protected Boolean doInBackground() {
                    try {
                        return LogSniffer.detect(LogSniffer.read(f.path(), f.entry()).lines(),
                                Optional.of(chosen), choices).isPresent();
                    } catch (IOException e) {
                        log.warn("Can't read the first lines of {}", f, e);
                        return false;
                    }
                }

                @Override protected void done() {
                    if (isCancelled() || checking[0] != this) return;
                    checking[0] = null;
                    boolean recognized;
                    try {
                        recognized = get();
                    } catch (Exception e) {
                        recognized = false;
                    }
                    dialog.okButton().setEnabled(recognized);
                    if (!recognized) {
                        combo.putClientProperty("JComponent.outline", "error");
                        String text = MessageFormat.format(Messages.get("merge.parser.unrecognized"), chosen.getName(), name);
                        notice.show(text, NoticeLabel.Kind.ERROR);
                        notice.setToolTipText(text);
                    }
                    combo.repaint();
                }
            };
            checking[0] = worker;
            worker.execute();
        };
        combo.addActionListener(e -> check.run());
        if (!dialog.ask()) {
            if (checking[0] != null) checking[0].cancel(true);
            return;
        }
        LogParser chosen = combo.getSelectedItem() instanceof LogParser p ? p : null;
        if (chosen == own) return;
        if (chosen != null) parsers.put(f, chosen);
        else parsers.remove(f);
        host.reload();
    }

    /** A file's name as the Source column shows it. */
    private String sourceName(int source) {
        MergedEntries view = host.view();
        if (view != null && source < view.sourceCount()) return view.sourceName(source);
        return host.files().get(source).path().getFileName().toString();
    }

    /** The field takes the focus once the dialog shows; asked for earlier, it's lost. */
    private static void focusOnOpen(Window dialog, JComponent field) {
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) { field.requestFocusInWindow(); }
        });
    }
}
