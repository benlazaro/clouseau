package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.HighlightRule;
import com.droidenx.clouseau.core.HighlightRuleStore;
import com.droidenx.clouseau.core.HighlightRules;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The app's auto-highlight rules (UC-3, D-29), shared by every window and tab: read from
 * {@code <data>/highlight-rules.json} on first use, saved on every change, and handed to
 * whoever listens ({@link WindowManager}, which passes them to every tab). EDT only.
 */
@Slf4j
final class HighlightRuleSet {

    private static List<HighlightRule> rules;
    /** The rules that match (on and complete), as last handed out. */
    private static List<HighlightRule> applying;
    private static HighlightRules compiled;
    private static final List<Consumer<HighlightRules>> listeners = new ArrayList<>();
    /** Where the rules are kept; tests point it elsewhere. */
    static Path file = AppDirs.dataDir().resolve(HighlightRuleStore.FILE_NAME);

    private HighlightRuleSet() {}

    /** Every rule, in order, the ones turned off included. */
    static List<HighlightRule> rules() {
        if (rules == null) {
            rules = HighlightRuleStore.read(file);
            applying = applying(rules);
            compiled = HighlightRules.of(rules);
        }
        return rules;
    }

    /** The rules that apply, ready to match. */
    static HighlightRules compiled() {
        rules();
        return compiled;
    }

    /**
     * Replaces the rules and saves them. The listeners hear only when what matches changed
     * (not for a rule still being typed, or one turned off that was incomplete anyway).
     * Returns false if the rules couldn't be saved (they still apply until Clouseau closes).
     */
    static boolean set(List<HighlightRule> newRules) {
        rules();
        rules = List.copyOf(newRules);
        List<HighlightRule> now = applying(rules);
        if (!now.equals(applying)) {
            applying = now;
            compiled = HighlightRules.of(rules);
            listeners.forEach(l -> l.accept(compiled));
        }
        try {
            HighlightRuleStore.write(file, rules);
            return true;
        } catch (IOException e) {
            log.warn("Could not save highlight rules to {}", file, e);
            return false;
        }
    }

    private static List<HighlightRule> applying(List<HighlightRule> all) {
        return all.stream().filter(r -> r.enabled() && r.isComplete()).toList();
    }

    /** Calls {@code listener} with the rules that apply whenever they change. */
    static void onChange(Consumer<HighlightRules> listener) {
        listeners.add(listener);
    }
}
