package com.droidenx.clouseau.api;

/** The plugin contract's own version, separate from the app's. */
public final class ClouseauApi {

    /**
     * The version of this API. Clouseau checks a plugin's {@code Plugin-Requires} manifest entry
     * against it (for example {@code >=0.2.0}) and refuses plugins that need a different one.
     * While the API is 0.x, a change that breaks plugins bumps the minor version.
     *
     * <ul>
     *   <li>0.3.0: parsers are told the timezone the log was written in
     *       ({@link LogParser#parse(String, java.time.ZoneId)}), so a timestamp with an offset
     *       is no longer moved; {@link LogEntry#builder(String)}; {@link LogEntry} and
     *       {@link SourceSetting} and {@link LogSyntaxHighlighter.Span} are final classes instead of records, so they can grow
     *       without breaking plugins. Plugins built for 0.2.0 still load and run unchanged.</li>
     *   <li>0.2.0: highlighters return {@link LogSyntaxHighlighter.Span}s with a
     *       {@link LogSyntaxHighlighter.Role} instead of RGB colors, and every one that applies is used.</li>
     *   <li>0.1.0: the first release.</li>
     * </ul>
     */
    public static final String VERSION = "0.3.0";

    private ClouseauApi() {}
}
