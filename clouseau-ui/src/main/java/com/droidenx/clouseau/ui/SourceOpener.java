package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.JetBrainsIde;
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
import java.util.concurrent.ExecutionException;

/**
 * Open in IDE: opens a stack frame's file at its line, or a logger's class, in the editor
 * chosen in Settings → Source Code. A JetBrains IDE finds the file itself, in its open
 * projects ({@link JetBrainsIde}); VS Code and Cursor are given the full path, found under the
 * user's source folders ({@link SourceFolders}) and opened through their {@code vscode://} and
 * {@code cursor://} links.
 *
 * <p>The work runs off the EDT. Success needs no word, since the editor comes to the front; a
 * file no project has gets a toast over {@code area}, and a setup problem (no IDE running, its
 * plugin missing, no source folders) a message saying what to do.
 */
@Slf4j
final class SourceOpener {

    private SourceOpener() {}

    /** What an attempt came to, for the EDT to show. */
    private enum Outcome { OPENED, NOT_FOUND, NO_PLUGIN, NO_IDE, NO_FOLDERS, FAILED }

    /** Opens the first of {@code candidates} the editor has (a logger may be a .java or a .kt). */
    static void open(JComponent area, Toast toast, List<SourceRef> candidates) {
        if (candidates.isEmpty()) return;
        AppPrefs.SourceEditor editor = AppPrefs.getSourceEditor();
        List<String> folders = AppPrefs.getSourceFolders();
        SourceRef first = candidates.get(0);
        new SwingWorker<Outcome, Void>() {
            @Override
            protected Outcome doInBackground() {
                return editor == AppPrefs.SourceEditor.JETBRAINS
                        ? switch (new JetBrainsIde().open(candidates)) {
                            case OPENED -> Outcome.OPENED;
                            case NOT_FOUND -> Outcome.NOT_FOUND;
                            case NO_PLUGIN -> Outcome.NO_PLUGIN;
                            case NO_IDE -> Outcome.NO_IDE;
                        }
                        : openByPath(editor, folders, candidates);
            }

            @Override
            protected void done() {
                Outcome outcome;
                try {
                    outcome = get();
                } catch (InterruptedException | ExecutionException e) {
                    log.warn("Open in IDE failed for {}", first.path(), e);
                    outcome = Outcome.FAILED;
                }
                report(area, toast, editor, first, outcome);
            }
        }.execute();
    }

    /** VS Code or Cursor: find the file under the source folders and open its link. Blocks. */
    private static Outcome openByPath(AppPrefs.SourceEditor editor, List<String> folders, List<SourceRef> candidates) {
        if (folders.isEmpty()) return Outcome.NO_FOLDERS;
        List<Path> roots = folders.stream().map(Path::of).toList();
        for (SourceRef ref : candidates) {
            Optional<Path> file = SourceFolders.find(roots, ref.path());
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

    private static void report(JComponent area, Toast toast, AppPrefs.SourceEditor editor, SourceRef ref, Outcome outcome) {
        String name = ref.label();
        switch (outcome) {
            case OPENED -> { }
            case NOT_FOUND -> toast.show(area, java.text.MessageFormat.format(Messages.get(
                    editor == AppPrefs.SourceEditor.JETBRAINS ? "source.open.not.found.ide" : "source.open.not.found.folders"),
                    name));
            case NO_PLUGIN -> message(area, "source.open.no.plugin");
            case NO_IDE -> message(area, "source.open.no.ide");
            case NO_FOLDERS -> message(area, "source.open.no.folders");
            case FAILED -> message(area, "source.open.failed");
        }
    }

    private static void message(JComponent area, String key) {
        JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(area), Messages.get(key + ".message"),
                Messages.get(key + ".title"), JOptionPane.INFORMATION_MESSAGE);
    }
}
