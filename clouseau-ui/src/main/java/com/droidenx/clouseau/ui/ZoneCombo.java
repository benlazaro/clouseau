package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.ui.SettingsDialog.TimezoneEntry;
import com.droidenx.clouseau.ui.theme.ClouseauColors;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.List;

/**
 * A timezone drop-down you can type in to search: "chic" lists America/Chicago. This
 * computer's zone and UTC come first, then the zones used recently, then every other zone Java
 * knows by name (GMT, EST, Etc/* and SystemV/* too), each with its offset from UTC
 * ("America/Chicago (UTC−6)").
 * <p>
 * Read the choice with {@link #selectedZone()}; action listeners hear only a zone chosen
 * (from the list, or Enter on what was typed), never the typing itself. Leaving the field
 * with text that names no zone puts the last choice back.
 */
final class ZoneCombo extends JComboBox<TimezoneEntry> {

    /** Zones shown first: this computer's, UTC, then the most recently used. */
    private static final int RECENT = 5;

    private final List<TimezoneEntry> all;
    private final Model model;
    private final JTextField editor;
    private TimezoneEntry chosen;
    /** True while the list is being swapped, so the swap isn't taken for a choice or typing. */
    private boolean quiet;

    ZoneCombo(ZoneId current) {
        all = entries();
        model = new Model(all);
        quiet = true;
        setModel(model);
        setEditable(true);
        setMaximumRowCount(14);
        setPrototypeDisplayValue(new TimezoneEntry(ZoneId.of("UTC"), "America/Argentina/Buenos_Aires (UTC−3)", false));
        setRenderer(new Renderer());
        editor = (JTextField) getEditor().getEditorComponent();
        editor.putClientProperty("JTextField.placeholderText", Messages.get("timezone.search.placeholder"));
        chosen = find(current != null ? current : ZoneId.systemDefault());
        setSelectedItem(chosen);
        quiet = false;

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { typed(); }
            @Override public void removeUpdate(DocumentEvent e)  { typed(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        editor.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { SwingUtilities.invokeLater(editor::selectAll); }
            @Override public void focusLost(FocusEvent e) {
                // Only a move to another field or button counts: the list's own window can
                // take the focus for a moment on some systems while it opens.
                if (e.isTemporary() || e.getOppositeComponent() == null) return;
                TimezoneEntry match = match(editor.getText());
                if (match != null && !match.equals(chosen)) {
                    showAll(match);
                    choose(match);
                } else {
                    showAll(chosen);
                }
            }
        });
    }

    /** The zone chosen; never null. */
    ZoneId selectedZone() {
        return chosen.zone();
    }

    /** Shows {@code zone} as chosen, without telling the listeners. */
    void setZone(ZoneId zone) {
        chosen = find(zone);
        showAll(chosen);
    }

    // ── Choosing and searching ──────────────────────────────────────────────

    /** A choice from the list or Enter: becomes {@link #chosen}; typing never fires. */
    @Override protected void fireActionEvent() {
        if (quiet) return;
        Object item = super.getSelectedItem();
        TimezoneEntry entry = item instanceof TimezoneEntry t ? t : match(String.valueOf(item));
        if (entry == null) return;
        boolean list = model.getSize() != all.size();
        boolean changed = !entry.equals(chosen);
        chosen = entry;
        if (list || !(item instanceof TimezoneEntry)) SwingUtilities.invokeLater(() -> showAll(chosen));
        if (changed) super.fireActionEvent();
    }

    /** The text changed: list the zones that contain it. */
    private void typed() {
        if (quiet || !editor.isFocusOwner()) return;
        SwingUtilities.invokeLater(() -> {
            String text = editor.getText();
            if (all.stream().anyMatch(e -> e.label().equals(text))) return;   // arrowing through the list
            List<TimezoneEntry> hits = text.isBlank() ? all : hits(text);
            quiet = true;
            // The typed text stays the "selection", so the editor keeps it as the list changes.
            model.show(hits, text);
            quiet = false;
            if (isShowing()) {
                // Closed and opened again (once this event is done) so it sizes to the new list.
                hidePopup();
                if (!hits.isEmpty()) SwingUtilities.invokeLater(() -> {
                    if (!isShowing()) return;
                    showPopup();
                    // A popup window that was just made can miss its first paint; paint it again.
                    javax.swing.Timer again = new javax.swing.Timer(40, e -> {
                        if (getUI().getAccessibleChild(this, 0) instanceof JComponent popup) popup.repaint();
                    });
                    again.setRepeats(false);
                    again.start();
                });
            }
        });
    }

    /** The whole list again, with {@code entry} selected. */
    private void showAll(TimezoneEntry entry) {
        quiet = true;
        model.show(all, entry);
        editor.setText(entry.label());
        quiet = false;
    }

    private void choose(TimezoneEntry entry) {
        chosen = entry;
        super.fireActionEvent();
    }

    /** What {@code text} names: a label, the first zone containing it, or a zone ID. */
    private TimezoneEntry match(String text) {
        if (text == null || text.isBlank()) return null;
        for (TimezoneEntry e : all) if (e.label().equalsIgnoreCase(text.trim())) return e;
        List<TimezoneEntry> hits = hits(text);
        if (!hits.isEmpty()) return hits.get(0);
        try {
            return find(ZoneId.of(text.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The zones whose name contains {@code text}, those with a place starting with it first
     * ("tok": Asia/Tokyo before Antarctica/Vostok), the rest in list order.
     */
    private List<TimezoneEntry> hits(String text) {
        String q = norm(text.trim());
        List<TimezoneEntry> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (TimezoneEntry e : all) {
            String id = norm(e.zone().getId());
            if (!norm(e.label()).contains(q)) continue;
            boolean placeStarts = id.startsWith(q) || java.util.Arrays.stream(id.split("/")).anyMatch(p -> p.startsWith(q));
            (placeStarts ? starts : contains).add(e);
        }
        starts.addAll(contains);
        return starts;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private TimezoneEntry find(ZoneId zone) {
        for (TimezoneEntry e : all) if (e.zone().equals(zone)) return e;
        return entry(zone, false);
    }

    // ── The list ────────────────────────────────────────────────────────────

    /** This computer's zone, UTC and recent ones first (the last of them ends the group), then the rest. */
    private static List<TimezoneEntry> entries() {
        ZoneId system = ZoneId.systemDefault();
        LinkedHashSet<ZoneId> first = new LinkedHashSet<>();
        first.add(system);
        first.add(ZoneId.of("UTC"));
        for (ZoneId z : AppPrefs.getRecentTimezones()) {
            if (first.size() >= 2 + RECENT) break;
            first.add(z);
        }
        List<TimezoneEntry> list = new ArrayList<>();
        int i = 0;
        for (ZoneId z : first) {
            boolean last = ++i == first.size();
            list.add(z.equals(system)
                    ? new TimezoneEntry(z, java.text.MessageFormat.format(Messages.get("timezone.this.computer"), label(z)), last)
                    : entry(z, last));
        }
        Set<String> firstIds = new HashSet<>();
        first.forEach(z -> firstIds.add(z.getId()));
        ZoneId.getAvailableZoneIds().stream()
                .filter(id -> !firstIds.contains(id))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(id -> list.add(entry(ZoneId.of(id), false)));
        return List.copyOf(list);
    }

    private static TimezoneEntry entry(ZoneId zone, boolean groupEnd) {
        return new TimezoneEntry(zone, label(zone), groupEnd);
    }

    /** "America/Chicago (UTC−6)", the offset as of now. */
    static String label(ZoneId zone) {
        return zone.getId().equals("UTC") ? "UTC" : zone.getId() + " (" + offset(zone) + ")";
    }

    /** "UTC−6", "UTC+5:30", "UTC" as of now. */
    static String offset(ZoneId zone) {
        int s = zone.getRules().getOffset(Instant.now()).getTotalSeconds();
        if (s == 0) return "UTC";
        int h = Math.abs(s) / 3600, m = Math.abs(s) % 3600 / 60;
        return "UTC" + (s < 0 ? "−" : "+") + h + (m != 0 ? String.format(":%02d", m) : "");
    }

    /** The zones listed: all of them, or those matching what was typed. */
    private static final class Model extends AbstractListModel<TimezoneEntry> implements ComboBoxModel<TimezoneEntry> {
        private List<TimezoneEntry> shown;
        private Object selected;

        Model(List<TimezoneEntry> all) { shown = all; }

        void show(List<TimezoneEntry> list, Object select) {
            int before = shown.size();
            shown = list;
            selected = select;
            fireContentsChanged(this, 0, Math.max(0, Math.max(before, list.size()) - 1));
        }

        @Override public int getSize() { return shown.size(); }
        @Override public TimezoneEntry getElementAt(int i) { return shown.get(i); }
        @Override public Object getSelectedItem() { return selected; }
        @Override public void setSelectedItem(Object item) {
            if (Objects.equals(item, selected)) return;
            selected = item;
            fireContentsChanged(this, -1, -1);
        }
    }

    /** A line under the last of the zones listed first, while the whole list shows. */
    private final class Renderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            boolean line = index >= 0 && value instanceof TimezoneEntry t && t.groupEnd()
                    && model.getSize() == all.size();
            setBorder(line
                    ? BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 0, 1, 0, ClouseauColors.separatorColor()),
                            BorderFactory.createEmptyBorder(2, 6, 3, 6))
                    : BorderFactory.createEmptyBorder(2, 6, 2, 6));
            return this;
        }
    }
}
