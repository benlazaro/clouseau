package com.droidenx.clouseau.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds places in source code that a log points at, so they can be opened in an IDE: the
 * frames of a JVM stack trace ({@code at com.acme.Foo.bar(Foo.java:42)}) and a logger named
 * after its class ({@code com.acme.OrderService}).
 *
 * <p>A place is a path relative to a source root ({@code com/acme/Foo.java}): the package of
 * the frame's class as folders, then the file the frame names. That's how the IDE finds it,
 * under any module's source roots, and how {@link SourceFolders} finds it on disk.
 */
public final class SourceRefs {

    private SourceRefs() {}

    /** A file relative to a source root, and a 1-based line (0 when there's none). */
    public record SourceRef(String path, int line) {
        /** The file's name, with the line if there is one: {@code Foo.java:42}. */
        public String label() {
            return line > 0 ? fileName() + ":" + line : fileName();
        }

        /** The file's name alone: {@code Foo.java}. */
        public String fileName() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /** A frame found in a text: the link's span ({@code Foo.java:42}) and where it points. */
    public record Found(int start, int end, SourceRef ref) {}

    /** Source files a JVM frame can name. */
    private static final String EXTENSIONS = "java|kt|kts|scala|groovy|gvy|clj|cljc";

    /**
     * {@code at [loader or module/]pkg.Class$Inner.method(File.java:42)}. Java 9+ puts the
     * module or class loader before a slash ({@code java.base/}, {@code app//}). Logback and
     * Log4j may add {@code ~[app.jar:1.0]} after the parenthesis, which doesn't matter here.
     */
    private static final Pattern FRAME = Pattern.compile(
            "\\bat\\s+(?:[\\w.$@-]*/)*([\\w$]+(?:\\.[\\w$]+)*)\\.[\\w$<>-]+"
            + "\\((([\\w$-]+\\.(?:" + EXTENSIONS + ")):(\\d{1,9}))\\)");

    /** Every stack frame in {@code text} that names a source file and line, in order. */
    public static List<Found> frames(String text) {
        List<Found> found = new ArrayList<>();
        if (text == null || text.indexOf("at ") < 0) return found;
        Matcher m = FRAME.matcher(text);
        while (m.find()) {
            int line;
            try {
                line = Integer.parseInt(m.group(4));
            } catch (NumberFormatException e) {
                continue;
            }
            found.add(new Found(m.start(2), m.end(2), new SourceRef(packagePath(m.group(1)) + m.group(3), line)));
        }
        return found;
    }

    /**
     * The files a logger named after its class could be: {@code com.acme.OrderService} gives
     * {@code com/acme/OrderService.java}, then {@code .kt}. Empty for a logger that doesn't look
     * like a class name: one word ({@code ROOT}), or a last part that isn't capitalized
     * ({@code org.hibernate.SQL} passes; {@code com.acme.orders} doesn't).
     */
    public static List<SourceRef> loggerClass(String logger) {
        if (logger == null) return List.of();
        String name = logger.strip();
        int dollar = name.indexOf('$');
        if (dollar >= 0) name = name.substring(0, dollar);   // a nested class lives in its outer class's file
        if (!name.matches("[A-Za-z_][\\w]*(\\.[A-Za-z_][\\w]*)+")) return List.of();
        String simple = name.substring(name.lastIndexOf('.') + 1);
        if (!Character.isUpperCase(simple.charAt(0))) return List.of();
        String base = name.replace('.', '/');
        return List.of(new SourceRef(base + ".java", 0), new SourceRef(base + ".kt", 0));
    }

    /** {@code com.acme.Foo$Bar} → {@code com/acme/}: the class's package as folders. */
    private static String packagePath(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot).replace('.', '/') + "/";
    }
}
