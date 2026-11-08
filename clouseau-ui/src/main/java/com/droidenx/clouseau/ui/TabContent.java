package com.droidenx.clouseau.ui;

/**
 * What a tab shows (D-21): one object per load, replaced on reload or Reconnect. It reaches
 * the tab only through the {@link TabContext} it was made with, which stops working once
 * it is replaced. EDT only.
 *
 * <p>Single files still live in {@link TabPanel}; they become {@code FileContent} in
 * D-21's next step.
 */
sealed interface TabContent permits LiveContent, MergedContent {

    /** Loads or opens what this content shows into the tab. */
    void start();

    /** Follow mode turned on or off: start or stop following what this content reads. */
    void setFollow(boolean on);

    /** The user cleared the table: stop showing and following what was loaded. */
    void cleared();

    /** Stops for good: cancels workers, closes sources. The tab is about to show something else, or close. */
    void dispose();

    /** The status bar's left side: what the tab shows. */
    String path();

    /** The status bar path's tooltip. */
    String pathTooltip();
}
