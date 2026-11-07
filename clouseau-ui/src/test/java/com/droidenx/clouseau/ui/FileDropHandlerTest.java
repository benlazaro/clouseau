package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Lightweight components only, so this runs without a display. */
class FileDropHandlerTest {

    private final List<List<Path>> opened = new ArrayList<>();

    @Test
    void regularFilesKeepsFilesInOrderAndSkipsFoldersAndMissing(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.log"), "x");
        Path b = Files.writeString(dir.resolve("b.log.gz"), "x");
        Path sub = Files.createDirectory(dir.resolve("sub"));
        List<Path> files = FileDropHandler.regularFiles(List.of(
                b.toFile(), sub.toFile(), new File(dir.toFile(), "missing.log"), a.toFile()));
        assertThat(files).containsExactly(b, a);
    }

    @Test
    void wrapsEveryComponentThatHasATransferHandlerOnce() {
        JPanel root = new JPanel();
        JPanel inner = new JPanel();
        JTable table = new JTable(1, 1);
        JTextField field = new JTextField();
        JLabel label = new JLabel();
        inner.add(table);
        root.add(inner);
        root.add(field);
        root.add(label);

        java.util.function.Consumer<List<Path>> opener = opened::add;   // one per window, as install passes it
        FileDropHandler.wrapTree(root, opener);
        TransferHandler tableHandler = table.getTransferHandler();
        FileDropHandler.wrapTree(root, opener);

        assertThat(tableHandler).isInstanceOf(FileDropHandler.class);
        assertThat(table.getTransferHandler()).isSameAs(tableHandler);   // not wrapped twice
        assertThat(field.getTransferHandler()).isInstanceOf(FileDropHandler.class);
        assertThat(label.getTransferHandler()).isNull();
        assertThat(root.getTransferHandler()).isNull();
    }

    @Test
    void aTabMovedToAnotherWindowOpensDropsThere() throws Exception {
        JTable table = new JTable(new Object[][]{{"hello"}}, new Object[]{"c"});
        List<List<Path>> secondWindow = new ArrayList<>();
        java.util.function.Consumer<List<Path>> first = opened::add;
        java.util.function.Consumer<List<Path>> second = secondWindow::add;
        FileDropHandler.wrapTree(table, first);
        FileDropHandler.wrapTree(table, second);   // what installing on the new window does

        TransferHandler handler = table.getTransferHandler();
        assertThat(handler).isInstanceOf(FileDropHandler.class);
        assertThat(((FileDropHandler) handler).opener()).isSameAs(second);

        // The table's own handler is still the one underneath, not a wrapped wrapper: copy works.
        table.setRowSelectionInterval(0, 0);
        table.setColumnSelectionInterval(0, 0);
        Clipboard clipboard = new Clipboard("test");
        handler.exportToClipboard(table, clipboard, TransferHandler.COPY);
        assertThat(clipboard.getData(DataFlavor.stringFlavor)).isEqualTo("hello");
    }

    @Test
    void copyFromAWrappedTableStillWorks() throws Exception {
        JTable table = new JTable(new Object[][]{{"hello"}}, new Object[]{"c"});
        table.setRowSelectionInterval(0, 0);
        FileDropHandler.wrapTree(table, opened::add);

        Clipboard clip = new Clipboard("test");
        table.getTransferHandler().exportToClipboard(table, clip, TransferHandler.COPY);

        assertThat(clip.getData(DataFlavor.stringFlavor)).asString().contains("hello");
    }

    @Test
    void pasteIntoAWrappedTextFieldStillWorks() {
        JTextField field = new JTextField();
        FileDropHandler.wrapTree(field, opened::add);

        boolean ok = field.getTransferHandler().importData(
                new TransferHandler.TransferSupport(field, new StringSelection("pasted")));

        assertThat(ok).isTrue();
        assertThat(field.getText()).isEqualTo("pasted");
    }

    @Test
    void pastingFilesIsNotTreatedAsADrop(@TempDir Path dir) throws IOException {
        File f = Files.writeString(dir.resolve("a.log"), "x").toFile();
        JTable table = new JTable(1, 1);
        FileDropHandler.wrapTree(table, opened::add);

        TransferHandler.TransferSupport paste = new TransferHandler.TransferSupport(table, fileList(f));

        assertThat(table.getTransferHandler().canImport(paste)).isFalse();
        assertThat(table.getTransferHandler().importData(paste)).isFalse();
        assertThat(opened).isEmpty();
    }

    private static Transferable fileList(File... files) {
        return new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{DataFlavor.javaFileListFlavor};
            }
            @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.javaFileListFlavor.equals(flavor);
            }
            @Override public Object getTransferData(DataFlavor flavor) {
                return List.of(files);
            }
        };
    }
}
