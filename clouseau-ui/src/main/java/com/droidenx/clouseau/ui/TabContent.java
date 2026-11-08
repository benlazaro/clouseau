package com.droidenx.clouseau.ui;

/**
 * What a tab shows (D-21): one object per load, replaced on reload or Reconnect. It reaches
 * the tab only through the {@link TabContext} it was made with, which stops working once
 * it is replaced. EDT only.
 *
 * <p>Single files and merged views still live in {@link TabPanel}; they become
 * {@code FileContent} and {@code MergedContent} in D-21's next steps.
 */
sealed interface TabContent permits LiveContent {

    /** Loads or opens what this content shows into the tab. */
    void start();

    /** Stops for good: cancels workers, closes sources. The tab is about to show something else, or close. */
    void dispose();

    /** The status bar's left side: what the tab shows. */
    String path();

    /** The status bar path's tooltip. */
    String pathTooltip();
}
