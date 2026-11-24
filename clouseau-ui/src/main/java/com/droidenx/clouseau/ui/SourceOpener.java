package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.Foreground;
import com.droidenx.clouseau.core.JetBrainsIde;
import com.droidenx.clouseau.core.JetBrainsProjects;
import com.droidenx.clouseau.core.SourceFolders;
import com.droidenx.clouseau.core.SourceRefs.SourceRef;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;

/**
 * Open in IDE: opens a stack frame's file at its line, or a logger's class, in the editor
 * chosen in Settings → Source Code.
 *
 * <p>The file is looked up in the source folders first. A JetBrains IDE is given its full path
 * ({@link JetBrainsIde}), which it opens whether or not the project is open; a file the folders
 * don't have is sent by its path in the source tree, for the IDE to look up in its open
 * projects. VS Code and Cursor get the full path, through their {@code vscode://} and
 * {@code cursor://} links.
 *
 * <p>The source folders are those chosen in Settings, or, with none chosen, the usual places
 * people keep projects ({@link SourceFolders#usualRoots}). One index of them is shared, built in
 * the background when the detail pane first shows a stack frame ({@link #prepare}), and built
 * again when the folders change.
 *
 * <p>The work runs off the EDT. Success needs no word, since the editor comes to the front; a
 * file no project has gets a toast over {@code area}, and a setup problem (no IDE running, its
 * plugin missing, no source folders) a message saying what to do.
 */
@Slf4j
final class SourceOpener {

    private SourceOpener() {}

    /** What an attempt came to, for the EDT to show. */
    private enum Outcome { OPENED, ASKED, NOT_OPEN, NOT_FOUND, NO_PLUGIN, NO_IDE, NO_FOLDERS, FAILED }

    /**
     * An outcome; the IDE's name ("IntelliJ IDEA 2026.3"), if known; and for
     * {@link Outcome#NOT_OPEN}, the project to open and the file and line to open anyway.
     */
    private record Done(Outcome outcome, String ide, Path project, Path file, int line) {
        Done(Outcome outcome) { this(outcome, null, null, null, 0); }
        Done(Outcome outcome, String ide) { this(outcome, ide, null, null, 0); }
    }

    /** The shared index; replaced when the folders it searches change. Guarded by the class. */
    private static SourceFolders index;

    /** The folders searched: those chosen in Settings, or the usual ones that exist. */
    static List<Path> searchedFolders() {
        List<String> chosen = AppPrefs.getSourceFolders();
        return chosen.isEmpty()
                ? SourceFolders.usualRoots(Path.of(System.getProperty("user.home")))
                : chosen.stream().map(Path::of).toList();
    }

    /** The index of {@link #searchedFolders}, new if they changed since the last one. Blocks briefly. */
    private static synchronized SourceFolders index() {
        List<Path> roots = searchedFolders();
        if (index == null || !index.roots().equals(roots)) index = new SourceFolders(roots);
        return index;
    }

    /** The Settings folders the last {@link #prepare} was for, so it starts one walk per change. */
    private static volatile List<String> preparedFor;

    /** Builds the index in the background, if it isn't built yet. Call from any thread. */
    static void prepare() {
        List<String> chosen = AppPrefs.getSourceFolders();
        if (chosen.equals(preparedFor)) return;
        preparedFor = chosen;
        Thread t = new Thread(() -> index().prepare(), "source-index");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Opens the first of {@code candidates} the editor has (a logger may be a .java or a .kt). */
    static void open(JComponent area, Toast toast, List<SourceRef> candidates) {
        if (candidates.isEmpty()) return;
        AppPrefs.SourceEditor editor = AppPrefs.getSourceEditor();
        SourceRef first = candidates.get(0);
        new SwingWorker<Done, Void>() {
            @Override
            protected Done doInBackground() {
                return editor == AppPrefs.SourceEditor.JETBRAINS
                        ? openInJetBrains(candidates)
                        : new Done(openByPath(editor, candidates));
            }

            @Override
            protected void done() {
                Done result;
                try {
                    result = get();
                } catch (InterruptedException | ExecutionException e) {
                    log.warn("Open in IDE failed for {}", first.path(), e);
                    result = new Done(Outcome.FAILED);
                }
                report(area, toast, editor, first, result);
            }
        }.execute();
    }

    /**
     * A JetBrains IDE: by full path when the source folders have the file, which the IDE opens
     * whether or not its project is open; otherwise by the path in the source tree, for the IDE
     * to look up in its open projects. Blocks.
     *
     * <p>The IDE Remote Control plugin answers 200 to a relative path whether or not it found
     * the file, so a file asked for that way can't be known to have opened: it's
     * {@link Outcome#ASKED}, and gets a note. (IDEs before 2026.2 answered 404 when no project
     * had it, which still comes back as {@code NOT_FOUND}.)
     *
     * <p>A file whose project isn't open in the IDE would open with none of the IDE's
     * navigation, no better than in a text editor, so it isn't opened: {@link Outcome#NOT_OPEN}
     * says which project to open, and offers to open the file anyway. Whether it's open comes
     * from the IDE's own record ({@link JetBrainsProjects}); when that can't be read, the file
     * is opened.
     */
    private static Done openInJetBrains(List<SourceRef> candidates) {
        JetBrainsIde ide = new JetBrainsIde();
        SourceFolders folders = index();
        for (SourceRef ref : candidates) {
            Optional<Path> file = folders.find(ref.path());
            if (file.isEmpty()) continue;
            Optional<JetBrainsIde.About> about = ide.about();
            if (about.isPresent()) {
                JetBrainsIde.About a = about.get();
                Optional<JetBrainsProjects> projects = JetBrainsProjects.of(a.name(), a.product(), a.build());
                if (projects.isPresent()) {
                    Optional<JetBrainsProjects.Project> project = projects.get().projectOf(file.get());
                    if (project.isEmpty() || !project.get().open()) {
                        Path root = project.map(JetBrainsProjects.Project::root)
                                .orElseGet(() -> JetBrainsProjects.likelyRoot(file.get()));
                        return new Done(Outcome.NOT_OPEN, a.name(), root, file.get(), ref.line());
                    }
                }
            }
            return openFull(ide, file.get(), ref.line());
        }
        return new Done(outcome(ide.open(candidates), Outcome.ASKED));
    }

    /**
     * Opens {@code file} by its full path, then brings the IDE forward ({@link Foreground}): it
     * doesn't come to the front by itself for a file whose project isn't open. Blocks.
     */
    private static Done openFull(JetBrainsIde ide, Path file, int line) {
        String full = file.toAbsolutePath().toString().replace('\\', '/');
        Outcome outcome = outcome(ide.open(new SourceRef(full, line)), Outcome.OPENED);
        OptionalInt port = ide.openedPort();
        if (outcome != Outcome.OPENED || port.isEmpty()) return new Done(outcome);
        Foreground.listeningProcess(port.getAsInt()).ifPresent(pid -> {
            if (!Foreground.bringToFront(pid)) log.debug("Couldn't bring the IDE (pid {}) to the front", pid);
        });
        return new Done(outcome, ide.about(port.getAsInt()).map(JetBrainsIde.About::name).orElse(null));
    }

    private static Outcome outcome(JetBrainsIde.Result result, Outcome whenOpened) {
        return switch (result) {
            case OPENED -> whenOpened;
            case NOT_FOUND -> Outcome.NOT_FOUND;
            case NO_PLUGIN -> Outcome.NO_PLUGIN;
            case NO_IDE -> Outcome.NO_IDE;
        };
    }

    /** VS Code or Cursor: find the file under the source folders and open its link. Blocks. */
    private static Outcome openByPath(AppPrefs.SourceEditor editor, List<SourceRef> candidates) {
        SourceFolders folders = index();
        if (folders.roots().isEmpty()) return Outcome.NO_FOLDERS;
        for (SourceRef ref : candidates) {
            Optional<Path> file = folders.find(ref.path());
            if (file.isEmpty()) continue;
            try {
                Desktop.getDesktop().browse(editorLink(editor, file.get(), ref.line()));
                return Outcome.OPENED;
            } catch (Exception e) {
                log.warn("Couldn't open {} in {}", file.get(), editor, e);
                return Outcome.FAILED;
            }
        }
        return Outcome.NOT_FOUND;
    }

    /** {@code vscode://file/C:/src/Foo.java:42}: the editor opens the file at the line. */
    static URI editorLink(AppPrefs.SourceEditor editor, Path file, int line) throws URISyntaxException {
        String scheme = editor == AppPrefs.SourceEditor.CURSOR ? "cursor" : "vscode";
        String path = file.toAbsolutePath().toString().replace('\\', '/');
        if (!path.startsWith("/")) path = "/" + path;
        return new URI(scheme, "file", path + (line > 0 ? ":" + line : ""), null);
    }

    private static void report(JComponent area, Toast toast, AppPrefs.SourceEditor editor, SourceRef ref, Done result) {
        String name = ref.label();
        switch (result.outcome()) {
            // A JetBrains IDE may not have come to the front: say where the file went. The
            // other editors always come forward.
            case OPENED -> {
                if (editor == AppPrefs.SourceEditor.JETBRAINS) {
                    toast.show(area, java.text.MessageFormat.format(Messages.get("source.open.opened"), name,
                            result.ide() != null ? result.ide() : Messages.get("source.open.opened.ide")));
                }
            }
            case ASKED -> toast.show(area, java.text.MessageFormat.format(Messages.get("source.open.asked.ide"), name));
            case NOT_OPEN -> askToOpenProject(area, toast, ref, result);
            case NOT_FOUND -> toast.show(area, java.text.MessageFormat.format(Messages.get(
                    editor == AppPrefs.SourceEditor.JETBRAINS ? "source.open.not.found.ide" : "source.open.not.found.folders"),
                    name));
            case NO_PLUGIN -> message(area, "source.open.no.plugin");
            case NO_IDE -> message(area, "source.open.no.ide");
            case NO_FOLDERS -> message(area, "source.open.no.folders");
            case FAILED -> message(area, "source.open.failed");
        }
    }

    /**
     * The file's project isn't open in the IDE: says which to open, with OK the default, and
     * Open File Anyway for a quick look (or when the IDE's record of its projects is behind).
     */
    private static void askToOpenProject(JComponent area, Toast toast, SourceRef ref, Done result) {
        String ide = result.ide() != null ? result.ide() : Messages.get("source.open.opened.ide");
        Path project = result.project();
        String name = project.getFileName() != null ? project.getFileName().toString() : project.toString();
        String message = java.text.MessageFormat.format(Messages.get("source.open.not.open.message"),
                html(ref.label()), html(name), html(ide), html(project.toString()));
        String ok = Messages.get("source.open.not.open.ok"), anyway = Messages.get("source.open.not.open.anyway");
        int answer = JOptionPane.showOptionDialog(SwingUtilities.getWindowAncestor(area), message,
                Messages.get("source.open.not.open.title"), JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, new Object[] { anyway, ok }, ok);
        if (answer != 0) return;
        new SwingWorker<Done, Void>() {
            @Override
            protected Done doInBackground() {
                return openFull(new JetBrainsIde(), result.file(), result.line());
            }

            @Override
            protected void done() {
                Done opened;
                try {
                    opened = get();
                } catch (InterruptedException | ExecutionException e) {
                    log.warn("Open in IDE failed for {}", result.file(), e);
                    opened = new Done(Outcome.FAILED);
                }
                report(area, toast, AppPrefs.SourceEditor.JETBRAINS, ref, opened);
            }
        }.execute();
    }

    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void message(JComponent area, String key) {
        JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(area), Messages.get(key + ".message"),
                Messages.get(key + ".title"), JOptionPane.INFORMATION_MESSAGE);
    }
}
