package com.droidenx.clouseau.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the JVM stack traces in a text line by line: which lines are exceptions, causes and
 * frames, and which frames are framework code (the JDK, Spring, Tomcat…) rather than the
 * application's. The stack-trace highlighter colors from it; a fold of framework frames
 * (a run of {@link Kind#FRAMEWORK_FRAME} lines, with the {@code ... 12 more} after it) would
 * read the same lines.
 *
 * <p>Java, Kotlin, Scala and Groovy traces all have this shape. Other languages' traces
 * (Python, .NET, JavaScript) aren't recognized.
 */
public final class StackTrace {

    private StackTrace() {}

    /** What a line of a stack trace is. */
    public enum Kind {
        /** The exception a trace starts with: {@code java.io.IOException: disk full}. */
        EXCEPTION,
        /** A cause or a suppressed exception: {@code Caused by: …}, {@code Suppressed: …}. */
        CAUSE,
        /** A frame of the application's own code. */
        FRAME,
        /** A frame of the JDK, a framework, a server or generated code. */
        FRAMEWORK_FRAME,
        /** Frames left out: {@code ... 42 more}, {@code ... 3 common frames omitted}. */
        OMITTED
    }

    /**
     * One line of a stack trace, as offsets into the text (half-open). {@code prefixEnd} ends a
     * frame's {@code at} (with any module, {@code java.base/}), a cause's {@code Caused by:} or a
     * thread's {@code Exception in thread "main"}, and equals
     * {@code start} when there's none; {@code classStart}/{@code classEnd} are the exception's
     * class or the frame's {@code pkg.Class.method}; {@code suffixStart} begins what follows a
     * frame's parenthesis ({@code ~[app.jar:1.0]}), {@code end} when there's nothing.
     * Unused offsets equal {@code start}.
     */
    public record Line(Kind kind, int start, int end, int prefixEnd,
                       int classStart, int classEnd, int suffixStart) {}

    /** {@code at [loader or module/]pkg.Class.method(...)[ ~[jar]]}. */
    private static final Pattern FRAME = Pattern.compile(
            "[ \\t]*(at)[ \\t]+((?:[\\w.$@-]*/)*)([\\w$]+(?:\\.[\\w$<>-]+)+)\\([^)\\n]*\\)(.*)");

    private static final Pattern OMITTED = Pattern.compile(
            "[ \\t]*\\.\\.\\.[ \\t]*\\d+[ \\t]+(?:more|common frames omitted)[ \\t]*");

    private static final Pattern CAUSE = Pattern.compile(
            "[ \\t]*((?:Caused by|Suppressed|Wrapped by):[ \\t]*)([\\w$]+(?:\\.[\\w$]+)+)(?::.*)?");

    private static final Pattern THREAD = Pattern.compile(
            "[ \\t]*(Exception in thread \"[^\"\\n]*\"[ \\t]+)([\\w$]+(?:\\.[\\w$]+)+)(?::.*)?");

    /** A qualified class name alone, or with its message: {@code pkg.FooException: msg}. */
    private static final Pattern EXCEPTION = Pattern.compile(
            "[ \\t]*([a-zA-Z_$][\\w$]*(?:\\.[\\w$]+)+)(?::.*)?");

    /**
     * Packages of code that isn't the application's: the JDK, languages' runtimes, servers,
     * frameworks and libraries that sit between the application's frames. Frames under them
     * are dimmed so the application's stand out.
     */
    private static final List<String> FRAMEWORK_PACKAGES = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "kotlin.", "kotlinx.", "scala.", "groovy.", "org.codehaus.groovy.", "clojure.",
            "org.springframework.", "org.apache.catalina.", "org.apache.coyote.", "org.apache.tomcat.",
            "org.eclipse.jetty.", "io.undertow.", "org.glassfish.", "org.jboss.", "io.netty.",
            "reactor.", "io.reactivex.", "rx.", "akka.", "org.apache.pekko.",
            "io.quarkus.", "io.micronaut.", "io.vertx.", "io.grpc.", "okhttp3.", "feign.",
            "org.hibernate.", "com.zaxxer.hikari.", "org.apache.ibatis.", "org.mybatis.",
            "com.fasterxml.jackson.", "com.google.gson.", "com.google.common.", "com.google.inject.",
            "net.bytebuddy.", "org.aspectj.", "cglib.", "org.apache.commons.", "org.apache.http.",
            "org.apache.hc.", "org.apache.kafka.", "org.slf4j.", "ch.qos.logback.",
            "org.apache.logging.log4j.", "io.micrometer.", "io.opentelemetry.",
            "org.junit.", "junit.", "org.testng.", "org.mockito.", "org.gradle.", "worker.org.gradle.",
            "org.apache.maven.", "org.eclipse.jdt.", "com.intellij.");

    /**
     * Whether {@code className} (dotted, {@code pkg.Class} or {@code pkg.Class.method}) is
     * framework code rather than the application's: under one of the built-in packages, or a
     * generated proxy or lambda ({@code $Proxy12}, {@code $$SpringCGLIB$$0}, {@code $$Lambda}).
     */
    public static boolean isFramework(String className) {
        if (className == null || className.isEmpty()) return false;
        if (className.contains("$$") || className.contains("$Proxy") || className.contains("$Lambda")) return true;
        for (String p : FRAMEWORK_PACKAGES) if (className.startsWith(p)) return true;
        return false;
    }

    /** Whether {@code text} has a stack frame on a line of its own: cheap, for {@code canHighlight}. */
    public static boolean hasFrames(String text) {
        if (text == null) return false;
        int from = 0;
        while (true) {
            int at = text.indexOf("at ", from);
            if (at < 0) return false;
            int lineStart = text.lastIndexOf('\n', at) + 1;
            if (text.substring(lineStart, at).isBlank()) {
                int lineEnd = text.indexOf('\n', at);
                if (FRAME.matcher(text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd)).matches()) return true;
            }
            from = at + 3;
        }
    }

    /**
     * The stack-trace lines of {@code text}, in order. Lines that aren't part of a trace (the
     * log message above it) aren't listed. An exception line counts only when a frame,
     * {@code ... N more} or cause follows it, so a message that merely names a class isn't one.
     */
    public static List<Line> lines(String text) {
        List<Line> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        List<int[]> spans = new ArrayList<>();
        int s = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                int e = i > s && text.charAt(i - 1) == '\r' ? i - 1 : i;
                spans.add(new int[] {s, e});
                s = i + 1;
            }
        }
        Line[] parsed = new Line[spans.size()];
        for (int n = 0; n < spans.size(); n++) {
            int start = spans.get(n)[0], end = spans.get(n)[1];
            parsed[n] = traceLine(text, start, end);
        }
        for (int n = 0; n < spans.size(); n++) {
            if (parsed[n] != null) {
                out.add(parsed[n]);
                continue;
            }
            int start = spans.get(n)[0], end = spans.get(n)[1];
            Line ex = exceptionLine(text, start, end);
            if (ex != null && n + 1 < parsed.length && parsed[n + 1] != null && parsed[n + 1].kind() != Kind.EXCEPTION) {
                out.add(ex);
            }
        }
        out.sort(java.util.Comparator.comparingInt(Line::start));
        return out;
    }

    /** A frame, cause, thread or omitted line; null for anything else. */
    private static Line traceLine(String text, int start, int end) {
        String line = text.substring(start, end);
        Matcher m = FRAME.matcher(line);
        if (m.matches()) {
            // A hidden class's frame looks like a module: com.acme.Foo$$Lambda/0x1f.run
            String cls = m.group(3);
            String before = m.group(2);
            boolean framework = isFramework(cls) || before.contains("$$") || before.contains("$Proxy");
            Kind kind = framework ? Kind.FRAMEWORK_FRAME : Kind.FRAME;
            int suffix = m.group(4).isBlank() ? end : start + m.start(4);
            return new Line(kind, start, end, start + m.start(3), start + m.start(3), start + m.end(3), suffix);
        }
        if (OMITTED.matcher(line).matches()) return new Line(Kind.OMITTED, start, end, start, start, start, end);
        for (Pattern p : new Pattern[] {CAUSE, THREAD}) {
            m = p.matcher(line);
            if (m.matches()) {
                return new Line(p == CAUSE ? Kind.CAUSE : Kind.EXCEPTION, start, end,
                        start + m.end(1), start + m.start(2), start + m.end(2), end);
            }
        }
        return null;
    }

    private static Line exceptionLine(String text, int start, int end) {
        Matcher m = EXCEPTION.matcher(text.substring(start, end));
        if (!m.matches()) return null;
        return new Line(Kind.EXCEPTION, start, end, start, start + m.start(1), start + m.end(1), end);
    }
}
