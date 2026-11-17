package com.droidenx.clouseau.api;

import java.util.List;
import java.util.Objects;

/**
 * One setting a {@link LogSourceProvider} needs from the user, such as a URL, a query or
 * a token. Clouseau shows a form with one field per setting and passes the values to
 * {@link LogSourceProvider#create}. The API stays free of Swing: a plugin describes its
 * settings, and the app draws them. Immutable.
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
 * That's how settings grow: something added in a later API version comes as another
 * {@code with…} method with a default, so settings built this way keep working. The
 * six-value constructor stays as it is, for plugins built against API 0.2, but never
 * gains values.
 */
public final class SourceSetting {

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

    private final String key;
    private final String label;
    private final Type type;
    private final boolean required;
    private final String defaultValue;
    private final List<String> choices;

    /**
     * A setting with these values. Prefer the factory methods ({@link #text}, {@link #choice}…):
     * this constructor is kept for plugins built against API 0.2 and won't take values added later.
     *
     * @param key          the key the value is passed under; unique within a provider
     * @param label        shown next to the field
     * @param type         what kind of field to show
     * @param required     whether the user must fill it in
     * @param defaultValue shown the first time, before the user has saved a value; may be null
     * @param choices      the allowed values for {@link Type#CHOICE}; empty for other types
     */
    public SourceSetting(String key, String label, Type type, boolean required,
                         String defaultValue, List<String> choices) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
        if (key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        choices = choices == null ? List.of() : List.copyOf(choices);
        if (type == Type.CHOICE && choices.isEmpty())
            throw new IllegalArgumentException("a CHOICE setting needs at least one choice: " + key);
        if (type != Type.CHOICE && !choices.isEmpty())
            throw new IllegalArgumentException("only CHOICE settings have choices: " + key);
        this.key = key;
        this.label = label;
        this.type = type;
        this.required = required;
        this.defaultValue = defaultValue;
        this.choices = choices;
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

    /** The key the value is passed under; unique within a provider. */
    public String key() { return key; }

    /** Shown next to the field. */
    public String label() { return label; }

    /** What kind of field to show. */
    public Type type() { return type; }

    /** Whether the user must fill it in. */
    public boolean required() { return required; }

    /** Shown the first time, before the user has saved a value; may be null. */
    public String defaultValue() { return defaultValue; }

    /** The allowed values for {@link Type#CHOICE}; empty for other types. */
    public List<String> choices() { return choices; }

    @Override
    public boolean equals(Object o) {
        return o instanceof SourceSetting s
                && key.equals(s.key) && label.equals(s.label) && type == s.type
                && required == s.required && Objects.equals(defaultValue, s.defaultValue)
                && choices.equals(s.choices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, label, type, required, defaultValue, choices);
    }

    @Override
    public String toString() {
        return "SourceSetting[key=" + key + ", label=" + label + ", type=" + type + ", required=" + required
                + ", defaultValue=" + defaultValue + ", choices=" + choices + "]";
    }
}
