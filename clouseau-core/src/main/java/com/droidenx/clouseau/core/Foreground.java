package com.droidenx.clouseau.core;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings another program's window to the front, for Open in IDE: a JetBrains IDE that opens a
 * file from a project it doesn't have open does so in the background, without asking for the
 * foreground, so Clouseau brings it forward itself.
 *
 * <p>The program is found by the local port it listens on ({@link #listeningProcess}), then
 * raised with what each system offers, by a short helper process (Clouseau is in front when
 * the user clicks, and a process it starts may hand the foreground on):
 * <ul>
 *   <li><b>Windows</b>: PowerShell, giving the process's main window the foreground through
 *       user32's {@code SetForegroundWindow} (about 0.7 s).</li>
 *   <li><b>macOS</b>: {@code open -a} with the app bundle the process runs from, which
 *       activates the running app.</li>
 *   <li><b>Linux</b>: {@code wmctrl}, or else {@code xdotool}, when installed, on X11 (or
 *       XWayland). Wayland desktops don't let one app raise another's window, so there it
 *       does nothing.</li>
 * </ul>
 *
 * <p>Best effort: every step can fail (a tool missing, the window manager refusing), and each
 * says so by returning false or empty. Blocking: call off the Event Dispatch Thread.
 */
@Slf4j
public final class Foreground {

    /** How long a helper may take before it's abandoned. */
    private static final long TIMEOUT_SECONDS = 5;

    enum Os { WINDOWS, MAC, LINUX, OTHER }

    private Foreground() {}

    static Os os() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) return Os.WINDOWS;
        if (name.startsWith("mac")) return Os.MAC;
        if (name.contains("linux") || name.contains("bsd")) return Os.LINUX;
        return Os.OTHER;
    }

    /** The process listening on local TCP {@code port}, if the system can tell. Blocks. */
    public static OptionalLong listeningProcess(int port) {
        return switch (os()) {
            case WINDOWS -> run(List.of("netstat", "-ano", "-p", "TCP"))
                    .map(out -> parseNetstat(out, port)).orElse(OptionalLong.empty());
            case MAC, LINUX -> run(List.of("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t"))
                    .map(Foreground::parseLsof).orElse(OptionalLong.empty());
            case OTHER -> OptionalLong.empty();
        };
    }

    /** Brings process {@code pid}'s window to the front; whether a helper did it. Blocks. */
    public static boolean bringToFront(long pid) {
        return switch (os()) {
            case WINDOWS -> run(windowsCommand(pid)).isPresent();
            case MAC -> macBundle(ProcessHandle.of(pid).flatMap(p -> p.info().command()).orElse(null))
                    .map(app -> run(List.of("open", "-a", app)).isPresent())
                    .orElse(false);
            case LINUX -> linux(pid);
            case OTHER -> false;
        };
    }

    /**
     * PowerShell giving process {@code pid}'s main window the foreground with user32's
     * {@code SetForegroundWindow}, restoring it first if it's minimized. ({@code WScript.Shell}'s
     * {@code AppActivate} finds no window for a JetBrains IDE's process id.) The script goes
     * Base64-encoded, so its quotes survive the command line.
     */
    static List<String> windowsCommand(long pid) {
        return List.of("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                Base64.getEncoder().encodeToString(windowsScript(pid).getBytes(StandardCharsets.UTF_16LE)));
    }

    static String windowsScript(long pid) {
        return """
                $h = (Get-Process -Id %d -ErrorAction Stop).MainWindowHandle
                if ($h -eq 0) { exit 2 }
                Add-Type -Namespace ClouseauForeground -Name User32 -MemberDefinition '[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h); [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c); [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);'
                if ([ClouseauForeground.User32]::IsIconic($h)) { [void][ClouseauForeground.User32]::ShowWindow($h, 9) }
                if (-not [ClouseauForeground.User32]::SetForegroundWindow($h)) { exit 1 }
                """.formatted(pid);
    }

    /** {@code /Applications/IntelliJ IDEA.app} from a command inside it. */
    static Optional<String> macBundle(String command) {
        if (command == null) return Optional.empty();
        int app = command.indexOf(".app/");
        return app < 0 ? Optional.empty() : Optional.of(command.substring(0, app + 4));
    }

    private static boolean linux(long pid) {
        if (onPath("wmctrl")) {
            Optional<String> window = run(List.of("wmctrl", "-lp")).flatMap(out -> wmctrlWindow(out, pid));
            if (window.isPresent() && run(List.of("wmctrl", "-i", "-a", window.get())).isPresent()) return true;
        }
        if (onPath("xdotool")) {
            Optional<String> window = run(List.of("xdotool", "search", "--onlyvisible", "--pid", String.valueOf(pid)))
                    .flatMap(Foreground::lastLine);
            if (window.isPresent()) return run(List.of("xdotool", "windowactivate", window.get())).isPresent();
        }
        return false;
    }

    private static final Pattern NETSTAT = Pattern.compile(
            "^\\s*TCP\\s+(?:127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1?])\\s*:(\\d+)\\s+\\S+\\s+LISTENING\\s+(\\d+)\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** The PID listening on {@code port} in {@code netstat -ano} output. */
    static OptionalLong parseNetstat(String output, int port) {
        Matcher m = NETSTAT.matcher(output);
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) == port) return OptionalLong.of(Long.parseLong(m.group(2)));
        }
        return OptionalLong.empty();
    }

    /** The first PID {@code lsof -t} printed. */
    static OptionalLong parseLsof(String output) {
        for (String line : output.split("\\R")) {
            String s = line.strip();
            if (s.matches("\\d+")) return OptionalLong.of(Long.parseLong(s));
        }
        return OptionalLong.empty();
    }

    /** The id of a window of {@code pid} in {@code wmctrl -lp} output ({@code 0x04400003  0 1234 host Title}). */
    static Optional<String> wmctrlWindow(String output, long pid) {
        for (String line : output.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length >= 3 && parts[2].equals(String.valueOf(pid))) return Optional.of(parts[0]);
        }
        return Optional.empty();
    }

    private static Optional<String> lastLine(String output) {
        List<String> lines = new ArrayList<>(output.lines().map(String::strip).filter(s -> !s.isEmpty()).toList());
        return lines.isEmpty() ? Optional.empty() : Optional.of(lines.get(lines.size() - 1));
    }

    private static boolean onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, tool))) return true;
        }
        return false;
    }

    /** Runs {@code command}: its output when it exits 0 in time, else empty. */
    private static Optional<String> run(List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            log.debug("Couldn't run {}", command.get(0), e);
            return Optional.empty();
        }
        try {
            process.getOutputStream().close();
            // Read while it runs, so a full pipe can't stall it, on another thread, so a helper
            // that hangs can't hold this one past the timeout.
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try (InputStream out = process.getInputStream()) {
                    return out.readAllBytes();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                log.debug("{} exited with {}", command.get(0), process.exitValue());
                return Optional.empty();
            }
            return Optional.of(new String(output.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } catch (IOException | ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            return Optional.empty();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
