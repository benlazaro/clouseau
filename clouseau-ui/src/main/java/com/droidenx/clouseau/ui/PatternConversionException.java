package com.droidenx.clouseau.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Why a template or a Logback / Log4j pattern can't be made into a regex, as a message key in
 * {@code messages.properties} and its arguments, so the Parser Editor shows a sentence that
 * names the field rather than the converter's own wording. {@link #getMessage()} is that
 * sentence.
 */
final class PatternConversionException extends IllegalArgumentException {

    private static final Pattern DUPLICATE_GROUP = Pattern.compile("Named capturing group <([^>]+)> is already defined");

    private final String key;
    private final transient Object[] args;

    PatternConversionException(String key, Object... args) {
        super(Messages.format(key, args));
        this.key = key;
        this.args = args.clone();
    }

    String key() {
        return key;
    }

    Object[] args() {
        return args.clone();
    }

    /**
     * The regex a converter made doesn't compile. A value named twice (a field, or the time
     * written twice) is said as such; anything else as a regex that doesn't work.
     *
     * @param field {@code "template"} or {@code "pattern"}, the start of the message keys
     */
    static PatternConversionException invalidRegex(String field, PatternSyntaxException e) {
        Matcher m = DUPLICATE_GROUP.matcher(e.getDescription() != null ? e.getDescription() : "");
        if (m.find()) return new PatternConversionException("parsereditor.error." + field + ".duplicate", m.group(1));
        return new PatternConversionException("parsereditor.error." + field + ".invalid");
    }
}
