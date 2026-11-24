package com.droidenx.clouseau.core;

import com.droidenx.clouseau.core.NetworkLogSource.Listen;
import com.droidenx.clouseau.core.NetworkLogSource.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the receiver on loopback ports only: binding every interface would make Windows ask
 * about its firewall.
 */
class NetworkLogSourceTest {

    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CountDownLatch ended = new CountDownLatch(1);
    private NetworkLogSource source;

    @AfterEach
    void tearDown() throws Exception {
        if (source != null) source.close();
        assertThat(ended.await(5, TimeUnit.SECONDS)).as("open returns once closed").isTrue();
    }

    @Test
    void receivesTcpLinesFromSeveralSenders() throws Exception {
        int port = start(Protocol.TCP);
        try (Socket a = connect(port); Socket b = connect(port)) {
            send(a, "first from a\r\n");
            assertThat(next()).isEqualTo("first from a");
            send(b, "from b\nsecond line of b\n\n");
            assertThat(next()).isEqualTo("from b");
            assertThat(next()).isEqualTo("second line of b");
            send(a, "last, without a newline");
        }
        assertThat(next()).isEqualTo("last, without a newline");
    }

    @Test
    void readsOctetCountedSyslogFrames() throws Exception {
        int port = start(Protocol.TCP);
        String first = "<34>1 - h app - - - one\nand two";
        String second = "<13>Oct 11 22:14:15 host app: three";
        try (Socket s = connect(port)) {
            send(s, bytes(first).length + " " + first + bytes(second).length + " " + second);
        }
        assertThat(next()).isEqualTo("<34>1 - h app - - - one");
        assertThat(next()).isEqualTo("and two");
        assertThat(next()).isEqualTo(second);
    }

    @Test
    void receivesUdpDatagrams() throws Exception {
        int port = start(Protocol.UDP);
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] data = bytes("<14>one datagram\nwith two lines\n");
            s.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), port));
        }
        assertThat(next()).isEqualTo("<14>one datagram");
        assertThat(next()).isEqualTo("with two lines");
    }

    @Test
    void reportsATakenPort() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            source = new NetworkLogSource(Protocol.TCP, taken.getLocalPort(), Listen.LOCAL);
            assertThatThrownBy(() -> source.open(l -> {}, () -> {}))
                    .isInstanceOf(NetworkLogSource.PortInUseException.class)
                    .hasMessageContaining(String.valueOf(taken.getLocalPort()));
        }
        ended.countDown();
    }

    @Test
    void closingFreesThePort() throws Exception {
        int port = start(Protocol.TCP);
        source.close();
        assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();
        try (ServerSocket again = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))) {
            assertThat(again.getLocalPort()).isEqualTo(port);
        }
    }

    @Test
    void namesWhatItListensOn() {
        assertThat(new NetworkLogSource(Protocol.TCP, 5140, Listen.LOCAL).getName()).isEqualTo("TCP :5140");
        assertThat(new NetworkLogSource(Protocol.UDP, 514, Listen.ALL).getName()).isEqualTo("UDP *:514");
        assertThatThrownBy(() -> new NetworkLogSource(Protocol.TCP, 0, Listen.LOCAL))
                .isInstanceOf(IllegalArgumentException.class);
        ended.countDown();
    }

    @Test
    void framesAreReadAsLinesUnlessTheyStartWithAPriority() throws IOException {
        assertThat(frames("12 <b>bold</b>\n")).containsExactly("12 <b>bold</b>");
        assertThat(frames("05 <34>x\n")).containsExactly("05 <34>x");
        assertThat(frames("9 <999>abc\n")).containsExactly("9 <999>abc");
        assertThat(frames("4 <1>ab4 <2>cd")).containsExactly("<1>a", "b4 <2>cd");
        ended.countDown();
    }

    @Test
    void cutsALineThatIsTooLong() throws IOException {
        String longLine = "x".repeat(NetworkLogSource.MAX_LINE_BYTES + 10);
        List<String> read = frames(longLine + "\nnext\n");
        assertThat(read).hasSize(2);
        assertThat(read.get(0)).hasSize(NetworkLogSource.MAX_LINE_BYTES);
        assertThat(read.get(1)).isEqualTo("next");
        ended.countDown();
    }

    @Test
    void decodesBadBytesAsReplacementsAndDropsABom() {
        byte[] data = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'o', 'k', (byte) 0xFF};
        assertThat(NetworkLogSource.decode(data, 0, data.length)).isEqualTo("ok" + (char) 0xFFFD);
        ended.countDown();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Starts a receiver on a free loopback port and waits until it listens. */
    private int start(Protocol protocol) throws Exception {
        int port;
        if (protocol == Protocol.TCP) {
            try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
                port = probe.getLocalPort();
            }
        } else {
            try (DatagramSocket probe = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
                port = probe.getLocalPort();
            }
        }
        source = new NetworkLogSource(protocol, port, Listen.LOCAL);
        CountDownLatch connected = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                source.open(lines::add, connected::countDown);
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                ended.countDown();
            }
        });
        t.setDaemon(true);
        t.start();
        assertThat(connected.await(5, TimeUnit.SECONDS)).as("listening; failure: %s", failure.get()).isTrue();
        return port;
    }

    private static Socket connect(int port) throws IOException {
        return new Socket(InetAddress.getByName("127.0.0.1"), port);
    }

    private static void send(Socket s, String text) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(bytes(text));
        out.flush();
    }

    private String next() throws InterruptedException {
        String line = lines.poll(5, TimeUnit.SECONDS);
        assertThat(line).as("a line arrives").isNotNull();
        return line;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> frames(String stream) throws IOException {
        NetworkLogSource.FrameReader reader = new NetworkLogSource.FrameReader(new ByteArrayInputStream(bytes(stream)));
        List<String> out = new ArrayList<>();
        String m;
        while ((m = reader.next()) != null) out.add(m);
        return out;
    }
}
