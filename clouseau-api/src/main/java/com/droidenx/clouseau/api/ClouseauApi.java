package com.droidenx.clouseau.api;

/** The plugin contract's own version, separate from the app's. */
public final class ClouseauApi {

    /**
     * The version of this API. Clouseau checks a plugin's {@code Plugin-Requires} manifest entry
     * against it (for example {@code >=0.2.0}) and refuses plugins that need a different one.
     * While the API is 0.x, a change that breaks plugins bumps the minor version.
     *
     * <ul>
     *   <li>0.2.0: highlighters return {@link LogSyntaxHighlighter.Span}s with a
     *       {@link LogSyntaxHighlighter.Role} instead of RGB colors, and every one that applies is used.</li>
     *   <li>0.1.0: the first release.</li>
     * </ul>
     */
    public static final String VERSION = "0.2.0";

    private ClouseauApi() {}
}
