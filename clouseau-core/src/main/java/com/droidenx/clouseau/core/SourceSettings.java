package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.SourceSetting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks and tidies the values a user entered for a plugin source's
 * {@link SourceSetting}s before they reach {@code LogSourceProvider.create}.
 */
public final class SourceSettings {

    private SourceSettings() {}

    /** What's wrong with one value. The UI turns it into a message. */
    public enum Problem { MISSING, NOT_A_NUMBER, NOT_A_CHOICE }

    /** A problem with the setting labelled {@code label}. */
    public record Issue(String key, String label, Problem problem) {}

    /** The tidied values to pass on, or the issues to show the user. */
    public record Result(Map<String, String> values, List<Issue> issues) {
        public boolean ok() { return issues.isEmpty(); }
    }

    /**
     * Trims each value (passwords are kept exactly as typed), drops empty optional values,
     * and checks that required values are present, numbers are whole numbers, and choices
     * are among the allowed ones. Keys that aren't settings are ignored.
     */
    public static Result check(List<SourceSetting> settings, Map<String, String> entered) {
        Map<String, String> values = new LinkedHashMap<>();
        List<Issue> issues = new ArrayList<>();
        for (SourceSetting s : settings) {
            String raw = entered.get(s.key());
            String value = raw == null ? "" : s.type() == SourceSetting.Type.PASSWORD ? raw : raw.trim();
            if (value.isEmpty()) {
                if (s.required()) issues.add(new Issue(s.key(), s.label(), Problem.MISSING));
                continue;
            }
            switch (s.type()) {
                case NUMBER -> {
                    try {
                        Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        issues.add(new Issue(s.key(), s.label(), Problem.NOT_A_NUMBER));
                        continue;
                    }
                }
                case CHOICE -> {
                    if (!s.choices().contains(value)) {
                        issues.add(new Issue(s.key(), s.label(), Problem.NOT_A_CHOICE));
                        continue;
                    }
                }
                default -> { }
            }
            values.put(s.key(), value);
        }
        return new Result(Map.copyOf(values), List.copyOf(issues));
    }
}
