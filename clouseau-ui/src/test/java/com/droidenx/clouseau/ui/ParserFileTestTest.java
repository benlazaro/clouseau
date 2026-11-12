package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Parser Editor's file test counts lines that continue an entry (stack traces, wrapped
 * text) apart, so a parser isn't blamed for not matching them.
 */
class ParserFileTestTest {

    @Test
    void stackTraceLinesContinueTheEntryAbove() {
        for (String line : new String[] {
                "    at com.example.Foo.bar(Foo.java:10)",
                "\tat com.example.Foo.main(Foo.java:3)",
                "java.lang.IllegalStateException: boom",
                "com.example.PaymentException",
                "Caused by: java.io.IOException: closed",
                "... 12 more"}) {
            assertThat(ParserEditorDialog.isContinuation(line)).as(line).isTrue();
        }
    }

    @Test
    void entryLinesDoNot() {
        for (String line : new String[] {
                "2026-03-27 08:00:00.050 [pool-1] INFO  com.example.db.Pool - Pool warming up",
                "27/03/2026 08:00:00 | ERROR | main | IllegalStateException: boom",
                "ERROR Something failed"}) {
            assertThat(ParserEditorDialog.isContinuation(line)).as(line).isFalse();
        }
    }
}
