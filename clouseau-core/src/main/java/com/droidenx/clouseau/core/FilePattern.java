package com.droidenx.clouseau.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A file name pattern such as {@code app.log*}, typed in the open dialog to open a set of
 * files as one merged view (UC-12, D-17). {@code *} and {@code ?} work as in a shell, in
 * one folder; the set is fixed once expanded.
 */
public final class FilePattern {

    private FilePattern() {}

    /** True if {@code name} is a pattern rather than a file name. */
    public static boolean isPattern(String name) {
        return name.indexOf('*') >= 0 || name.indexOf('?') >= 0;
    }

    /** The regular files in {@code dir} whose names match {@code pattern}, sorted by name. */
    public static List<Path> expand(Path dir, String pattern) throws IOException {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path p : files) {
                if (Files.isRegularFile(p) && matcher.matches(p.getFileName())) out.add(p);
            }
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }
}
