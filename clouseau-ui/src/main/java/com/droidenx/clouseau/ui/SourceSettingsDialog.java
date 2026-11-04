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

    /** The created source and the parser the user picked (empty = auto-detect). */
    record Result(LogSource source, Optional<LogParser> parser) {}

    private final LogSourceProvider provider;
    private final List<SourceSetting> settings;
    private final Map<String, JComponent> fields = new LinkedHashMap<>();
    private final List<LogParser> parsers;
    private final JComboBox<String> parserCombo;
    private final JLabel errorLabel = new JLabel(" ");
    private Result result;

    SourceSettingsDialog(Frame owner, LogSourceProvider provider, List<SourceSetting> settings,
                         List<LogParser> parsers) {
        super(owner, MessageFormat.format(Messages.get("source.dialog.title"), provider.getName()), true);
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

    private JPanel buildContent() {
        JPanel panel = new JPanel(new MigLayout("insets 20, wrap 2, gapy 8, gapx 10", "[right][grow, fill]"));
        Map<String, String> saved = AppPrefs.getSourceSettings(providerId(), secretKeys());

        for (SourceSetting s : settings) {
            String initial = saved.getOrDefault(s.key(), s.defaultValue());
            JComponent field = buildField(s, initial);
            fields.put(s.key(), field);
            panel.add(new JLabel(s.label() + (s.required() ? " *" : "") + ":"));
            panel.add(field);
        }
        panel.add(new JLabel(Messages.get("ssh.dialog.parser")));
        panel.add(parserCombo);

        errorLabel.setForeground(UIManager.getColor("Component.error.focusedBorderColor") != null
                ? UIManager.getColor("Component.error.focusedBorderColor") : Color.RED);
        panel.add(errorLabel, "span 2, growx, gaptop 4");

        panel.add(new JSeparator(), "span 2, growx, gaptop 4, gapbottom 4");
        JButton openBtn = new JButton(Messages.get("source.dialog.open"));
        openBtn.addActionListener(e -> onOpen());
        JButton cancelBtn = new JButton(Messages.get("ssh.dialog.cancel"));
        cancelBtn.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(openBtn);
        buttons.add(cancelBtn);
        panel.add(buttons, "span 2, growx");
        getRootPane().setDefaultButton(openBtn);
        return panel;
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
            showError(checked.issues().stream().map(SourceSettingsDialog::describe).collect(Collectors.joining("<br>")));
            JComponent first = fields.get(checked.issues().get(0).key());
            if (first != null) first.requestFocusInWindow();
            return;
        }

        LogSource source;
        try {
            source = provider.create(checked.values());
            if (source == null) throw new IllegalStateException("create() returned null");
        } catch (IllegalArgumentException e) {
            showError(escape(e.getMessage() != null ? e.getMessage() : e.toString()));
            return;
        } catch (Exception | LinkageError e) {
            log.error("Source plugin '{}' failed to create a source", provider.getName(), e);
            showError(MessageFormat.format(Messages.get("source.dialog.create.failed"),
                    escape(e.getMessage() != null ? e.getMessage() : e.toString())));
            return;
        }

        AppPrefs.saveSourceSettings(providerId(), checked.values(), secretKeys());
        int idx = parserCombo.getSelectedIndex();
        result = new Result(source, idx > 0 ? Optional.of(parsers.get(idx - 1)) : Optional.empty());
        dispose();
    }

    private static String describe(SourceSettings.Issue issue) {
        String key = switch (issue.problem()) {
            case MISSING      -> "source.dialog.error.missing";
            case NOT_A_NUMBER -> "source.dialog.error.number";
            case NOT_A_CHOICE -> "source.dialog.error.choice";
        };
        return MessageFormat.format(Messages.get(key), escape(issue.label()));
    }

    private void showError(String htmlSafeText) {
        errorLabel.setText("<html>" + htmlSafeText + "</html>");
        pack();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
