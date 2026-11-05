package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.FileFollower;
import com.droidenx.clouseau.core.IndexedEntries;
import com.droidenx.clouseau.core.LineParser;
import com.droidenx.clouseau.core.MergedEntries;
import com.droidenx.clouseau.core.MergedFollow;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Follows the files of a merged tab (D-17 step 5): a tail worker for each file that can be
 * followed (not compressed), which adds the lines it reads to that file's index, and a
 * {@link MergedFollow} that holds them back and places them in timeline order. A Swing
 * timer releases held rows when their time is up.
 * <p>
 * One tailer lives as long as the tab shows the same merge; {@link #start} and
 * {@link #stop} follow the Follow toggle and keep each file's position. Call both on the EDT.
 */
@Slf4j
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
    private final List<Path> paths;
    /** Per source, the byte position following resumes from; -1 if it isn't followed. */
    private final long[] positions;
    private final Optional<LogParser> parser;
    private final List<LogParser> parsers;
    private final ZoneId sourceTimezone;
    private final Host host;
    private final MergedFollow follow;
    /** Rows of each source handed over by its workers so far. */
    private final int[] counts;
    /** One worker follows a source at a time; a cancelled one may still be finishing. */
    private final ReentrantLock[] locks;
    private final List<SwingWorker<Void, LogEntry>> workers = new ArrayList<>();
    private final Timer timer;

    /**
     * {@code ends} has, per source, where its index ends (-1: not followed); {@code paths}
     * the files the sources read.
     */
    MergedTailer(MergedEntries view, List<Path> paths, List<Long> ends, Optional<LogParser> parser,
                 List<LogParser> parsers, ZoneId sourceTimezone, Host host) {
        this.view = view;
        this.paths = List.copyOf(paths);
        this.positions = new long[ends.size()];
        for (int s = 0; s < positions.length; s++) positions[s] = ends.get(s);
        this.parser = parser;
        this.parsers = parsers;
        this.sourceTimezone = sourceTimezone;
        this.host = host;
        this.follow = new MergedFollow(view);
        this.counts = new int[positions.length];
        for (int s = 0; s < counts.length; s++) counts[s] = view.order().rowCount(s);
        this.locks = new ReentrantLock[positions.length];
        for (int s = 0; s < locks.length; s++) locks[s] = new ReentrantLock();
        this.timer = new Timer(TICK_MS, e -> place());
    }

    /** True if at least one of the files can be followed. */
    boolean canFollow() {
        for (long p : positions) if (p >= 0) return true;
        return false;
    }

    void start() {
        stop();
        for (int s = 0; s < positions.length; s++) {
            if (positions[s] >= 0) workers.add(tail(s));
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
        final Path file = paths.get(source);
        return new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                LineParser lineParser = new LineParser(parser, parsers, sourceTimezone);
                try {
                    locks[source].lockInterruptibly();
                } catch (InterruptedException e) {
                    return null;                    // cancelled while the last worker finished
                }
                try {
                    if (isCancelled()) return null;
                    FileFollower.follow(file, positions[source], new FileFollower.Listener() {
                        @Override
                        public void line(long offset, int length, String text) {
                            LogEntry e = lineParser.parse(text);
                            entries.index().add(offset, length, e);
                            publish(e);
                        }

                        @Override
                        public void rotated(FileFollower.Rotation how) {
                            log.info("{} was rotated ({})", file, how);
                            if (entries.rotated()) SwingUtilities.invokeLater(host::textLost);
                        }

                        @Override
                        public void position(long position) {
                            if (!isCancelled()) positions[source] = position;
                        }
                    }, this::isCancelled, SAFETY_POLL_MS);
                } catch (InterruptedException e) {
                    // cancel(true) interrupts the wait: following stopped
                } finally {
                    locks[source].unlock();
                }
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
