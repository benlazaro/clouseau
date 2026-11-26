package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginManagerDialogTest {

    /**
     * The example plugin installed, with a (made-up) 1.1.0 on its repository: its row has a green
     * arrow left of the trash can's spot, painted without hovering, whose tooltip says what it does.
     */
    @Test
    void aPluginWithAnUpdateHasAGreenArrowLeftOfTheTrashCan(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path plugins = java.nio.file.Files.createDirectories(dir.resolve("plugins"));
        java.nio.file.Path jar = java.nio.file.Path.of(System.getProperty("clouseau.examplePlugin.jar"));
        java.nio.file.Path installed = java.nio.file.Files.copy(jar, plugins.resolve(jar.getFileName()));
        var pluginManager = new com.droidenx.clouseau.runtime.ClouseauPluginManager(plugins);
        pluginManager.loadAll();
        // Its own empty folder, so the check the dialog starts has nothing to ask a repository about.
        PluginUpdates updates = new PluginUpdates(java.nio.file.Files.createDirectories(dir.resolve("none")));

        var dialog = new java.util.concurrent.atomic.AtomicReference<PluginManagerDialog>();
        javax.swing.SwingUtilities.invokeAndWait(() -> dialog.set(new PluginManagerDialog(null, pluginManager, updates, () -> {})));
        try {
            var checking = PluginUpdates.class.getDeclaredField("checking");
            checking.setAccessible(true);
            for (int i = 0; i < 200 && (boolean) checking.get(updates); i++) Thread.sleep(25);

            var asset = new PluginRepository.Asset("com.droidenx.clouseau", "logfmt-clouseau-plugin", "1.1.0", "u", -1, null);
            var image = new java.util.concurrent.atomic.AtomicReference<java.awt.image.BufferedImage>();
            var tooltip = new java.util.concurrent.atomic.AtomicReference<String>();
            var arrow = new java.util.concurrent.atomic.AtomicReference<java.awt.Rectangle>();
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                updates.set(java.util.List.of(new PluginUpdates.Update(asset, "1.0.0", installed, "Test")));
                dialog.get().pack();
                javax.swing.JList<?> list = list(dialog.get());
                assertThat(list.getModel().getSize()).isEqualTo(1);
                java.awt.Rectangle row = list.getCellBounds(0, 0);
                arrow.set(new java.awt.Rectangle(list.getWidth() - PluginManagerDialog.TRASH - PluginManagerDialog.UPDATE,
                        row.y, PluginManagerDialog.UPDATE, row.height));
                var img = new java.awt.image.BufferedImage(list.getWidth(), list.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                list.paint(g);
                g.dispose();
                image.set(img);
                java.awt.Rectangle a = arrow.get();
                tooltip.set(list.getToolTipText(new java.awt.event.MouseEvent(list, java.awt.event.MouseEvent.MOUSE_MOVED,
                        0, 0, a.x + a.width / 2, a.y + a.height / 2, 0, false)));
            });

            java.awt.Color green = com.droidenx.clouseau.ui.theme.ClouseauColors.statusOk();
            assertThat(greenPixels(image.get(), arrow.get(), green)).as("green pixels on the arrow's spot").isGreaterThan(5);
            assertThat(tooltip.get()).isEqualTo("Update to 1.1.0 (you have 1.0.0)");
        } finally {
            javax.swing.SwingUtilities.invokeAndWait(() -> dialog.get().dispose());
            pluginManager.stopAll();
        }
    }

    private static javax.swing.JList<?> list(PluginManagerDialog dialog) {
        try {
            var field = PluginManagerDialog.class.getDeclaredField("list");
            field.setAccessible(true);
            return (javax.swing.JList<?>) field.get(dialog);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Pixels in {@code area} close to {@code color} (antialiasing blends the edges). */
    private static int greenPixels(java.awt.image.BufferedImage img, java.awt.Rectangle area, java.awt.Color color) {
        int n = 0;
        for (int x = area.x; x < area.x + area.width; x++) {
            for (int y = area.y; y < area.y + area.height; y++) {
                java.awt.Color c = new java.awt.Color(img.getRGB(x, y));
                if (Math.abs(c.getRed() - color.getRed()) < 40 && Math.abs(c.getGreen() - color.getGreen()) < 40
                        && Math.abs(c.getBlue() - color.getBlue()) < 40) n++;
            }
        }
        return n;
    }

    @Test
    void anUpdateSaysTheNewVersionAndTheOldOne() {
        assertThat(PluginManagerDialog.installedToast("Loki", "1.2.0", false, true, "1.1.0"))
                .isEqualTo("Updated Loki from 1.1.0 to 1.2.0");
    }

    @Test
    void goingToAnOlderVersionSaysDowngraded() {
        assertThat(PluginManagerDialog.installedToast("Loki", "1.1.0", false, true, "1.2.0"))
                .isEqualTo("Downgraded Loki from 1.2.0 to 1.1.0");
    }

    @Test
    void anUpdateFromAnUnknownVersionSaysOnlyTheNewOne() {
        assertThat(PluginManagerDialog.installedToast("Loki", "1.2.0", false, true, null))
                .isEqualTo("Updated Loki to 1.2.0");
    }

    @Test
    void anInstallSaysWhatAndWhichVersion() {
        assertThat(PluginManagerDialog.installedToast("Loki", "1.2.0", false, false, null))
                .isEqualTo("Installed Loki 1.2.0");
    }

    @Test
    void oneThatDidntLoadSaysSo() {
        assertThat(PluginManagerDialog.installedToast("loki.jar", "", true, true, "1.1.0"))
                .isEqualTo("loki.jar is installed but didn't load; its details say why");
    }
}
