package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSource;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.SourceSetting;
import com.droidenx.clouseau.core.SourceSettings;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The form for a plugin {@link LogSourceProvider}: one field per {@link SourceSetting},
 * plus the parser to use. On Open it checks the values, asks the provider to create the
 * source, and remembers the values for next time (passwords encrypted).
 */
@Slf4j
final class SourceSettingsDialog extends JDialog {

    /**
     * The created source, the parser the user picked (empty = auto-detect), the checked
     * values it was created from, so Reconnect can create a new source the same way, and
     * those values without secrets, which a session keeps (D-19).
     */
    record Result(LogSource source, Optional<LogParser> parser, Map<String, String> values,
                  Map<String, String> sessionValues) {}

    private final LogSourceProvider provider;
    private final String pluginName;

    /**
     * A source that couldn't be created, naming the plugin when it's known, so it's clear
     * whose problem it is.
     */
    static String failed(String pluginName, String message) {
        return pluginName != null
                ? MessageFormat.format(Messages.get("source.dialog.create.failed.plugin"), pluginName, message)
                : MessageFormat.format(Messages.get("source.dialog.create.failed"), message);
    }
    private final List<SourceSetting> settings;
    private final Map<String, JComponent> fields = new LinkedHashMap<>();
    private final List<LogParser> parsers;
    private final JComboBox<String> parserCombo;
    private final NoticeLabel notice = new NoticeLabel();
    private final JButton openBtn = new JButton(Messages.get("source.dialog.open"));
    private Result result;

    /** @param pluginName the plugin the source comes from, named when it fails; null if unknown */
    SourceSettingsDialog(Frame owner, LogSourceProvider provider, List<SourceSetting> settings,
                         List<LogParser> parsers, String pluginName) {
        super(owner, MessageFormat.format(Messages.get("source.dialog.title"), provider.getName()), true);
        this.pluginName = pluginName;
        this.provider = provider;
        this.settings = settings;
        this.parsers  = parsers;

        String[] parserNames = new String[parsers.size() + 1];
        parserNames[0] = Messages.get("filechooser.parser.autodetect");
        for (int i = 0; i < parsers.size(); i++) parserNames[i + 1] = parsers.get(i).getName();
        parserCombo = new JComboBox<>(parserNames);

        setContentPane(buildContent());
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setMinimumSize(new Dimension(Math.max(getWidth(), 420), getHeight()));
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    Optional<Result> showDialog() {
        setVisible(true);
        return Optional.ofNullable(result);
    }

    private String providerId() {
        return provider.getClass().getName();
    }

    private Set<String> secretKeys() {
        return settings.stream()
                .filter(s -> s.type() == SourceSetting.Type.PASSWORD)
                .map(SourceSetting::key)
                .collect(Collectors.toSet());
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    /**
     * Laid out like Settings: the required settings under a section title, the optional ones
     * and the parser under another, labels indented; then the notice saying what Open waits
     * for, and a footer below a divider, Cancel before Open.
     */
    private JPanel buildContent() {
        JPanel panel = new JPanel(new MigLayout("insets 20 20 12 20, wrap 2, gapy 8, gapx 16", "[][grow, fill]"));
        Map<String, String> saved = AppPrefs.getSourceSettings(providerId(), secretKeys());

        for (SourceSetting s : settings) {
            fields.put(s.key(), buildField(s, saved.getOrDefault(s.key(), s.defaultValue())));
        }
        List<SourceSetting> required = settings.stream().filter(SourceSetting::required).toList();
        List<SourceSetting> optional = settings.stream().filter(s -> !s.required()).toList();
        if (!required.isEmpty()) {
            panel.add(SettingsDialog.sectionLabel(Messages.get("source.dialog.section.required")), "span 2, growx, gapbottom 4");
            required.forEach(s -> addRow(panel, s));
        }
        panel.add(SettingsDialog.sectionLabel(Messages.get("source.dialog.section.optional")),
                "span 2, growx, gapbottom 4" + (required.isEmpty() ? "" : ", gaptop 16"));
        optional.forEach(s -> addRow(panel, s));
        panel.add(new JLabel(Messages.get("ssh.dialog.parser")), SettingsDialog.INDENT);
        panel.add(parserCombo);

        // What Open waits for, or why it failed. Keeps its height when empty.
        panel.add(notice, "span 2, growx, wmin 0, gaptop 12");

        openBtn.addActionListener(e -> onOpen());
        JButton cancelBtn = new JButton(Messages.get("ssh.dialog.cancel"));
        cancelBtn.addActionListener(e -> dispose());
        JPanel footer = DialogFooter.of(cancelBtn, openBtn);   // Cancel before the default button
        getRootPane().setDefaultButton(openBtn);

        JPanel root = new JPanel(new BorderLayout());
        root.add(panel, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        updateOpen();
        return root;
    }

    private void addRow(JPanel panel, SourceSetting s) {
        JComponent field = fields.get(s.key());
        panel.add(new JLabel(s.label() + ":"), SettingsDialog.INDENT);
        panel.add(field);
        // Typing clears the outline an earlier Open left, and says what's still needed.
        Runnable edited = () -> {
            field.putClientProperty("JComponent.outline", null);
            notice.setText(" ");
            updateOpen();
        };
        if (field instanceof JTextComponent tc) {
            tc.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e)  { edited.run(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e)  { edited.run(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { edited.run(); }
            });
        } else if (field instanceof JComboBox<?> cb) {
            cb.addActionListener(e -> edited.run());
        }
    }

    /**
     * Open is enabled once every required setting has a value; until then the notice says
     * which are still needed.
     */
    private void updateOpen() {
        List<String> missing = SourceSettings.check(settings, enteredValues()).issues().stream()
                .filter(i -> i.problem() == SourceSettings.Problem.MISSING)
                .map(SourceSettings.Issue::label)
                .toList();
        openBtn.setEnabled(missing.isEmpty());
        if (!missing.isEmpty()) {
            notice.setText(MessageFormat.format(Messages.get("source.dialog.missing"), String.join(", ", missing)));
            notice.setToolTipText(null);
        } else if (notice.kind() == NoticeLabel.Kind.NOTE) {
            notice.setToolTipText(null);
            notice.setText(" ");
        }
    }

    private static JComponent buildField(SourceSetting s, String initial) {
        return switch (s.type()) {
            case PASSWORD -> {
                JPasswordField f = new JPasswordField(24);
                if (initial != null) f.setText(initial);
                yield f;
            }
            case CHOICE -> {
                JComboBox<String> c = new JComboBox<>(s.choices().toArray(String[]::new));
                if (initial != null && s.choices().contains(initial)) c.setSelectedItem(initial);
                yield c;
            }
            default -> {   // TEXT, NUMBER
                JTextField f = new JTextField(24);
                if (initial != null) f.setText(initial);
                yield f;
            }
        };
    }

    private Map<String, String> enteredValues() {
        Map<String, String> values = new HashMap<>();
        fields.forEach((key, field) -> {
            String value;
            if (field instanceof JPasswordField pf) value = new String(pf.getPassword());
            else if (field instanceof JTextComponent tc) value = tc.getText();
            else if (field instanceof JComboBox<?> cb) value = cb.getSelectedItem() == null ? "" : cb.getSelectedItem().toString();
            else value = "";
            values.put(key, value);
        });
        return values;
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private void onOpen() {
        SourceSettings.Result checked = SourceSettings.check(settings, enteredValues());
        if (!checked.ok()) {
            // Each offending field outlined, the problems in the notice, the first field focused.
            checked.issues().forEach(i -> {
                JComponent f = fields.get(i.key());
                if (f != null) f.putClientProperty("JComponent.outline", "error");
            });
            showError(checked.issues().stream().map(SourceSettingsDialog::describe).collect(Collectors.joining(" ")));
            JComponent first = fields.get(checked.issues().get(0).key());
            if (first != null) first.requestFocusInWindow();
            return;
        }

        LogSource source;
        try {
            source = provider.create(checked.values());
            if (source == null) throw new IllegalStateException("create() returned null");
        } catch (IllegalArgumentException e) {
            // The plugin's own words about the values: they're meant for the user.
            showError(e.getMessage() != null ? e.getMessage() : e.toString());
            return;
        } catch (Exception | LinkageError e) {
            log.error("Source plugin '{}' failed to create a source", provider.getName(), e);
            showError(failed(pluginName, e.getMessage() != null ? e.getMessage() : e.toString()));
            return;
        }

        AppPrefs.saveSourceSettings(providerId(), checked.values(), secretKeys());
        int idx = parserCombo.getSelectedIndex();
        Map<String, String> kept = new LinkedHashMap<>(checked.values());
        kept.keySet().removeAll(secretKeys());
        result = new Result(source, idx > 0 ? Optional.of(parsers.get(idx - 1)) : Optional.empty(),
                java.util.Collections.unmodifiableMap(new HashMap<>(checked.values())), kept);
        dispose();
    }

    private static String describe(SourceSettings.Issue issue) {
        String key = switch (issue.problem()) {
            case MISSING      -> "source.dialog.error.missing";
            case NOT_A_NUMBER -> "source.dialog.error.number";
            case NOT_A_CHOICE -> "source.dialog.error.choice";
        };
        return MessageFormat.format(Messages.get(key), issue.label());
    }

    /**
     * Shows why Open failed in the notice, on one line (the whole of it in the tooltip). The
     * notice keeps its place, so the dialog doesn't change size.
     */
    private void showError(String text) {
        String line = text.replaceAll("\\s*\\R\\s*", " ").strip();
        notice.show(line, NoticeLabel.Kind.ERROR);
        notice.setToolTipText(line);
    }
}
