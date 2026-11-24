package com.droidenx.clouseau.core;

import com.droidenx.clouseau.core.JetBrainsIde.Result;
import com.droidenx.clouseau.core.SourceRefs.SourceRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Stands in for IDEs' built-in servers: each "has" some files and answers 200 or 404. */
class JetBrainsIdeTest {

    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    /** A fake IDE on {@code port}, with the IDE Remote Control plugin, whose projects have {@code files}. */
    private void ide(int port, String... files) throws IOException {
        ide(port, true, files);
    }

    /**
     * A fake IDE on {@code port}. With the plugin it answers 200 for its files, 404 for others
     * and 400 when no file is given; without it, 404 to everything. Requests without a file
     * aren't recorded.
     */
    private void ide(int port, boolean plugin, String... files) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/file", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            int status;
            if (query == null) {
                status = plugin ? 400 : 404;
            } else {
                requests.add(port + " " + query + " origin=" + exchange.getRequestHeaders().getFirst("Origin"));
                boolean has = List.of(files).stream().anyMatch(f -> query.startsWith("file=" + f));
                status = plugin && has ? 200 : 404;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.createContext("/api/about", exchange -> {
            byte[] body = ("{\"name\" : \"Fake IDE " + port + "\", \"productName\" : \"IDEA\", \"buildNumber\" : \"263.1\"}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        servers.add(server);
    }

    /** Three ports in a row that nothing is listening on yet. */
    private static int freePorts() throws IOException {
        for (int attempt = 0; attempt < 50; attempt++) {
            int base;
            try (ServerSocket s = new ServerSocket(0)) { base = s.getLocalPort(); }
            if (base + 2 > 65535) continue;
            boolean free = true;
            for (int p = base + 1; p <= base + 2 && free; p++) {
                try (ServerSocket s = new ServerSocket(p)) { /* free */ } catch (IOException e) { free = false; }
            }
            if (free) return base;
        }
        throw new IOException("no three free ports in a row");
    }

    @Test
    void opensInWhicheverIdeHasTheFile() throws Exception {
        int base = freePorts();
        ide(base, "com/acme/Other.java");
        ide(base + 2, "com/acme/Foo.java");
        JetBrainsIde ides = new JetBrainsIde("127.0.0.1", base, 3);

        assertThat(ides.open(new SourceRef("com/acme/Foo.java", 42))).isEqualTo(Result.OPENED);
        assertThat(requests).containsExactly(
                base + " file=com/acme/Foo.java&line=42 origin=http://localhost:" + base,
                (base + 2) + " file=com/acme/Foo.java&line=42 origin=http://localhost:" + (base + 2));
    }

    @Test
    void remembersWhichIdeOpenedItAndItsName() throws Exception {
        int base = freePorts();
        ide(base + 1, "com/acme/Foo.java");
        JetBrainsIde ides = new JetBrainsIde("127.0.0.1", base, 3);
        assertThat(ides.openedPort()).isEmpty();

        assertThat(ides.open(new SourceRef("com/acme/Foo.java", 1))).isEqualTo(Result.OPENED);
        assertThat(ides.openedPort()).hasValue(base + 1);
        assertThat(ides.about(base + 1)).contains(new JetBrainsIde.About(base + 1, "Fake IDE " + (base + 1), "IDEA", "263.1"));
        assertThat(ides.about()).map(JetBrainsIde.About::port).contains(base + 1);
        assertThat(ides.about(base)).as("nothing listening").isEmpty();
    }

    @Test
    void saysWhetherNoIdeHadItOrNoIdeAnswered() throws Exception {
        int base = freePorts();
        assertThat(new JetBrainsIde("127.0.0.1", base, 3).open(new SourceRef("a/B.java", 1))).isEqualTo(Result.NO_IDE);
        ide(base + 1, false, "a/B.java");
        assertThat(new JetBrainsIde("127.0.0.1", base, 3).open(new SourceRef("a/B.java", 1))).isEqualTo(Result.NO_PLUGIN);
        ide(base + 2);
        assertThat(new JetBrainsIde("127.0.0.1", base, 3).open(new SourceRef("a/B.java", 1))).isEqualTo(Result.NOT_FOUND);
    }

    @Test
    void saysWhetherAnIdeCanOpenFiles() throws Exception {
        int base = freePorts();
        ide(base);
        ide(base + 1, false);
        JetBrainsIde ides = new JetBrainsIde("127.0.0.1", base, 3);
        assertThat(ides.canOpenFiles(base)).as("with the plugin").isTrue();
        assertThat(ides.canOpenFiles(base + 1)).as("without it").isFalse();
        assertThat(ides.canOpenFiles(base + 2)).as("nothing listening").isFalse();
        assertThat(requests).as("asks with no file").isEmpty();
    }

    @Test
    void triesEachCandidateUntilOneOpensAndLeavesOutAMissingLine() throws Exception {
        int base = freePorts();
        ide(base, "com/acme/Svc.kt");
        Result r = new JetBrainsIde("127.0.0.1", base, 1).open(SourceRefs.loggerClass("com.acme.Svc"));
        assertThat(r).isEqualTo(Result.OPENED);
        assertThat(requests).extracting(s -> s.split(" ")[1])
                .containsExactly("file=com/acme/Svc.java", "file=com/acme/Svc.kt");
    }
}
