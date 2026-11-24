package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogSyntaxHighlighter.Role;
import com.droidenx.clouseau.api.LogSyntaxHighlighter.Span;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ToStringFormatTest {

    private static final String ORDER = "Saved Order(id=12, customer=Customer(name=Bob Smith, email=null), "
            + "items=[Item(sku=A-1, qty=2), Item(sku=B-7, qty=1)], status=SHIPPED, note=Leave at door, thanks) in 5ms";

    private static String noSpace(String s) {
        return s.replaceAll("\\s", "");
    }

    @Test
    void laysOutAWideStructureOneEntryPerLine() {
        assertThat(ToStringFormat.format(ORDER)).isEqualTo(String.join("\n",
                "Saved Order(",
                "  id=12,",
                "  customer=Customer(name=Bob Smith, email=null),",
                "  items=[Item(sku=A-1, qty=2), Item(sku=B-7, qty=1)],",
                "  status=SHIPPED,",
                "  note=Leave at door, thanks",
                ") in 5ms"));
    }

    @Test
    void breaksNestedStructuresThatDontFit() {
        String text = "Batch(items=[Item(sku=AAAAAAAAAAAAAAAAAAAA, qty=2), Item(sku=BBBBBBBBBBBBBBBBBBBBBBBB, qty=1),"
                + " Item(sku=CCCCCCCCCCCCCCC, qty=3)])";
        assertThat(ToStringFormat.format(text)).isEqualTo(String.join("\n",
                "Batch(",
                "  items=[",
                "    Item(sku=AAAAAAAAAAAAAAAAAAAA, qty=2),",
                "    Item(sku=BBBBBBBBBBBBBBBBBBBBBBBB, qty=1),",
                "    Item(sku=CCCCCCCCCCCCCCC, qty=3)",
                "  ]",
                ")"));
    }

    @Test
    void keepsEveryCharacterAndIsIdempotent() {
        String formatted = ToStringFormat.format(ORDER);
        assertThat(noSpace(formatted)).isEqualTo(noSpace(ORDER));
        assertThat(ToStringFormat.format(formatted)).isEqualTo(formatted);
    }

    @Test
    void shortStructuresStayOnOneLine() {
        String text = "User{id=1, name='Bob'} logged in";
        assertThat(ToStringFormat.contains(text)).isTrue();
        assertThat(ToStringFormat.format(text)).isEqualTo(text);
    }

    @Test
    void recognizesTheCommonShapes() {
        assertThat(ToStringFormat.find("Point[x=1, y=2]")).hasSize(1);                 // record
        assertThat(ToStringFormat.find("com.acme.Foo@1a2b3c[id=1,name=x]")).hasSize(1); // Apache ToStringBuilder
        assertThat(ToStringFormat.find("Outer.Inner(a=1)")).hasSize(1);                // nested class
        assertThat(ToStringFormat.find("mdc={user=bob, trace=abc}")).hasSize(1);        // a map
        assertThat(ToStringFormat.find("Old Foo(a=1) new Foo(a=2)")).hasSize(2);
    }

    @Test
    void leavesOrdinaryTextAlone() {
        assertThat(ToStringFormat.contains("[main] INFO Started in 3s")).isFalse();
        assertThat(ToStringFormat.contains("Calling Service(GET /orders)")).isFalse();
        assertThat(ToStringFormat.contains("compute(x=1)")).isFalse();                  // lowercase: a call
        assertThat(ToStringFormat.contains("Unclosed Order(id=1, name=x")).isFalse();
        assertThat(ToStringFormat.contains("{\"msg\": \"Order(id=1)\"}")).isFalse();   // inside a JSON string
        assertThat(ToStringFormat.contains("\tat com.acme.Db.write(Db.java:7)")).isFalse();
    }

    @Test
    void aCommaInTextSplitsOnlyBeforeAnEntry() {
        String text = "Msg(text=Hello, world, id=5)";
        ToStringFormat.Struct s = ToStringFormat.find(text).get(0);
        assertThat(s.entries()).hasSize(2);
        ToStringFormat.Scalar v = (ToStringFormat.Scalar) s.entries().get(0).value();
        assertThat(text.substring(v.start(), v.end())).isEqualTo("Hello, world");
    }

    @Test
    void bracketsInsideTextValuesStayBalanced() {
        String text = "Event(when=Optional[2024-01-01], tags=[a, b], extra={k=v})";
        assertThat(ToStringFormat.find(text)).hasSize(1);
        assertThat(ToStringFormat.find(text).get(0).entries()).hasSize(3);
    }

    @Test
    void aPathologicalMessageIsQuick() {
        String text = "Foo(a=".repeat(20_000);
        long start = System.nanoTime();
        assertThat(ToStringFormat.contains(text)).isFalse();
        assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
    }

    @Test
    void highlightsNamesKeysAndValues() {
        String text = "Foo@1f(id=12, name=Bob, state=ACTIVE, gone=null)";
        String roles = new ToStringHighlighter().highlight(text).stream()
                .sorted(java.util.Comparator.comparingInt(Span::start))
                .map(sp -> text.substring(sp.start(), sp.end()) + ":" + sp.role())
                .collect(Collectors.joining(" "));
        assertThat(roles).isEqualTo("Foo:ACCENT @1f:MUTED (:PUNCTUATION id:KEY =:OPERATOR 12:NUMBER ,:PUNCTUATION"
                + " name:KEY =:OPERATOR Bob:STRING ,:PUNCTUATION state:KEY =:OPERATOR ACTIVE:LITERAL ,:PUNCTUATION"
                + " gone:KEY =:OPERATOR null:LITERAL ):PUNCTUATION");
    }

    @Test
    void valueRoles() {
        assertThat(List.of("3.5", "-2", "10L", "true", "A", "'x'", "Hello there"))
                .extracting(ToStringHighlighter::role)
                .containsExactly(Role.NUMBER, Role.NUMBER, Role.NUMBER, Role.LITERAL, Role.STRING, Role.STRING, Role.STRING);
    }
}
