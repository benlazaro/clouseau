package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Loads several files into one merged view (D-17): checks each one's first lines, indexes
 * the ones that can go on a timeline in parallel (one thread per file, up to the number of
 * processors), and merges them. Runs on a background thread; no Swing.
 * <p>
 * A file can't go on the timeline if no parser recognizes its first lines or none of them
 * has a timestamp, or if a compressed file can't be unpacked to the cache folder. Those
 * are returned as {@link Result#skipped()}, for the caller to offer in their own tabs.
 */
@Slf4j
public final class MergeLoad {

    private MergeLoad() {}

    /** One file to merge: a plain or {@code .gz} file, or {@code entry} of the zip {@code path}. */
    public record File(Path path, String entry) {
        public File {
            path = path.toAbsolutePath();
        }

        /** The file name, or the zip entry's. */
        public String fileName() {
            return entry == null ? path.getFileName().toString() : new ZipLogs.Entry(entry, -1).fileName();
        }

        /** Where its annotations are kept: the file, or {@code <zip>!<entry>}. */
        public Path key() {
            return entry == null ? path : ZipLogs.entryKey(path, entry);
        }

        @Override public String toString() {
            return entry == null ? path.toString() : path + " › " + entry;
        }
    }

    /** Why a file was left out of the merge. */
    public enum Skip { UNRECOGNIZED, NO_TIMESTAMPS, CANT_UNPACK }

    public record Skipped(File file, Skip reason) {}

    /**
     * What the load produced. {@code entries} is null unless at least two files were
     * merged; then {@code mergeable} says how many could have been (zero or one).
     *
     * @param entries   the merged view, which the caller must close
     * @param mergeable the files that can go on a timeline, in the order given
     * @param fields    the custom fields of every merged file, in first-seen order
     * @param skipped   the files left out, and why
     * @param copies    unpacked copies of compressed files, for the caller to delete with the view
     * @param stopped   true if loading stopped for low memory or a full disk; nothing is merged then
     * @param diskFull  why it stopped: the disk filled while unpacking
     */
    public record Result(MergedEntries entries, List<File> mergeable, List<String> fields,
                         List<Skipped> skipped, List<Path> copies, boolean stopped, boolean diskFull) {}

    /**
     * Receives every entry as it is indexed, on the indexing threads: the caller can learn
     * loggers and field values (the filter bar does).
     */
    @FunctionalInterface
    public interface Sink {
        void entry(LogEntry entry);
    }

    /**
     * Loads {@code files}.
     *
     * @param parser      the parser every file is read with, or empty for per-line detection
     * @param zone        the source timezone every file is read in, or null for the system's
     * @param cacheDir    where compressed files are unpacked
     * @param rotatedText the message of a row whose text was lost to rotation
     * @param lowMemory   checked every 10,000 lines; true stops the load
     * @param cancelled   checked often; true stops the load and returns null
     */
    public static Result run(List<File> files, Optional<LogParser> parser, List<LogParser> parsers, ZoneId zone,
                             Path cacheDir, String rotatedText, BooleanSupplier lowMemory,
                             BooleanSupplier cancelled, Sink sink) throws IOException, InterruptedException {
        // 1. Which files can go on the timeline.
        List<File> mergeable = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (File f : files) {
            if (cancelled.getAsBoolean()) return null;
            Skip reason = check(f, parser, parsers, zone);
            if (reason == null) mergeable.add(f);
            else skipped.add(new Skipped(f, reason));
        }
        List<Path> copies = new ArrayList<>();
        if (mergeable.size() < 2) {
            return new Result(null, mergeable, List.of(), skipped, copies, false, false);
        }

        // 2. Unpacked copies for compressed files.
        Map<File, Path> copyOf = new HashMap<>();
        for (File f : List.copyOf(mergeable)) {
            if (f.entry() == null && !LogReaders.isCompressed(f.path())) continue;
            Path copy = newCopy(f, cacheDir);
            if (copy == null) {
                mergeable.remove(f);
                skipped.add(new Skipped(f, Skip.CANT_UNPACK));
            } else {
                copyOf.put(f, copy);
                copies.add(copy);
            }
        }
        if (mergeable.size() < 2) {
            copies.forEach(LogCache::delete);
            return new Result(null, mergeable, List.of(), skipped, List.of(), false, false);
        }

        // 3. Index every file at once.
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean diskFull = new AtomicBoolean();
        BooleanSupplier halt = () -> stop.get() || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
        int threads = Math.min(mergeable.size(), Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "clouseau-merge-index");
            t.setDaemon(true);
            return t;
        });
        List<Future<EntryIndex>> futures = new ArrayList<>();
        try {
            for (File f : mergeable) {
                Path copy = copyOf.get(f);
                futures.add(pool.submit(() -> index(f, copy, parser, parsers, zone, lowMemory, halt, stop, diskFull, sink)));
            }
            List<EntryIndex> indexes = new ArrayList<>();
            for (Future<EntryIndex> fu : futures) indexes.add(fu.get());
            if (cancelled.getAsBoolean()) {
                copies.forEach(LogCache::delete);
                return null;
            }
            if (stop.get()) {
                copies.forEach(LogCache::delete);
                return new Result(null, mergeable, List.of(), skipped, List.of(), true, diskFull.get());
            }

            // 4. Open each for reading back, and merge.
            List<String> names = names(mergeable);
            List<MergedEntries.Source> sources = new ArrayList<>();
            List<MergeOrder.Input> inputs = new ArrayList<>();
            Set<String> fields = new LinkedHashSet<>();
            try {
                for (int i = 0; i < mergeable.size(); i++) {
                    File f = mergeable.get(i);
                    EntryIndex index = indexes.get(i);
                    Path rows = copyOf.getOrDefault(f, f.path());
                    sources.add(new MergedEntries.Source(names.get(i), IndexedEntries.open(rows, index,
                            new LineParser(parser, parsers, zone), rotatedText)));
                    inputs.add(new MergeOrder.Input(index, index.size(), 0));
                    fields.addAll(index.fieldNames());
                }
            } catch (IOException e) {
                sources.forEach(s -> s.entries().close());
                copies.forEach(LogCache::delete);
                throw e;
            }
            MergeOrder order = MergeOrder.build(inputs, cancelled, done -> {});
            if (order == null) {
                sources.forEach(s -> s.entries().close());
                copies.forEach(LogCache::delete);
                return null;
            }
            parser.ifPresent(p -> fields.addAll(p.customFields()));
            return new Result(new MergedEntries(sources, order), mergeable, List.copyOf(fields), skipped,
                    copies, false, false);
        } catch (InterruptedException e) {
            copies.forEach(LogCache::delete);
            throw e;
        } catch (ExecutionException e) {
            copies.forEach(LogCache::delete);
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException re) throw re;
            throw new IOException(cause);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Null if {@code f} can go on a timeline, else why not. */
    static Skip check(File f, Optional<LogParser> parser, List<LogParser> parsers, ZoneId zone) throws IOException {
        LogSniffer.Sample sample = LogSniffer.read(f.path(), f.entry());
        Optional<LogParser> found = LogSniffer.detect(sample.lines(), parser, parsers);
        if (found.isEmpty()) return Skip.UNRECOGNIZED;
        LineParser lines = new LineParser(found, parsers, zone);
        for (String line : sample.lines()) {
            if (!line.isBlank() && lines.parse(line).timestamp() != null) return null;
        }
        return Skip.NO_TIMESTAMPS;
    }

    private static Path newCopy(File f, Path cacheDir) {
        try {
            long size = LogReaders.uncompressedSize(f.path(), f.entry());
            if (!LogCache.hasRoom(cacheDir, size)) {
                log.warn("Not enough free space in {} to unpack {} ({} bytes)", cacheDir, f, size);
                return null;
            }
            return LogCache.newCopy(cacheDir, f.path(), f.entry());
        } catch (IOException e) {
            log.warn("Can't create a copy of {} in {}", f, cacheDir, e);
            return null;
        }
    }

    private static EntryIndex index(File f, Path copy, Optional<LogParser> parser, List<LogParser> parsers,
                                    ZoneId zone, BooleanSupplier lowMemory, BooleanSupplier halt,
                                    AtomicBoolean stop, AtomicBoolean diskFull, Sink sink) throws IOException {
        EntryIndex index = new EntryIndex();
        LineParser lineParser = new LineParser(parser, parsers, zone);
        long[] count = {0};
        InputStream source = copy != null
                ? new CopyingInputStream(LogReaders.openBytes(f.path(), f.entry()), Files.newOutputStream(copy))
                : Files.newInputStream(f.path());
        try (InputStream in = source) {
            LineScanner.scan(in, (offset, length, text) -> {
                LogEntry e = lineParser.parse(text);
                index.add(offset, length, e);
                sink.entry(e);
                if (++count[0] % LogFileReader.DEFAULT_CHECK_INTERVAL == 0 && lowMemory.getAsBoolean()) {
                    log.warn("Low memory: stopped the merged load at {} lines of {}", count[0], f);
                    stop.set(true);
                }
            }, halt);
        }
        if (source instanceof CopyingInputStream c && c.writeFailure() != null && !halt.getAsBoolean()) {
            log.warn("Stopped unpacking {} to {} after {} entries", f, copy, count[0], c.writeFailure());
            diskFull.set(true);
            stop.set(true);
        }
        return index;
    }

    /**
     * The Source column's names: file names, or, where two are the same, with their folder
     * (or zip) in front.
     */
    static List<String> names(List<File> files) {
        Map<String, Integer> seen = new HashMap<>();
        for (File f : files) seen.merge(f.fileName(), 1, Integer::sum);
        List<String> out = new ArrayList<>();
        for (File f : files) {
            String name = f.fileName();
            if (seen.get(name) > 1) {
                if (f.entry() != null) {
                    name = f.path().getFileName() + "!" + f.entry();
                } else if (f.path().getParent() != null && f.path().getParent().getFileName() != null) {
                    name = f.path().getParent().getFileName() + "/" + name;
                }
            }
            out.add(name);
        }
        return out;
    }
}
