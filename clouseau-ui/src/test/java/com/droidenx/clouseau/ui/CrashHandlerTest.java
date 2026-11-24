package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrashHandlerTest {

    private final List<CrashHandler.Report> shown = new ArrayList<>();
    private final CrashHandler handler = CrashHandler.forTest(shown::add);

    @Test
    void showsAnErrorOnceEvenIfItRepeats() throws Exception {
        Thread edt = new Thread("AWT-EventQueue-0");
        for (int i = 0; i < 3; i++) handler.uncaughtException(edt, failure("repaint"));
        flushEdt();

        assertThat(shown).hasSize(1);
        assertThat(shown.get(0).thread()).isEqualTo("AWT-EventQueue-0");
    }

    @Test
    void showsADifferentErrorToo() throws Exception {
        Thread worker = new Thread("worker");
        handler.uncaughtException(worker, failure("first"));
        handler.uncaughtException(worker, new IllegalStateException("second"));
        flushEdt();

        assertThat(shown).extracting(r -> r.error().getClass().getSimpleName())
                .containsExactly("NullPointerException", "IllegalStateException");
    }

    @Test
    void signatureTellsCausesApart() {
        Exception a = new RuntimeException("wrapper", new java.io.IOException("disk"));
        Exception b = new RuntimeException("wrapper", new IllegalArgumentException("bad"));

        assertThat(CrashHandler.signature(a)).contains("java.io.IOException");
        assertThat(CrashHandler.signature(a)).isNotEqualTo(CrashHandler.signature(b));
    }

    @Test
    void detailsHaveWhatAnIssueNeeds() {
        String details = CrashHandler.details("AWT-EventQueue-0",
                new IllegalStateException("broken", new java.io.IOException("disk full")), "1.0.0");

        assertThat(details).startsWith("Clouseau v1.0.0\nPlugin API v" + com.droidenx.clouseau.api.ClouseauApi.VERSION + "\nJava ")
                .contains("Thread: AWT-EventQueue-0")
                .contains("java.lang.IllegalStateException: broken")
                .contains("Caused by: java.io.IOException: disk full")
                .contains(System.getProperty("os.name"));
    }

    private static NullPointerException failure(String where) {
        return new NullPointerException(where);   // same stack each call: thrown from one line
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }
}
