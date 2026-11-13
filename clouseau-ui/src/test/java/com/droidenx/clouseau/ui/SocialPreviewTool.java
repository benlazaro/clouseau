package com.droidenx.clouseau.ui;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Dev-only: draws the repository's social preview (the picture GitHub shows when the repo is
 * linked), 1280x640: the app icon, name and tagline on the left, and a screenshot as a window
 * running off the right edge, on the Dark theme's grays with the logo's amber as the accent.
 * Not part of the app. Run it with {@code ./gradlew :clouseau-ui:socialPreview}; it draws
 * off-screen, so it doesn't need the screen to itself.
 *
 * <p>Arguments: the screenshot to show ({@code docs/screenshots/main-view.png}) and the PNG to
 * write ({@code docs/social-preview.png}). The icon and the Inter fonts come from the
 * classpath, as the app loads them. Upload the result in the repository's Settings → General
 * → Social preview.
 */
public final class SocialPreviewTool {

    static final int W = 1280, H = 640;
    static final String NAME = "Clouseau";
    /** The README's pitch, broken where it reads well at this size. */
    static final String[] TAGLINE = {
            "A fast, modern desktop", "log viewer for engineers", "who investigate logs", "for a living." };
    static final String FOOTNOTE = "Free & open source  ·  Windows  ·  macOS  ·  Linux";

    private static final Color AMBER = new Color(0xE0A243);

    public static void main(String[] args) throws Exception {
        BufferedImage shot = ImageIO.read(new File(args[0]));
        File out = new File(args[1]).getAbsoluteFile();
        BufferedImage img = render(shot);
        ImageIO.write(img, "png", out);
        System.err.println("Saved " + out + " (" + img.getWidth() + "x" + img.getHeight() + ", "
                + out.length() / 1024 + " KB)");
    }

    static BufferedImage render(BufferedImage shot) throws Exception {
        Font bold = font("Inter-Bold.otf");
        Font regular = font("Inter-Regular.otf");
        Font semi = font("Inter-SemiBold.otf");
        BufferedImage icon = ImageIO.read(resource("/com/droidenx/clouseau/ui/icons/app-256.png"));

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Background: the theme's window gray, a little lighter at the top left, and a soft
        // amber glow behind the screenshot.
        g.setPaint(new GradientPaint(0, 0, new Color(0x2A2B2F), W, H, new Color(0x18191B)));
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(900, 330), 520, new float[] { 0f, 1f },
                new Color[] { new Color(224, 162, 67, 46), new Color(224, 162, 67, 0) }));
        g.fillRect(0, 0, W, H);

        // The screenshot as a window, running off the right and bottom edges.
        int sx = 560, sy = 110, sw = 860;
        int sh = Math.round(shot.getHeight() * (sw / (float) shot.getWidth()));
        Shape frame = new RoundRectangle2D.Float(sx, sy, sw, sh, 18, 18);
        for (int i = 14; i > 0; i--) {
            g.setColor(new Color(0, 0, 0, 10));
            g.fill(new RoundRectangle2D.Float(sx - i + 6, sy - i + 14, sw + 2 * i, sh + 2 * i, 18 + i, 18 + i));
        }
        Shape clip = g.getClip();
        g.clip(frame);
        g.drawImage(scaled(shot, sw, sh), sx, sy, null);
        g.setClip(clip);
        g.setColor(new Color(255, 255, 255, 40));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(frame);

        // Left column: icon, name with an amber rule, tagline, platforms.
        int x = 72;
        g.drawImage(scaled(icon, 104, 104), x - 8, 112, null);
        g.setFont(bold.deriveFont(76f));
        g.setColor(new Color(0xF2F4F8));
        g.drawString(NAME, x, 300);
        g.setColor(AMBER);
        g.fill(new RoundRectangle2D.Float(x + 2, 324, 64, 5, 5, 5));
        g.setFont(regular.deriveFont(27f));
        g.setColor(new Color(0xC9CED6));
        int y = 378;
        for (String line : TAGLINE) {
            g.drawString(line, x, y);
            y += 38;
        }
        g.setFont(semi.deriveFont(18f));
        g.setColor(new Color(0x8B919B));
        g.drawString(FOOTNOTE, x, 572);
        g.dispose();
        return img;
    }

    private static Font font(String file) throws Exception {
        try (InputStream in = resource("/com/formdev/flatlaf/fonts/inter/" + file)) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        }
    }

    private static InputStream resource(String path) throws IOException {
        InputStream in = SocialPreviewTool.class.getResourceAsStream(path);
        if (in == null) throw new IOException("not on the classpath: " + path);
        return in;
    }

    /** Scales down in halving steps, so a large screenshot stays sharp. */
    static BufferedImage scaled(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        int cw = src.getWidth(), ch = src.getHeight();
        do {
            boolean last = cur.getWidth() / 2 < w;
            cw = last ? w : Math.max(w, cw / 2);
            ch = last ? h : Math.max(h, ch / 2);
            BufferedImage next = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(cur, 0, 0, cw, ch, null);
            g.dispose();
            cur = next;
        } while (cw != w || ch != h);
        return cur;
    }
}
