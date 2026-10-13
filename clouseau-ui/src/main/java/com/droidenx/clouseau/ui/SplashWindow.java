package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;

@Slf4j
public final class SplashWindow extends JWindow {

    private static final int    SVG_W          = 680;
    private static final int    SVG_H          = 300;
    private static final int    PAD            = 24;
    private static final int    STATUS_H       = 32;
    // Background is resolved at paint time so it picks up the active theme.
    // Falls back to the Clouseau Dark palette value when shown before theme init.
    private static final Color  BG_FALLBACK    = new Color(0x2c2c2c);
    private static final long   MIN_DISPLAY_MS = 500;

    private final long   shownAt = System.currentTimeMillis();
    private final JLabel statusLabel;

    private SplashWindow(BufferedImage img) {
        JPanel panel = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Color splash = ClouseauColors.splashBackground();
                g2.setColor(splash != Color.GRAY ? splash : BG_FALLBACK);
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (img != null) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.drawImage(img, PAD, PAD, SVG_W, SVG_H, null);
                }
                g2.dispose();
            }
        };

        int winW = SVG_W + PAD * 2;
        int winH = SVG_H + PAD * 2 + STATUS_H;
        panel.setPreferredSize(new Dimension(winW, winH));

        int statusY = SVG_H + PAD * 2;
        Font statusFont = UIManager.getFont("defaultFont") != null
                ? UIManager.getFont("defaultFont").deriveFont(Font.PLAIN, 11f)
                : new JLabel().getFont().deriveFont(Font.PLAIN, 11f);
        Color statusColor = ClouseauColors.mutedForeground();

        statusLabel = new JLabel("Initializing application...", SwingConstants.LEFT);
        statusLabel.setForeground(statusColor);
        statusLabel.setFont(statusFont);
        statusLabel.setOpaque(false);
        statusLabel.setBounds(PAD, statusY, winW / 2, STATUS_H);
        panel.add(statusLabel);

        JLabel versionLabel = new JLabel("v" + AppPrefs.getAppVersion(), SwingConstants.RIGHT);
        versionLabel.setForeground(statusColor);
        versionLabel.setFont(statusFont);
        versionLabel.setOpaque(false);
        versionLabel.setBounds(winW / 2, statusY, winW / 2 - PAD, STATUS_H);
        panel.add(versionLabel);

        setContentPane(panel);
        pack();
        setLocationRelativeTo(null);
    }

    /** Updates the status label. Safe to call from any thread. */
    public void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    /**
     * Decodes the splash image on the calling thread (safe to call from the main thread),
     * then creates and shows the window on the EDT.  Returns {@code null} if the splash
     * cannot be shown.
     */
    public static SplashWindow createAndShow() {
        BufferedImage img = loadSplashImage();
        SplashWindow[] ref = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                ref[0] = new SplashWindow(img);
                ref[0].setVisible(true);
            });
        } catch (Exception e) {
            log.warn("Could not show splash screen", e);
        }
        return ref[0];
    }

    /**
     * Hides and disposes the window, but waits until at least {@code MIN_DISPLAY_MS}
     * have elapsed since the splash was shown, then runs {@code onClosed}.
     * Must be called on the EDT.
     */
    public void close(Runnable onClosed) {
        long remaining = MIN_DISPLAY_MS - (System.currentTimeMillis() - shownAt);
        if (remaining <= 0) {
            setVisible(false);
            dispose();
            if (onClosed != null) onClosed.run();
        } else {
            Timer t = new Timer((int) remaining, e -> {
                setVisible(false);
                dispose();
                if (onClosed != null) onClosed.run();
            });
            t.setRepeats(false);
            t.start();
        }
    }

    // ── Splash image ──────────────────────────────────────────────────────────

    /** Loads the 2x PNG that the {@code generateSplashPng} Gradle task renders from the SVG. */
    private static BufferedImage loadSplashImage() {
        try (InputStream in = SplashWindow.class.getResourceAsStream(
                "/com/droidenx/clouseau/ui/images/clouseau_splash.png")) {
            if (in == null) {
                log.warn("Splash image resource not found");
                return null;
            }
            return ImageIO.read(in);
        } catch (Exception e) {
            log.warn("Could not load splash image", e);
            return null;
        }
    }
}
