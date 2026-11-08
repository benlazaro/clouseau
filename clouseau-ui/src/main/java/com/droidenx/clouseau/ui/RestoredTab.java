package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.Session;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.event.HierarchyEvent;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What a tab does for a session (D-19, G-9), whatever it shows: the message card that
 * stands in for rows (not loaded yet, file missing, password needed), the load held back
 * until the tab is first shown, and the strip that says which filters came back. EDT only.
 */
final class RestoredTab {

    private final JComponent tab;
    private final FilterBar filterBar;
    /** Shows the message card (true) or the rows (false) in the tab's center. */
    private final Consumer<Boolean> showMessageCard;

    /** The load the tab starts the first time it is shown, or null. */
    private Runnable deferredLoad;
    /** True while the tab shows a message instead of rows. */
    private boolean showingMessage;
    private final JPanel messagePanel;
    private final JLabel messageLabel = new JLabel();
    private final JButton messageButton = new JButton();
    private Runnable messageAction;
    private final JPanel noticeStrip;
    private final JLabel noticeLabel = new JLabel();
    private final JButton noticeClear = new JButton(Messages.get("session.notice.clear"));
    /** The filters a session restored, while the strip announcing them is up. */
    private Session.Filters restoredFilters;

    RestoredTab(JComponent tab, FilterBar filterBar, Consumer<Boolean> showMessageCard) {
        this.tab = tab;
        this.filterBar = filterBar;
        this.showMessageCard = showMessageCard;
        messagePanel = buildMessagePanel();
        noticeStrip = buildNoticeStrip();
        // A restored tab loads the first time it is shown (selected in its window).
        tab.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && tab.isShowing()) {
                SwingUtilities.invokeLater(this::startDeferredLoad);
            }
        });
    }

    /** The card shown instead of rows; the tab adds it to its center. */
    JComponent messagePanel() { return messagePanel; }

    /** The strip above the filter bar; the tab adds it. Hidden until there's something to say. */
    JComponent noticeStrip() { return noticeStrip; }

    /**
     * Holds the tab's load until it is first shown, so a large session opens quickly: only
     * each window's selected tab loads at once.
     */
    void deferLoad(Runnable load) {
        deferredLoad = load;
        showMessage(Messages.get("session.tab.pending"), null, null);
        if (tab.isShowing()) startDeferredLoad();
    }

    /** Whether the tab is waiting for its first showing before it loads. */
    boolean isDeferred() { return deferredLoad != null; }

    /**
     * Whether a session should keep the tab exactly as it was restored: it hasn't loaded
     * (not shown yet, or showing a message such as a missing file).
     */
    boolean keepsOrigin() { return deferredLoad != null || showingMessage; }

    private void startDeferredLoad() {
        Runnable load = deferredLoad;
        if (load == null) return;
        deferredLoad = null;
        hideMessage();
        load.run();
    }

    /**
     * Shows a message instead of rows, with a button that runs {@code action}, or none if
     * {@code buttonText} is null.
     */
    void showMessage(String text, String buttonText, Runnable action) {
        showingMessage = true;
        messageLabel.setText("<html><div style='text-align:center'>" + escape(text).replace("\n", "<br>") + "</div></html>");
        messageAction = action;
        messageButton.setText(buttonText == null ? "" : buttonText);
        messageButton.setVisible(buttonText != null);
        showMessageCard.accept(true);
    }

    /** Back to the rows: the tab is about to load or stream. */
    void hideMessage() {
        if (!showingMessage) return;
        showingMessage = false;
        showMessageCard.accept(false);
    }

    /**
     * Restores a session's filters and announces them in the strip, with {@code notes} (such
     * as a parser that no longer exists). {@code sessionName} names a named session, or is
     * null for the last one; {@code follow} is the tab's restored follow mode.
     */
    void restoreFilters(Session.Filters filters, List<String> notes, String sessionName, boolean follow) {
        List<String> lines = new ArrayList<>(notes);
        if (filters != null && !FilterBar.isDefault(filters)) {
            filterBar.restore(filters);
            restoredFilters = filterBar.state();
            lines.add(0, describeRestored(filters, sessionName, follow));
        }
        if (!lines.isEmpty()) showNotice(String.join(" ", lines), restoredFilters != null);
    }

    /** The user changed a filter: the strip about restored filters goes away. */
    void filtersChanged() {
        if (restoredFilters != null && noticeStrip.isVisible()
                && !Objects.equals(filterBar.state(), restoredFilters)) {
            hideNotice();
        }
    }

    /** A column layout as a session keeps it, or null if there's nothing to keep. */
    static Session.Layout toSession(AppPrefs.ColumnLayout layout) {
        if (layout == null || layout.columns().isEmpty()) return null;
        return new Session.Layout(layout.columns().stream()
                .map(c -> new Session.Column(c.modelIndex(), c.visible(), c.width())).toList());
    }

    /** A session's column layout, as the table view restores it. */
    static AppPrefs.ColumnLayout fromSession(Session.Layout layout) {
        return new AppPrefs.ColumnLayout(null, layout.columns().stream()
                .map(c -> new AppPrefs.ColumnLayout.ColumnEntry(c.model(), c.visible(), c.width())).toList());
    }

    private JPanel buildMessagePanel() {
        JPanel p = new JPanel(new MigLayout("fill, wrap 1", "[center]", "push[]12[]push"));
        ClouseauColors.themedForeground(messageLabel, ClouseauColors::dimForeground);
        messageButton.setVisible(false);
        messageButton.addActionListener(e -> { if (messageAction != null) messageAction.run(); });
        p.add(messageLabel, "wmax 520");
        p.add(messageButton, "hidemode 3");
        return p;
    }

    private JPanel buildNoticeStrip() {
        JPanel strip = new JPanel(new MigLayout("insets 4 10 4 6, gap 8", "[][grow][][]", "[center]"));
        ClouseauColors.themedBackground(strip, ClouseauColors::loadingBackground);   // like the plain-text banner
        noticeClear.addActionListener(e -> {
            hideNotice();
            filterBar.clear();
        });
        JButton close = new JButton("×");
        close.setToolTipText(Messages.get("session.notice.close"));
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.setFocusable(false);
        close.addActionListener(e -> hideNotice());
        strip.add(new JLabel("ⓘ"));
        strip.add(noticeLabel, "growx, wmin 0");
        strip.add(noticeClear, "hidemode 3");
        strip.add(close);
        strip.setVisible(false);
        return strip;
    }

    private void showNotice(String text, boolean offerClear) {
        noticeLabel.setText(text);
        noticeLabel.setToolTipText(text);
        noticeClear.setVisible(offerClear);
        noticeStrip.setVisible(true);
        tab.revalidate();
    }

    private void hideNotice() {
        restoredFilters = null;
        noticeStrip.setVisible(false);
        tab.revalidate();
    }

    /** "Filters restored from your last session: levels, 3 loggers, time range." and its warnings. */
    private static String describeRestored(Session.Filters f, String sessionName, boolean follow) {
        List<String> parts = new ArrayList<>();
        if (!FilterBar.isDefault(new Session.Filters(f.levels(), null, null, null, null, null, null))) parts.add(Messages.get("session.notice.levels"));
        if (!f.excludedLoggers().isEmpty()) parts.add(MessageFormat.format(Messages.get("session.notice.loggers"), f.excludedLoggers().size()));
        if (f.excludedFields().values().stream().anyMatch(v -> !v.isEmpty())) parts.add(Messages.get("session.notice.fields"));
        if (!f.from().isBlank() || !f.to().isBlank()) parts.add(Messages.get("session.notice.time"));
        if (!f.text().isEmpty()) parts.add(Messages.get("session.notice.text"));
        if (!f.hiddenSources().isEmpty()) parts.add(MessageFormat.format(Messages.get("session.notice.sources"), f.hiddenSources().size()));
        String what = String.join(", ", parts);
        String text = sessionName == null
                ? MessageFormat.format(Messages.get("session.notice.last"), what)
                : MessageFormat.format(Messages.get("session.notice.named"), what, sessionName);
        if (follow && !f.to().isBlank()) {
            text += " " + MessageFormat.format(Messages.get("session.notice.to.hides"), f.to());
        }
        return text;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
