package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.theme.ClouseauColors;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.awt.dnd.DragSource;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Drags one tab the way IntelliJ does. Past the drag threshold the tab lifts out: a copy of
 * it follows the mouse and its own header goes blank, leaving an empty slot where it will
 * land. Near the tab strip the copy slides along the strip, and a neighbour swaps with it
 * once the copy's edge passes the neighbour's middle. Held at either end of the strip, it
 * moves on past the tabs scrolled out of sight. Further away it floats free, and on release
 * the tab goes to another window or a new one. Esc puts it back where it started.
 */
@Slf4j
final class TabDragHandler extends MouseAdapter {

    /** Client property set on a tab header while it stands in for the dragged tab. */
    static final String PLACEHOLDER = "clouseau.tabDragPlaceholder";

    /** What the window does for a drag: reorder, drop, and the tab's right-click menu. */
    interface Host {
        void moveTab(int from, int to);
        /** Called after the release is handled, so the header isn't removed under it. */
        void dropTab(Point screen);
        void showContextMenu(MouseEvent e);
    }

    private static final float PINNED_OPACITY = 0.9f;
    private static final float FLOATING_OPACITY = 0.7f;
    /** How long a tab is held at an end of the strip before the strip scrolls, then how often. */
    private static final int EDGE_DELAY_MS = 300;
    private static final int EDGE_STEP_MS = 180;

    private final JTabbedPane tabs;
    private final JComponent header;
    private final Host host;

    /** Where the left button went down, or null when no press can turn into a drag. */
    private Point pressScreen;
    /** The press point from the tab's top-left corner. */
    private Point grab;
    private int startIndex = -1;
    private JWindow ghost;
    private boolean pinned;
    private KeyEventDispatcher escape;
    /** -1 or 1 while the tab is held past the left or right end of the visible strip, else 0. */
    private int edge;
    private final Timer edgeScroll = new Timer(EDGE_STEP_MS, e -> stepPastEdge());

    TabDragHandler(JTabbedPane tabs, JComponent header, Host host) {
        this.tabs = tabs;
        this.header = header;
        this.host = host;
        edgeScroll.setInitialDelay(EDGE_DELAY_MS);
    }

    /** Listens on the header and on any child that takes mouse events of its own. */
    void install(Component... parts) {
        for (Component c : parts) {
            c.addMouseListener(this);
            c.addMouseMotionListener(this);
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.isPopupTrigger()) { host.showContextMenu(e); return; }
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        int idx = tabs.indexOfTabComponent(header);
        if (idx < 0) return;
        tabs.setSelectedIndex(idx);
        Point p = SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), tabs);
        Rectangle tab = tabs.getBoundsAt(idx);
        grab = new Point(p.x - tab.x, p.y - tab.y);
        pressScreen = e.getLocationOnScreen();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (pressScreen == null) return;
        Point screen = e.getLocationOnScreen();
        if (ghost == null) {
            // A click that wobbles a pixel or two stays a click.
            if (screen.distance(pressScreen) < DragSource.getDragThreshold()) return;
            if (!start()) { pressScreen = null; return; }
        }
        int src = tabs.indexOfTabComponent(header);
        if (src < 0 || !tabs.isShowing()) return;

        Point origin = tabs.getLocationOnScreen();
        boolean near = nearStrip(screen, origin);
        if (near) {
            int wanted = screen.x - origin.x - grab.x;
            Rectangle area = visibleStrip();
            // Kept inside the visible part of the strip: tabs scrolled out of sight are
            // reached by holding the tab at the edge, not by passing their hidden middles.
            int left = Math.max(area.x, Math.min(wanted, area.x + area.width - ghost.getWidth()));
            int dst = slotFor(tabBounds(), src, left);
            if (dst != src) {
                host.moveTab(src, dst);
                src = dst;
            }
            Rectangle tab = tabs.getBoundsAt(src);
            ghost.setLocation(origin.x + left, origin.y + tab.y);
            setEdge(edge(wanted, ghost.getWidth(), area));
        } else {
            setEdge(0);
            ghost.setLocation(screen.x - grab.x, screen.y - grab.y);
        }
        if (near != pinned || !ghost.isVisible()) {
            pinned = near;
            setOpacity(pinned ? PINNED_OPACITY : FLOATING_OPACITY);
            if (!ghost.isVisible()) ghost.setVisible(true);
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (e.isPopupTrigger()) { host.showContextMenu(e); return; }
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        boolean dragged = ghost != null;
        Point screen = e.getLocationOnScreen();
        pressScreen = null;
        finish();
        if (dragged) SwingUtilities.invokeLater(() -> host.dropTab(screen));
    }

    /** Lifts the tab: the copy that follows the mouse, the empty slot, and Esc to cancel. */
    private boolean start() {
        startIndex = tabs.indexOfTabComponent(header);
        if (startIndex < 0) return false;
        ghost = createGhost(startIndex);   // before the header goes blank
        if (ghost == null) return false;
        pinned = true;
        header.putClientProperty(PLACEHOLDER, Boolean.TRUE);
        header.repaint();
        tabs.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        escape = ev -> {
            if (ev.getKeyCode() != KeyEvent.VK_ESCAPE) return false;
            if (ev.getID() == KeyEvent.KEY_PRESSED) cancel();
            return true;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escape);
        return true;
    }

    /** Esc: the tab goes back where it was, and the rest of this press does nothing. */
    private void cancel() {
        int current = tabs.indexOfTabComponent(header);
        pressScreen = null;
        finish();
        if (current >= 0 && startIndex >= 0 && current != startIndex) {
            host.moveTab(current, Math.min(startIndex, tabs.getTabCount() - 1));
        }
    }

    private void finish() {
        setEdge(0);
        if (ghost != null) {
            ghost.dispose();
            ghost = null;
        }
        if (escape != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escape);
            escape = null;
        }
        header.putClientProperty(PLACEHOLDER, null);
        header.repaint();
        tabs.setCursor(Cursor.getDefaultCursor());
    }

    /** Within the window's width, and no more than a tab's height above or below the strip. */
    private boolean nearStrip(Point screen, Point origin) {
        Rectangle strip = null;
        for (Rectangle r : tabBounds()) strip = strip == null ? new Rectangle(r) : strip.union(r);
        if (strip == null) return false;
        int x = screen.x - origin.x;
        int y = screen.y - origin.y;
        return x >= 0 && x < tabs.getWidth()
                && y >= strip.y - strip.height && y < strip.y + 2 * strip.height;
    }

    private List<Rectangle> tabBounds() {
        Rectangle[] bounds = new Rectangle[tabs.getTabCount()];
        for (int i = 0; i < bounds.length; i++) bounds[i] = tabs.getBoundsAt(i);
        return List.of(bounds);
    }

    /**
     * Where the dragged tab belongs when its left edge is at {@code left}: past a neighbour
     * once its right edge crosses that neighbour's middle (left edge, going left). Bounds
     * are as laid out now, with the dragged tab at {@code src}. Swapping keeps the answer
     * the same, so tabs of different widths don't flip back and forth.
     */
    static int slotFor(List<Rectangle> bounds, int src, int left) {
        Rectangle self = bounds.get(src);
        int dst = src;
        while (dst + 1 < bounds.size() && left + self.width > middle(bounds.get(dst + 1))) {
            dst++;
        }
        if (dst != src) return dst;
        while (dst > 0 && left < middle(bounds.get(dst - 1))) {
            dst--;
        }
        return dst;
    }

    /**
     * Which end of the visible strip {@code area} a tab {@code width} wide, wanted at
     * {@code left}, is pushed past: -1 the left, 1 the right, 0 neither.
     */
    static int edge(int left, int width, Rectangle area) {
        if (left < area.x) return -1;
        if (left + width > area.x + area.width) return 1;
        return 0;
    }

    /**
     * The part of the tab strip that shows tabs, in the tabbed pane's coordinates: the
     * scrolling viewport (tabs scroll in one row), or the whole pane if there is none.
     */
    private Rectangle visibleStrip() {
        for (Component c : tabs.getComponents()) {
            if (c instanceof JViewport v && v.isVisible()) return v.getBounds();
        }
        return new Rectangle(0, 0, tabs.getWidth(), tabs.getHeight());
    }

    private void setEdge(int edge) {
        if (edge == this.edge) return;
        this.edge = edge;
        if (edge == 0) edgeScroll.stop();
        else edgeScroll.restart();
    }

    /**
     * Held at an end of the strip: the tab moves one place that way. Selecting it scrolls
     * the strip to keep it in sight, which brings the next hidden tab into view.
     */
    private void stepPastEdge() {
        int src = tabs.indexOfTabComponent(header);
        int dst = src + edge;
        if (ghost == null || src < 0 || dst < 0 || dst >= tabs.getTabCount()) return;
        host.moveTab(src, dst);
    }

    private static int middle(Rectangle r) {
        return r.x + r.width / 2;
    }

    private void setOpacity(float opacity) {
        try {
            ghost.setOpacity(opacity);
        } catch (UnsupportedOperationException | IllegalComponentStateException e) {
            log.debug("Window opacity not supported: {}", e.getMessage());
        }
    }

    /** A picture of the selected tab: its background, underline and header. */
    private JWindow createGhost(int index) {
        Rectangle tab = tabs.getBoundsAt(index);
        if (tab.width <= 0 || tab.height <= 0 || header.getWidth() <= 0) return null;
        Rectangle h = SwingUtilities.convertRectangle(header.getParent(), header.getBounds(), tabs);

        // Drawn at the screen's pixel density (150% scaling means 1.5 pixels per point), so
        // the text is as sharp as the real tab rather than a 1x picture stretched to fit.
        GraphicsConfiguration gc = tabs.getGraphicsConfiguration();
        AffineTransform screen = gc != null ? gc.getDefaultTransform() : new AffineTransform();
        double sx = screen.getScaleX();
        double sy = screen.getScaleY();
        BufferedImage img = new BufferedImage((int) Math.ceil(tab.width * sx),
                (int) Math.ceil(tab.height * sy), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.scale(sx, sy);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            // Subpixel (LCD) text needs an opaque destination; grayscale is the sharp choice here.
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            int arc = UIManager.getInt("TabbedPane.tabArc");
            Shape shape = new RoundRectangle2D.Float(0.5f, 0.5f, tab.width - 1, tab.height - 1, arc, arc);
            g.setColor(color("TabbedPane.selectedBackground", ClouseauColors.tableBackground()));
            g.fill(shape);
            g.setColor(color("Component.borderColor", Color.GRAY));
            g.draw(shape);
            int underline = Math.max(1, UIManager.getInt("TabbedPane.underlineHeight"));
            g.setColor(color("TabbedPane.underlineColor", Color.ORANGE));
            g.fillRect(arc / 2, tab.height - underline, tab.width - arc, underline);

            Graphics2D hg = (Graphics2D) g.create(h.x - tab.x, h.y - tab.y, h.width, h.height);
            try {
                header.paint(hg);
            } finally {
                hg.dispose();
            }
        } finally {
            g.dispose();
        }

        JWindow window = new JWindow(SwingUtilities.getWindowAncestor(tabs));
        try {
            window.setBackground(new Color(0, 0, 0, 0));   // let the rounded corners show through
        } catch (UnsupportedOperationException | IllegalComponentStateException e) {
            log.debug("Per-pixel translucency not supported: {}", e.getMessage());
        }
        // Drawn into the window's size in points: on a scaled screen that maps the image's
        // pixels one to one onto the screen's.
        int w = tab.width;
        int ht = tab.height;
        JComponent content = new JComponent() {
            @Override protected void paintComponent(Graphics gr) {
                Graphics2D g2 = (Graphics2D) gr.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(img, 0, 0, w, ht, null);
                } finally {
                    g2.dispose();
                }
            }
        };
        window.setContentPane(content);
        window.setSize(tab.width, tab.height);
        return window;
    }

    private static Color color(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }
}
