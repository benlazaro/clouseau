package com.droidenx.clouseau.api;

import org.pf4j.ExtensionPoint;

/**
 * Implement this to change how a message reads in the detail panel: pretty-print a payload,
 * decode hex to text, expand an escaped string. It changes only what the detail panel shows;
 * the table, filters, find and copied lines keep the message as logged.
 * Annotate your implementation with {@code @Extension} and drop the JAR in the plugins folder.
 *
 * <p><b>When it runs.</b> Each time the user selects an entry, Clouseau runs the formatters in
 * turn on its message (the message and any continuation lines, joined with {@code \n}): every
 * formatter whose {@link #canFormat} accepts the text so far gets it and passes its result on, so
 * a formatter may receive another formatter's output. The built-in JSON and {@code toString()}
 * formatters come first, then plugin formatters. The result is what the {@link LogSyntaxHighlighter}s
 * color.
 *
 * <p><b>Threads.</b> Clouseau calls it on the Swing event thread, so the window waits while it
 * works: keep {@link #canFormat} cheap and {@link #format} well under a few milliseconds for a
 * typical message, and never do I/O. One instance serves every tab; keep it stateless.
 *
 * <p><b>Failures.</b> If {@link #canFormat} or {@link #format} throws, or {@link #format} returns
 * {@code null}, the formatter is skipped for that message (the text goes on as it was) and the
 * first failure is logged.
 *
 * <p><b>Its button.</b> The detail panel's header has a button per format that turns it off.
 * Give a formatter the same {@link #getName} as the highlighter that colors its output, and the
 * two share one button (the built-in JSON formatter and highlighter do).
 */
public interface LogFormatter extends ExtensionPoint {

    /** Human-readable name, shown on the detail panel's toggle button (e.g. "JSON"). */
    String getName();

    /**
     * Returns true if this formatter has something to do with {@code input}. Called for every
     * selected entry, on the event thread: a quick look (a prefix, a character), not a parse.
     */
    boolean canFormat(String input);

    /**
     * Returns {@code input} formatted. Called only after {@link #canFormat} accepted it. Return the
     * input unchanged, not null, when it turns out not to be what you expected.
     */
    String format(String input);
}
