package com.droidenx.clouseau.core;

import java.util.List;
import java.util.Map;

/**
 * What was open, so it can be opened again (UC-17, D-19): every window and its tabs, and
 * how each tab reads its logs. Plain data, read and written by {@link SessionStore}; the
 * UI turns windows into it and back. Never holds a password.
 *
 * @param activeSession the named session in use, or null; only meaningful in the last session
 * @param windows       the windows, in the order they were opened
 */
public record Session(String activeSession, List<Window> windows) {

    public Session {
        windows = windows == null ? List.of() : List.copyOf(windows);
    }

    /** The same windows, recording {@code name} as the named session in use. */
    public Session withActiveSession(String name) {
        return new Session(name, windows);
    }

    /** How many tabs the session has, across its windows. */
    public int tabCount() {
        return windows.stream().mapToInt(w -> w.tabs().size()).sum();
    }

    /**
     * One window.
     *
     * @param bounds      its usual place, in screen coordinates (D-19: a window placed elsewhere
     *                    because its screen is missing keeps these)
     * @param screen      the ID of the screen it was on, or null
     * @param maximized   whether it was maximized
     * @param selectedTab the selected tab, or -1
     */
    public record Window(Bounds bounds, String screen, boolean maximized, int selectedTab, List<Tab> tabs) {
        public Window {
            tabs = tabs == null ? List.of() : List.copyOf(tabs);
        }
    }

    public record Bounds(int x, int y, int width, int height) {}

    /** What a tab shows. */
    public enum Kind { FILE, MERGED, SSH, SOURCE }

    /**
     * One tab. Which fields are set depends on {@code kind}; the rest are null.
     *
     * @param path     FILE: the log, or the zip that holds it
     * @param entry    FILE: the zip entry, or null for a plain file
     * @param parser   the tab's parser by name, or null for automatic detection
     * @param timezone FILE, SSH, SOURCE: the source timezone's ID, or null for the default
     * @param files    MERGED: each file and how it is read
     * @param ssh      SSH: the connection, without its password
     * @param source   SOURCE: the plugin source and its settings, without secrets
     * @param follow   whether follow mode was on
     * @param columns  the column layout, or null for the default
     * @param filters  the filters, or null when nothing is filtered
     */
    public record Tab(Kind kind, String path, String entry, String parser, String timezone,
                      List<MergedFile> files, Ssh ssh, PluginSource source,
                      boolean follow, Layout columns, Filters filters) {

        public Tab {
            files = files == null ? null : List.copyOf(files);
        }

        public static Tab file(String path, String entry, String parser, String timezone) {
            return new Tab(Kind.FILE, path, entry, parser, timezone, null, null, null, false, null, null);
        }

        public static Tab merged(List<MergedFile> files, String parser) {
            return new Tab(Kind.MERGED, null, null, parser, null, files, null, null, false, null, null);
        }

        public static Tab ssh(Ssh ssh, String parser, String timezone) {
            return new Tab(Kind.SSH, null, null, parser, timezone, null, ssh, null, true, null, null);
        }

        public static Tab source(PluginSource source, String parser, String timezone) {
            return new Tab(Kind.SOURCE, null, null, parser, timezone, null, null, source, true, null, null);
        }

        /** This tab with the state it has now: follow mode, layout and filters. */
        public Tab withState(boolean follow, Layout columns, Filters filters) {
            return new Tab(kind, path, entry, parser, timezone, files, ssh, source, follow, columns, filters);
        }

        /** This tab with a different source timezone. */
        public Tab withTimezone(String timezone) {
            return new Tab(kind, path, entry, parser, timezone, files, ssh, source, follow, columns, filters);
        }

        /** This merged tab with its files as they are read now. */
        public Tab withFiles(List<MergedFile> files) {
            return new Tab(kind, path, entry, parser, timezone, files, ssh, source, follow, columns, filters);
        }
    }

    /**
     * One file of a merged tab (the same facts a version 2 review bundle keeps, D-17).
     *
     * @param parser   this file's own parser by name, or null to use the tab's
     * @param timezone the source timezone's ID, or null for the default
     */
    public record MergedFile(String path, String entry, String parser, String timezone, long offsetMillis) {}

    /**
     * An SSH connection without secrets.
     *
     * @param auth            "password", "key" or "none"
     * @param keyPassphrase   whether the key file needs a passphrase (the passphrase itself is never kept)
     */
    public record Ssh(String host, int port, String user, String auth, String keyFile,
                      boolean keyPassphrase, String remotePath) {}

    /**
     * A plugin source: its provider (by class name) and its settings, without the ones of
     * type {@code PASSWORD}.
     */
    public record PluginSource(String provider, String name, Map<String, String> settings) {
        public PluginSource {
            settings = settings == null ? Map.of() : Map.copyOf(settings);
        }
    }

    /** A column layout: each column's model index, visibility and width, in view order. */
    public record Layout(List<Column> columns) {
        public Layout {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    public record Column(int model, boolean visible, int width) {}

    /**
     * A tab's filters. Time fields are kept as typed, since they're read in the display
     * timezone and may be a time of day only.
     *
     * @param levels          the levels shown
     * @param excludedLoggers loggers hidden
     * @param excludedFields  per field, the values hidden ("" for an empty value)
     * @param hiddenSources   a merged tab's hidden files, by position
     */
    public record Filters(List<String> levels, List<String> excludedLoggers,
                          Map<String, List<String>> excludedFields,
                          String from, String to, String text, List<Integer> hiddenSources) {
        public Filters {
            levels = levels == null ? List.of() : List.copyOf(levels);
            excludedLoggers = excludedLoggers == null ? List.of() : List.copyOf(excludedLoggers);
            excludedFields = excludedFields == null ? Map.of() : Map.copyOf(excludedFields);
            from = from == null ? "" : from;
            to = to == null ? "" : to;
            text = text == null ? "" : text;
            hiddenSources = hiddenSources == null ? List.of() : List.copyOf(hiddenSources);
        }
    }
}
