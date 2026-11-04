package com.droidenx.clouseau.api;

import java.util.List;
import java.util.Objects;

/**
 * One setting a {@link LogSourceProvider} needs from the user, such as a URL, a query or
 * a token. Clouseau shows a form with one field per setting and passes the values to
 * {@link LogSourceProvider#create}. The API stays free of Swing: a plugin describes its
 * settings, and the app draws them.
 *
 * <p>Build settings with the factory methods, then adjust them:
 * <pre>{@code
 * List.of(
 *     SourceSetting.text("url", "Server URL").withDefault("http://localhost:3100"),
 *     SourceSetting.text("query", "Query"),
 *     SourceSetting.password("token", "API token").optional(),
 *     SourceSetting.number("limit", "Lines to fetch first").withDefault("1000"),
 *     SourceSetting.choice("direction", "Order", List.of("forward", "backward")))
 * }</pre>
 *
 * @param key          the key the value is passed under; unique within a provider
 * @param label        shown next to the field
 * @param type         what kind of field to show
 * @param required     whether the user must fill it in
 * @param defaultValue shown the first time, before the user has saved a value; may be null
 * @param choices      the allowed values for {@link Type#CHOICE}; empty for other types
 */
public record SourceSetting(String key, String label, Type type, boolean required,
                            String defaultValue, List<String> choices) {

    /** Kinds of field. */
    public enum Type {
        /** Free text, on one line. */
        TEXT,
        /** Hidden text. Clouseau stores it encrypted, never in plain text. */
        PASSWORD,
        /** A whole number (passed as its decimal text). */
        NUMBER,
        /** One of {@link SourceSetting#choices()}. */
        CHOICE
    }

    public SourceSetting {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
        if (key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        choices = choices == null ? List.of() : List.copyOf(choices);
        if (type == Type.CHOICE && choices.isEmpty())
            throw new IllegalArgumentException("a CHOICE setting needs at least one choice: " + key);
        if (type != Type.CHOICE && !choices.isEmpty())
            throw new IllegalArgumentException("only CHOICE settings have choices: " + key);
    }

    /** A required text setting. */
    public static SourceSetting text(String key, String label) {
        return new SourceSetting(key, label, Type.TEXT, true, null, List.of());
    }

    /** A required password setting, stored encrypted. */
    public static SourceSetting password(String key, String label) {
        return new SourceSetting(key, label, Type.PASSWORD, true, null, List.of());
    }

    /** A required whole-number setting. */
    public static SourceSetting number(String key, String label) {
        return new SourceSetting(key, label, Type.NUMBER, true, null, List.of());
    }

    /** A required choice from {@code choices}; the first is the default. */
    public static SourceSetting choice(String key, String label, List<String> choices) {
        return new SourceSetting(key, label, Type.CHOICE, true,
                choices == null || choices.isEmpty() ? null : choices.get(0), choices);
    }

    /** This setting, but the user may leave it empty. */
    public SourceSetting optional() {
        return new SourceSetting(key, label, type, false, defaultValue, choices);
    }

    /** This setting, pre-filled with {@code value} until the user saves their own. */
    public SourceSetting withDefault(String value) {
        return new SourceSetting(key, label, type, required, value, choices);
    }
}
