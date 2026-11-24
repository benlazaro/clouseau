package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.ConnectingLogSource;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Receives log lines sent to a local port (UC-20, D-30): over TCP, one message per line or
 * syslog's octet-counted frames (RFC 6587), from several senders at once; or over UDP, one
 * message per datagram. It reads text only, as UTF-8 with bad bytes replaced, and never
 * deserializes objects. A message that holds several lines is passed on line by line, so
 * the lines after the first are continuation lines, as in a file.
 *
 * <p>It listens only while {@link #open} runs, on this computer's loopback addresses unless
 * {@link Listen#ALL} is asked for: it is connected once the port is bound, and closing it
 * closes the port and every sender's connection. Senders beyond {@link #MAX_SENDERS} are
 * turned away, and a line longer than {@link #MAX_LINE_BYTES} is cut there, so a sender
 * can't take unbounded memory or threads.
 */
@Slf4j
public final class NetworkLogSource implements ConnectingLogSource {

    public enum Protocol { TCP, UDP }

    /** Which addresses to listen on. */
    public enum Listen {
        /** 127.0.0.1 and ::1: only programs on this computer can send. */
        LOCAL,
        /** Every network interface: other computers, devices and VMs can send too. */
        ALL
    }

    /** Longest line kept, in bytes; the rest of a longer one is dropped. */
    public static final int MAX_LINE_BYTES = 1 << 20;
    /** Most TCP senders connected at once; more are closed as they connect. */
    public static final int MAX_SENDERS = 64;
    /** Largest UDP datagram. */
    private static final int MAX_DATAGRAM = 65_535;

    /** The port is taken by another program (or another Clouseau tab). */
    public static final class PortInUseException extends IOException {
        private final Protocol protocol;
        private final int port;

        public PortInUseException(Protocol protocol, int port, Throwable cause) {
            super(protocol + " port " + port + " is already in use", cause);
            this.protocol = protocol;
            this.port = port;
        }

        public Protocol protocol() { return protocol; }

        public int port() { return port; }
    }

    private final Protocol protocol;
    private final int port;
    private final Listen listen;
    /** Every listening socket: a ServerSocket or a DatagramSocket per address. */
    private final List<Closeable> sockets = new CopyOnWriteArrayList<>();
    private final Set<Socket> senders = ConcurrentHashMap.newKeySet();
    /** Counted down by {@link #close}, or by a listener that fails, to end {@link #open}. */
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile IOException failure;
    private volatile boolean closed;

    public NetworkLogSource(Protocol protocol, int port, Listen listen) {
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Port must be 1 to 65535: " + port);
        this.protocol = protocol;
        this.port = port;
        this.listen = listen;
    }

    public Protocol protocol() { return protocol; }

    public int port() { return port; }

    public Listen listen() { return listen; }

    /** "TCP :5140", or "TCP *:5140" when listening on every interface. */
    @Override
    public String getName() {
        return protocol + (listen == Listen.ALL ? " *:" : " :") + port;
    }

    /**
     * Binds the port, runs {@code onConnected}, then passes each line received to
     * {@code lines} until {@link #close} is called. Lines from different senders are passed
     * one at a time, each whole.
     *
     * <p>{@link Listen#LOCAL} binds 127.0.0.1 and, where the system has IPv6, ::1, since a
     * sender told "localhost" may use either (Windows usually tries ::1 first); only
     * 127.0.0.1 has to succeed. {@link Listen#ALL} binds the wildcard address, which takes
     * IPv4 and IPv6.
     *
     * @throws PortInUseException if the port is taken
     * @throws IOException        if it can't be bound, or listening fails
     */
    @Override
    public void open(Consumer<String> lines, Runnable onConnected) throws IOException {
        Consumer<String> serial = line -> {
            synchronized (this) {
                if (!closed) lines.accept(line);
            }
        };
        try {
            if (listen == Listen.ALL) {
                bind(new InetSocketAddress(port));
            } else {
                bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port));
                try {
                    bind(new InetSocketAddress(InetAddress.getByName("::1"), port));
                } catch (IOException e) {
                    log.info("Not listening on ::1 port {}: {}", port, e.toString());
                }
            }
        } catch (IOException e) {
            close();
            throw e;
        }
        if (closed) {
            close();
            return;
        }
        onConnected.run();
        for (Closeable s : sockets) {
            Thread t = new Thread(() -> listen(s, serial), "log-listener-" + protocol + "-" + port);
            t.setDaemon(true);
            t.start();
        }
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
        IOException f = failure;
        if (f != null) throw f;
    }

    private void bind(InetSocketAddress address) throws IOException {
        Closeable socket = null;
        try {
            if (protocol == Protocol.TCP) {
                ServerSocket s = new ServerSocket();
                socket = s;
                s.setReuseAddress(false);
                s.bind(address, 50);
            } else {
                DatagramSocket s = new DatagramSocket(null);
                socket = s;
                s.setReuseAddress(false);
                s.bind(address);
            }
        } catch (IOException e) {
            closeQuietly(socket);
            if (e instanceof BindException b && !isPermission(b)) throw new PortInUseException(protocol, port, e);
            throw e;
        }
        sockets.add(socket);
        log.info("Listening for logs on {} {}", protocol, address);
    }

    /** A bind refused for lack of rights (a port below 1024 on Linux or macOS), not because it's taken. */
    private static boolean isPermission(BindException e) {
        String m = e.getMessage();
        return m != null && (m.contains("Permission denied") || m.contains("Access is denied"));
    }

    /** Runs one socket's accept or receive loop until the source is closed. */
    private void listen(Closeable socket, Consumer<String> lines) {
        try {
            if (socket instanceof ServerSocket s) acceptLoop(s, lines);
            else receiveLoop((DatagramSocket) socket, lines);
        } catch (IOException e) {
            if (!closed) {
                log.warn("Listening on {} port {} failed", protocol, port, e);
                failure = e;
            }
        } finally {
            stopped.countDown();
        }
    }

    private void acceptLoop(ServerSocket s, Consumer<String> lines) throws IOException {
        while (!closed) {
            Socket client;
            try {
                client = s.accept();
            } catch (SocketException e) {
                if (closed) return;
                throw e;
            }
            if (senders.size() >= MAX_SENDERS) {
                log.warn("Turning away {}: {} senders already connected", client.getRemoteSocketAddress(), MAX_SENDERS);
                closeQuietly(client);
                continue;
            }
            senders.add(client);
            if (closed) {
                closeQuietly(client);
                return;
            }
            Thread t = new Thread(() -> readSender(client, lines), "log-sender-" + client.getPort());
            t.setDaemon(true);
            t.start();
        }
    }

    private void readSender(Socket client, Consumer<String> lines) {
        log.info("Log sender connected from {}", client.getRemoteSocketAddress());
        try (client; InputStream in = new BufferedInputStream(client.getInputStream())) {
            FrameReader frames = new FrameReader(in);
            String message;
            while (!closed && (message = frames.next()) != null) passLines(message, lines);
        } catch (IOException e) {
            if (!closed) log.info("Log sender {} dropped: {}", client.getRemoteSocketAddress(), e.toString());
        } finally {
            senders.remove(client);
            log.info("Log sender {} disconnected", client.getRemoteSocketAddress());
        }
    }

    private void receiveLoop(DatagramSocket s, Consumer<String> lines) throws IOException {
        byte[] buffer = new byte[MAX_DATAGRAM];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                s.receive(packet);
            } catch (SocketException e) {
                if (closed) return;
                throw e;
            }
            passLines(decode(packet.getData(), packet.getOffset(), packet.getLength()), lines);
        }
    }

    /** Passes each line of {@code message} on, without its CR or trailing NULs, skipping empty ones. */
    static void passLines(String message, Consumer<String> lines) {
        int start = 0;
        int length = message.length();
        while (start < length) {
            int nl = message.indexOf('\n', start);
            if (nl < 0) nl = length;
            int end = nl;
            while (end > start && (message.charAt(end - 1) == '\r' || message.charAt(end - 1) == '\0')) end--;
            if (end > start) lines.accept(message.substring(start, end));
            start = nl + 1;
        }
    }

    /** Closes the port and every sender's connection, making {@link #open} return. */
    @Override
    public void close() {
        closed = true;
        for (Closeable s : sockets) closeQuietly(s);
        for (Socket s : senders) closeQuietly(s);
        stopped.countDown();
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    /** UTF-8 with bad bytes replaced and a leading BOM dropped. */
    static String decode(byte[] bytes, int offset, int length) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        String s;
        try {
            s = decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException e) {
            s = new String(bytes, offset, length, StandardCharsets.UTF_8);   // can't happen with REPLACE
        }
        return !s.isEmpty() && s.charAt(0) == 0xFEFF ? s.substring(1) : s;
    }

    /**
     * Splits a TCP stream into messages: a line ended by LF (a CR before it is dropped), or
     * an RFC 6587 octet-counted frame ({@code 57 <34>1 …}), recognized by digits, a space
     * and a {@code <} at the start of a message. Lines longer than {@link #MAX_LINE_BYTES}
     * are cut there and the rest of the line skipped.
     */
    static final class FrameReader {
        private final InputStream in;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream(256);

        FrameReader(InputStream in) {
            this.in = in.markSupported() ? in : new BufferedInputStream(in);
        }

        /** The next message, or null at the end of the stream. */
        String next() throws IOException {
            int length = octetCount();
            if (length >= 0) {
                byte[] frame = in.readNBytes(Math.min(length, MAX_LINE_BYTES));
                if (length > MAX_LINE_BYTES) in.skipNBytes(length - MAX_LINE_BYTES);
                return decode(frame, 0, frame.length);
            }
            line.reset();
            boolean cut = false;
            int b;
            while ((b = in.read()) >= 0 && b != '\n') {
                if (line.size() < MAX_LINE_BYTES) line.write(b);
                else cut = true;
            }
            if (b < 0 && line.size() == 0) return null;
            if (cut) log.warn("Cut a received line at {} bytes", MAX_LINE_BYTES);
            byte[] bytes = line.toByteArray();
            int n = bytes.length;
            if (n > 0 && bytes[n - 1] == '\r') n--;
            return decode(bytes, 0, n);
        }

        /**
         * The length of an octet-counted frame that starts here, consumed with its space;
         * else -1, with nothing consumed. A frame is a count without leading zeros, a space
         * and a syslog priority ({@code <0>} to {@code <191>}), so an ordinary line such as
         * {@code 12 <b>bold</b>} is read as a line.
         */
        private int octetCount() throws IOException {
            in.mark(20);
            int length = 0;
            int digits = 0;
            int b;
            while ((b = in.read()) >= '0' && b <= '9' && digits <= 9) {
                if (digits == 0 && b == '0') digits = 10;   // a leading zero: not a count
                else digits++;
                length = length * 10 + (b - '0');
            }
            boolean frame = digits > 0 && digits <= 9 && b == ' ' && in.read() == '<';
            if (frame) {
                int pri = 0;
                int priDigits = 0;
                while ((b = in.read()) >= '0' && b <= '9' && priDigits < 3) {
                    pri = pri * 10 + (b - '0');
                    priDigits++;
                }
                frame = priDigits > 0 && b == '>' && pri <= 191;
            }
            in.reset();
            if (!frame) return -1;
            in.skipNBytes(digits + 1);
            return length;
        }
    }
}
