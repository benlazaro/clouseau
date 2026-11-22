package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The detail pane's find strip: counting, stepping, options, and keeping its query as the text changes. */
class DetailFindBarTest {

    private final JTextPane text = new JTextPane();
    private final AtomicInteger focusedBack = new AtomicInteger();

    private DetailFindBar bar(String content) throws Exception {
        DetailFindBar[] bar = new DetailFindBar[1];
        SwingUtilities.invokeAndWait(() -> {
            text.setText(content);
            bar[0] = new DetailFindBar(text, focusedBack::incrementAndGet);
        });
        return bar[0];
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    @Test
    void openWithSeedFindsAndGoesToTheFirstMatch() throws Exception {
        DetailFindBar bar = bar("{\n  \"id\": 7,\n  \"child\": { \"id\": 8 }\n}");
        edt(() -> bar.open("\"id\""));
        assertThat(bar.isVisible()).isTrue();
        assertThat(bar.matchCount()).isEqualTo(2);
        assertThat(bar.current()).isZero();
        assertThat(bar.counterText()).isEqualTo("1 / 2");
        assertThat(text.getHighlighter().getHighlights()).hasSize(2);
    }

    @Test
    void marksSitOnTheMatchedTextBelowOtherLines() throws Exception {
        // getText() writes "\r\n" on Windows; the marks must use the document's own offsets.
        String content = "{\n  \"orderId\": 1,\n  \"items\": [\n    { \"sku\": \"W\" }\n  ]\n}";
        DetailFindBar bar = bar(content);
        System.setProperty("line.separator", "\r\n");
        try {
            edt(() -> bar.open("sku"));
        } finally {
            System.setProperty("line.separator", System.lineSeparator());
        }
        var h = text.getHighlighter().getHighlights()[0];
        assertThat(text.getDocument().getText(h.getStartOffset(), h.getEndOffset() - h.getStartOffset()))
                .isEqualTo("sku");
    }

    @Test
    void stepWrapsBothWays() throws Exception {
        DetailFindBar bar = bar("x a x b x");
        edt(() -> bar.open("x"));
        edt(() -> bar.step(+1));
        edt(() -> bar.step(+1));
        assertThat(bar.counterText()).isEqualTo("3 / 3");
        edt(() -> bar.step(+1));
        assertThat(bar.current()).isZero();
        edt(() -> bar.step(-1));
        assertThat(bar.current()).isEqualTo(2);
    }

    @Test
    void steppingPastTheEndSaysItWrapped() throws Exception {
        DetailFindBar bar = bar("x a x");
        edt(() -> bar.open("x"));
        assertThat(bar.wrapNoteText()).isNull();
        edt(() -> bar.step(+1));
        assertThat(bar.wrapNoteText()).isNull();                // 1 → 2, no wrap
        edt(() -> bar.step(+1));
        assertThat(bar.wrapNoteText()).isEqualTo(Messages.get("find.wrapped.top"));
        assertThat(bar.field().getBackground()).isNotEqualTo(UIManager.getColor("TextField.background"));
        edt(() -> bar.step(-1));
        assertThat(bar.wrapNoteText()).isEqualTo(Messages.get("find.wrapped.bottom"));
        edt(() -> bar.step(-1));
        assertThat(bar.wrapNoteText()).isNull();                // an ordinary step hides it
        assertThat(bar.field().getBackground()).isEqualTo(UIManager.getColor("TextField.background"));
    }

    @Test
    void wrapDetection() {
        assertThat(WrapNotice.isWrap(2, 0, +1, 3)).isTrue();
        assertThat(WrapNotice.isWrap(0, 2, -1, 3)).isTrue();
        assertThat(WrapNotice.isWrap(0, 1, +1, 3)).isFalse();
        assertThat(WrapNotice.isWrap(2, 1, -1, 3)).isFalse();
        assertThat(WrapNotice.isWrap(0, 0, +1, 1)).isTrue();    // the only match, again
        assertThat(WrapNotice.isWrap(-1, 0, +1, 3)).isFalse();  // the first step
    }

    @Test
    void typingIsFoundOnTheNextStepWithoutWaitingForTheDebounce() throws Exception {
        DetailFindBar bar = bar("PID|1\nOBX|1\nOBX|2");
        edt(() -> bar.open(null));
        edt(() -> bar.field().setText("obx"));
        edt(() -> bar.step(+1));
        assertThat(bar.matchCount()).isEqualTo(2);
    }

    @Test
    void matchCaseAndRegexToggles() throws Exception {
        DetailFindBar bar = bar("Error error ERROR");
        edt(() -> bar.open("error"));
        assertThat(bar.matchCount()).isEqualTo(3);
        edt(() -> bar.caseToggle().doClick());
        assertThat(bar.matchCount()).isEqualTo(1);
        edt(() -> { bar.caseToggle().doClick(); bar.regexToggle().doClick(); bar.field().setText("^err"); bar.step(+1); });
        assertThat(bar.matchCount()).isEqualTo(1);
    }

    @Test
    void invalidRegexSaysSo() throws Exception {
        DetailFindBar bar = bar("abc");
        edt(() -> { bar.regexToggle().doClick(); bar.open("("); });
        assertThat(bar.matchCount()).isZero();
        assertThat(bar.counterText()).isEqualTo(Messages.get("detail.find.invalid"));
    }

    @Test
    void newEntryStartsAtItsFirstMatchAndTheSameEntryKeepsTheCurrentOne() throws Exception {
        DetailFindBar bar = bar("id id id");
        edt(() -> { bar.open("id"); bar.step(+1); bar.step(+1); });
        assertThat(bar.current()).isEqualTo(2);
        edt(() -> { text.setText("id id id"); bar.textChanged(false); });
        assertThat(bar.current()).isEqualTo(2);
        edt(() -> { text.setText("other id"); bar.textChanged(true); });
        assertThat(bar.matchCount()).isEqualTo(1);
        assertThat(bar.current()).isZero();
    }

    @Test
    void closeDropsTheMarksAndGivesFocusBack() throws Exception {
        DetailFindBar bar = bar("a a");
        edt(() -> bar.open("a"));
        edt(bar::close);
        assertThat(bar.isVisible()).isFalse();
        assertThat(bar.hasQuery()).isFalse();
        assertThat(text.getHighlighter().getHighlights()).isEmpty();
        assertThat(focusedBack.get()).isEqualTo(1);
        // Hidden, a redraw doesn't mark anything.
        edt(() -> bar.textChanged(true));
        assertThat(text.getHighlighter().getHighlights()).isEmpty();
    }
}
