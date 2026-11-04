package com.droidenx.clouseau.api;

import org.pf4j.ExtensionPoint;

import java.util.List;
import java.util.Map;

/**
 * Implement this to let Clouseau read logs from somewhere new, such as Grafana Loki,
 * CloudWatch or a message queue. Annotate your implementation with {@code @Extension} and
 * drop the JAR in the plugins folder.
 *
 * <p>Each provider appears under <b>File → Open Source</b>. Choosing it shows a form built
 * from {@link #settings()}; when the user confirms, Clouseau calls {@link #create} with
 * their values and streams the returned {@link LogSource} into a new tab, parsing each
 * line with the parser they picked (or auto-detection).
 *
 * <p>PF4J creates one instance of a provider and shares it, so keep per-connection state
 * in the {@code LogSource}, not here.
 */
public interface LogSourceProvider extends ExtensionPoint {

    /** Shown in the menu and as the form's title, for example "Grafana Loki". */
    String getName();

    /** The settings the user fills in, in display order. May be empty. */
    List<SourceSetting> settings();

    /**
     * Creates a source from the user's values, keyed by {@link SourceSetting#key()}.
     * Clouseau has already checked that required values are present and that numbers
     * are numbers; optional values the user left empty are absent from the map.
     *
     * <p>Called on the Event Dispatch Thread, so don't connect or do I/O here: check the
     * values and build the source, and connect in {@link LogSource#open}. Throw
     * {@link IllegalArgumentException} with a message for the user if a value is wrong.
     */
    LogSource create(Map<String, String> values) throws Exception;
}
