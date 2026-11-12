package com.droidenx.clouseau.ui;

/**
 * What a tab shows (D-21): one object per load, replaced on reload or Reconnect. It reaches
 * the tab only through the {@link TabContext} it was made with, which stops working once
 * it is replaced. EDT only.
 */
sealed interface TabContent permits FileContent, MergedContent, LiveContent {

    /** Loads or opens what this content shows into the tab. */
    void start();

    /** Follow mode turned on or off: start or stop following what this content reads. */
    void setFollow(boolean on);

    /**
     * The user cleared the table (already emptied): carry on from here, or stop showing what
     * was loaded. A file tab hides its rows instead, so this isn't called for one.
     */
    void cleared();

    /** Stops for good: cancels workers, closes sources, deletes copies. The tab is about to show something else, or close. */
    void dispose();

    /** True while the first load runs (rows still arriving in a batch). */
    default boolean loading() { return false; }

    /** The status bar's left side: what the tab shows. */
    String path();

    /** The status bar path's tooltip. */
    String pathTooltip();

    /** Appended to the row counts, such as " (partly loaded)"; empty if nothing to say. */
    default String statusNote() { return ""; }
}
