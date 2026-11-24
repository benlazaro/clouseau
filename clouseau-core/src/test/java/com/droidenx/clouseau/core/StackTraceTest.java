package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogSyntaxHighlighter.Role;
import com.droidenx.clouseau.api.LogSyntaxHighlighter.Span;
import com.droidenx.clouseau.core.StackTrace.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StackTraceTest {

    private static final String TRACE = String.join("\n",
            "Order failed",
            "java.lang.IllegalStateException: no stock",
            "\tat com.acme.OrderService.place(OrderService.java:42) ~[app.jar:1.0]",
            "\tat java.base/java.lang.Thread.run(Thread.java:1583)",
            "\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)",
            "\tat com.acme.OrderController$$SpringCGLIB$$0.place(<generated>)",
            "Caused by: java.io.IOException: disk full",
            "\tat com.acme.Db.write(Db.java:7)",
            "\t... 3 more",
            "\tSuppressed: java.lang.RuntimeException",
            "\t\tat com.acme.Db.close(Db.java:9)");

    private static List<Kind> kinds(String text) {
        return StackTrace.lines(text).stream().map(StackTrace.Line::kind).toList();
    }

    @Test
    void classifiesEveryLineOfATrace() {
        assertThat(kinds(TRACE)).containsExactly(
                Kind.EXCEPTION, Kind.FRAME, Kind.FRAMEWORK_FRAME, Kind.FRAMEWORK_FRAME, Kind.FRAMEWORK_FRAME,
                Kind.CAUSE, Kind.FRAME, Kind.OMITTED, Kind.CAUSE, Kind.FRAME);
    }

    @Test
    void theMessageAboveIsNotPartOfTheTrace() {
        assertThat(StackTrace.lines(TRACE).get(0).start()).isEqualTo("Order failed\n".length());
    }

    @Test
    void aClassNameWithoutFramesIsNotAnException() {
        assertThat(StackTrace.lines("Loaded com.acme.Config\ncom.acme.Other: fine")).isEmpty();
        assertThat(StackTrace.hasFrames("we were at home")).isFalse();
    }

    @Test
    void offsetsPointAtTheClassAndThePrefix() {
        StackTrace.Line cause = StackTrace.lines(TRACE).get(5);
        assertThat(TRACE.substring(cause.start(), cause.prefixEnd())).isEqualTo("Caused by: ");
        assertThat(TRACE.substring(cause.classStart(), cause.classEnd())).isEqualTo("java.io.IOException");

        StackTrace.Line frame = StackTrace.lines(TRACE).get(1);
        assertThat(TRACE.substring(frame.start(), frame.prefixEnd())).isEqualTo("\tat ");
        assertThat(TRACE.substring(frame.classStart(), frame.classEnd())).isEqualTo("com.acme.OrderService.place");
        assertThat(TRACE.substring(frame.suffixStart(), frame.end())).isEqualTo(" ~[app.jar:1.0]");

        StackTrace.Line jdk = StackTrace.lines(TRACE).get(2);
        assertThat(TRACE.substring(jdk.start(), jdk.prefixEnd())).isEqualTo("\tat java.base/");
    }

    @Test
    void threadPrefixAndWindowsLineEnds() {
        String text = "Exception in thread \"main\" java.lang.NullPointerException\r\n\tat com.acme.Main.main(Main.java:3)\r\n";
        List<StackTrace.Line> lines = StackTrace.lines(text);
        assertThat(lines).extracting(StackTrace.Line::kind).containsExactly(Kind.EXCEPTION, Kind.FRAME);
        assertThat(text.substring(lines.get(0).classStart(), lines.get(0).classEnd())).isEqualTo("java.lang.NullPointerException");
        assertThat(text.charAt(lines.get(1).end() - 1)).isEqualTo(')');
    }

    @Test
    void frameworkPackagesAndGeneratedClasses() {
        assertThat(StackTrace.isFramework("jdk.internal.reflect.DirectMethodHandleAccessor.invoke")).isTrue();
        assertThat(StackTrace.isFramework("org.apache.catalina.core.StandardWrapperValve")).isTrue();
        assertThat(StackTrace.isFramework("com.acme.Foo$$Lambda/0x123.run")).isTrue();
        assertThat(StackTrace.isFramework("com.acme.Foo")).isFalse();
        assertThat(StackTrace.isFramework("javaapp.Foo")).isFalse();
        assertThat(kinds("x.Boom\n\tat com.acme.Foo$$Lambda/0x000001f4.run(Unknown Source)"))
                .containsExactly(Kind.EXCEPTION, Kind.FRAMEWORK_FRAME);
    }

    @Test
    void highlighterDimsFrameworkAndMarksExceptions() {
        StackTraceHighlighter h = new StackTraceHighlighter();
        assertThat(h.canHighlight(TRACE)).isTrue();
        assertThat(h.canHighlight("Order failed")).isFalse();
        List<Span> spans = h.highlight(TRACE);

        assertThat(spans).contains(span("java.lang.IllegalStateException", Role.ERROR));
        assertThat(spans).contains(span("Caused by:", Role.KEYWORD));
        assertThat(spans).contains(span("Suppressed:", Role.KEYWORD));
        assertThat(spans).contains(span("at java.base/java.lang.Thread.run(Thread.java:1583)", Role.MUTED));
        assertThat(spans).contains(span("... 3 more", Role.MUTED));
        assertThat(spans).contains(span(" ~[app.jar:1.0]", Role.MUTED));
        // the application's frame: its "at " is dimmed and its Class.method stands out
        assertThat(spans).contains(span("at ", Role.MUTED));
        assertThat(spans).contains(span("OrderService.place", Role.ACCENT));
        assertThat(spans).noneMatch(s -> TRACE.substring(s.start(), s.end()).contains("com.acme.OrderService"));
    }

    private static Span span(String text, Role role) {
        int at = TRACE.indexOf(text);
        return new Span(at, at + text.length(), role);
    }
}
