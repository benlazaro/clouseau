package com.droidenx.clouseau.ui;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.AWTEventListener;
import java.awt.event.ContainerEvent;
import java.awt.event.InputEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Opens files dropped anywhere on the main window (UC-1).
 *
 * <p>Swing delivers a drop to the deepest component that has a drop target, and every
 * component with a {@link TransferHandler} has one: the log table, text fields and the
 * detail text area all get one from their UI and would reject a file list. So besides
 * the frame itself, each such component in the window gets this handler wrapped around
 * its own: a dropped file list opens the files, and anything else (dropping text, copy,
 * paste, dragging out) goes to the original handler unchanged.
 */
@Slf4j
final class FileDropHandler extends TransferHandler {

    private final Consumer<List<Path>> opener;
    private final TransferHandler delegate;

    private FileDropHandler(Consumer<List<Path>> opener, TransferHandler delegate) {
        this.opener = opener;
        this.delegate = delegate;
    }

    /**
     * Makes {@code frame} accept dropped files: on the frame itself, on every component in
     * it now, and on every component added to it later.
     */
    static void install(JFrame frame, Consumer<List<Path>> opener) {
        frame.setTransferHandler(new FileDropHandler(opener, null));
        wrapTree(frame.getRootPane(), opener);
        AWTEventListener onAdded = event -> {
            if (event instanceof ContainerEvent ce && ce.getID() == ContainerEvent.COMPONENT_ADDED
                    && SwingUtilities.getWindowAncestor(ce.getChild()) == frame) {
                wrapTree(ce.getChild(), opener);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(onAdded, AWTEvent.CONTAINER_EVENT_MASK);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(onAdded);
            }
        });
    }

    static void wrapTree(Component c, Consumer<List<Path>> opener) {
        if (c instanceof JComponent jc) {
            TransferHandler th = jc.getTransferHandler();
            if (th != null && !(th instanceof FileDropHandler)) {
                jc.setTransferHandler(new FileDropHandler(opener, th));
            }
        }
        if (c instanceof Container parent) {
            for (Component child : parent.getComponents()) wrapTree(child, opener);
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    @Override
    public boolean canImport(TransferSupport support) {
        if (isFileDrop(support)) {
            // Don't let the table move its selection to the row under the cursor.
            support.setShowDropLocation(false);
            if ((support.getSourceDropActions() & COPY) != 0) support.setDropAction(COPY);
            return true;
        }
        return delegate != null && delegate.canImport(support);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!isFileDrop(support)) return delegate != null && delegate.importData(support);
        List<Path> files;
        try {
            @SuppressWarnings("unchecked")
            List<File> dropped = (List<File>) support.getTransferable()
                    .getTransferData(DataFlavor.javaFileListFlavor);
            files = regularFiles(dropped);
        } catch (Exception e) {
            log.warn("Could not read dropped files", e);
            return false;
        }
        if (files.isEmpty()) return false;
        log.info("Opening {} dropped file(s)", files.size());
        // Later, so the drag source isn't held up by a dialog (e.g. the zip picker).
        SwingUtilities.invokeLater(() -> opener.accept(files));
        return true;
    }

    /** The dropped entries that are regular files, in order; folders are skipped. */
    static List<Path> regularFiles(List<File> dropped) {
        return dropped.stream().map(File::toPath).filter(Files::isRegularFile).toList();
    }

    private static boolean isFileDrop(TransferSupport support) {
        return support.isDrop() && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    // The old-style import API, for callers that still use it.

    @Override
    public boolean canImport(JComponent comp, DataFlavor[] flavors) {
        return delegate != null && delegate.canImport(comp, flavors);
    }

    @Override
    public boolean importData(JComponent comp, Transferable t) {
        return delegate != null && delegate.importData(comp, t);
    }

    // ── Export: entirely the original handler's ───────────────────────────────

    @Override
    public int getSourceActions(JComponent c) {
        return delegate != null ? delegate.getSourceActions(c) : NONE;
    }

    @Override
    public Icon getVisualRepresentation(Transferable t) {
        return delegate != null ? delegate.getVisualRepresentation(t) : null;
    }

    @Override
    public void exportAsDrag(JComponent comp, InputEvent e, int action) {
        if (delegate != null) delegate.exportAsDrag(comp, e, action);
    }

    @Override
    public void exportToClipboard(JComponent comp, Clipboard clip, int action) {
        if (delegate != null) delegate.exportToClipboard(comp, clip, action);
    }
}
