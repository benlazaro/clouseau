package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogFormatter;

/**
 * Built-in formatter for objects' {@code toString()} in a message ({@link ToStringFormat}): a
 * structure too wide for one line is laid out one entry per line, indented by depth, where it
 * stands in the message. Shares its toggle with {@link ToStringHighlighter}.
 */
public final class ToStringFormatter implements LogFormatter {

    @Override
    public String getName() { return ToStringHighlighter.NAME; }

    @Override
    public boolean canFormat(String input) {
        return ToStringFormat.contains(input);
    }

    @Override
    public String format(String input) {
        return ToStringFormat.format(input);
    }
}
