package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.IndexedEntries;
import com.droidenx.clouseau.core.IndexedFollow;
import com.droidenx.clouseau.core.LineParser;
import com.droidenx.clouseau.core.MergedEntries;
import com.droidenx.clouseau.core.MergedFollow;

import javax.swing.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Follows the files of a merged tab (D-17 step 5): a tail worker for each file that can be
 * followed (not compressed), which adds the lines it reads to that file's index through
 * an {@link IndexedFollow}, as a single-file tab does (G-11), and a {@link MergedFollow}
 * that holds them back and places them in timeline order. A Swing timer releases held
 * rows when their time is up.
 * <p>
 * One tailer lives as long as the tab shows the same merge; {@link #start} and
 * {@link #stop} follow the Follow toggle and keep each file's position. Call both on the EDT.
 */
final class MergedTailer {

    /** What the tab does with the tailer's results. Called on the EDT. */
    interface Host {
        /** Followed rows were placed in the view. */
        void placed(MergedFollow.Placed placed);
        /** Lines just read, for the filter bar's lists. */
        void read(List<LogEntry> entries);
        /** A followed file was replaced, and older rows of it lost their text. */
        void textLost();
    }

    private static final int TICK_MS = 200;
    private static final long SAFETY_POLL_MS = 2_000;

    private final MergedEntries view;
    /** Per source, its follower, or null if it isn't followed (compressed). */
    private final List<IndexedFollow> followers = new ArrayList<>();
    /** Per source, the parser its lines are read with (empty: per-line detection). */
    private final List<Optional<LogParser>> fileParsers;
    private final List<LogParser> parsers;
    /** Per source, the timezone its lines are read in (null: the system's). */
    private final List<ZoneId> zones;
    private final Host host;
    private final MergedFollow follow;
    /** Rows of each source handed over by its workers so far. */
    private final int[] counts;
    private final List<SwingWorker<Void, LogEntry>> workers = new ArrayList<>();
    private final Timer timer;

    /**
     * {@code ends} has, per source, where its index ends (-1: not followed); {@code paths}
     * the files the sources read, {@code zones} their source timezones and {@code fileParsers}
     * their parsers.
     */
    MergedTailer(MergedEntries view, List<Path> paths, List<Long> ends, List<ZoneId> zones,
                 List<Optional<LogParser>> fileParsers, List<LogParser> parsers, Host host) {
        this.view = view;
        for (int s = 0; s < ends.size(); s++) {
            followers.add(ends.get(s) >= 0 ? new IndexedFollow(paths.get(s), ends.get(s)) : null);
        }
        this.fileParsers = List.copyOf(fileParsers);
        this.parsers = parsers;
        this.zones = new ArrayList<>(zones);
        this.host = host;
        this.follow = new MergedFollow(view);
        this.counts = new int[ends.size()];
        for (int s = 0; s < counts.length; s++) counts[s] = view.order().rowCount(s);
        this.timer = new Timer(TICK_MS, e -> place());
    }

    /** True if at least one of the files can be followed. */
    boolean canFollow() {
        for (IndexedFollow f : followers) if (f != null) return true;
        return false;
    }

    void start() {
        stop();
        for (int s = 0; s < followers.size(); s++) {
            if (followers.get(s) != null) workers.add(tail(s));
        }
        workers.forEach(SwingWorker::execute);
        if (!workers.isEmpty()) timer.start();
    }

    void stop() {
        workers.forEach(w -> w.cancel(true));
        workers.clear();
        timer.stop();
    }

    private void place() {
        MergedFollow.Placed p = follow.step(System.currentTimeMillis());
        if (p != null) host.placed(p);
    }

    private SwingWorker<Void, LogEntry> tail(int source) {
        final IndexedEntries entries = view.sources().get(source).entries();
        final IndexedFollow follower = followers.get(source);
        return new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                follower.run(entries, new LineParser(fileParsers.get(source), parsers, zones.get(source)),
                        new IndexedFollow.Listener() {
                            @Override
                            public void entry(LogEntry e) {
                                publish(e);
                            }

                            @Override
                            public void textLost() {
                                SwingUtilities.invokeLater(host::textLost);
                            }
                        }, this::isCancelled, SAFETY_POLL_MS);
                return null;
            }

            @Override
            protected void process(List<LogEntry> chunk) {
                // Rows already in the index are counted even after a cancel, or the
                // count would fall behind the index for good.
                counts[source] += chunk.size();
                host.read(chunk);
                follow.arrived(source, counts[source], System.currentTimeMillis());
                place();
            }
        };
    }
}
