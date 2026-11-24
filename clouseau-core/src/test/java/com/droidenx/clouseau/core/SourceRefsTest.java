package com.droidenx.clouseau.core;

import com.droidenx.clouseau.core.SourceRefs.Found;
import com.droidenx.clouseau.core.SourceRefs.SourceRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRefsTest {

    @Test
    void findsEveryFrameWithItsPackagePathAndLine() {
        String trace = """
                java.lang.IllegalStateException: boom
                \tat com.acme.orders.OrderService.place(OrderService.java:42)
                \tat com.acme.orders.OrderService$Retry.lambda$run$0(OrderService.java:118) ~[orders.jar:1.2]
                \tat java.base/java.lang.Thread.run(Thread.java:1583)
                \tat app//com.acme.web.Handler.<init>(Handler.kt:7)
                \tat Main.main(Main.java:3)
                \t... 12 more""";
        List<Found> found = SourceRefs.frames(trace);
        assertThat(found).extracting(Found::ref).containsExactly(
                new SourceRef("com/acme/orders/OrderService.java", 42),
                new SourceRef("com/acme/orders/OrderService.java", 118),
                new SourceRef("java/lang/Thread.java", 1583),
                new SourceRef("com/acme/web/Handler.kt", 7),
                new SourceRef("Main.java", 3));
        // The link is the file and line, as the IDEs underline it.
        Found first = found.get(0);
        assertThat(trace.substring(first.start(), first.end())).isEqualTo("OrderService.java:42");
        assertThat(first.ref().label()).isEqualTo("OrderService.java:42");
        assertThat(first.ref().fileName()).isEqualTo("OrderService.java");
    }

    @Test
    void skipsFramesWithoutAFileOrLine() {
        assertThat(SourceRefs.frames("""
                \tat jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
                \tat com.acme.Gen.call(Unknown Source)
                \tat com.acme.Foo.bar(Foo.java)
                look at Foo.java:12 later""")).isEmpty();
        assertThat(SourceRefs.frames(null)).isEmpty();
    }

    @Test
    void loggerNamedAfterAClassGivesJavaThenKotlin() {
        assertThat(SourceRefs.loggerClass("com.acme.OrderService")).containsExactly(
                new SourceRef("com/acme/OrderService.java", 0), new SourceRef("com/acme/OrderService.kt", 0));
        assertThat(SourceRefs.loggerClass("com.acme.OrderService$Inner"))
                .first().isEqualTo(new SourceRef("com/acme/OrderService.java", 0));
        assertThat(SourceRefs.loggerClass("com.acme.OrderService").get(0).label()).isEqualTo("OrderService.java");
    }

    @Test
    void loggerThatIsNotAClassGivesNothing() {
        assertThat(SourceRefs.loggerClass("ROOT")).isEmpty();
        assertThat(SourceRefs.loggerClass("com.acme.orders")).isEmpty();
        assertThat(SourceRefs.loggerClass("main thread")).isEmpty();
        assertThat(SourceRefs.loggerClass("")).isEmpty();
        assertThat(SourceRefs.loggerClass(null)).isEmpty();
    }
}
