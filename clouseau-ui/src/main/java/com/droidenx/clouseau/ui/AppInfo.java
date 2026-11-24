package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.ClouseauApi;

/**
 * What a bug report needs to know about this copy of Clouseau: its version, the plugin API
 * version, Java and the operating system. Help → About's Copy Details copies it, and the crash
 * notice starts its details with it. English on purpose: it's pasted into GitHub issues.
 */
final class AppInfo {

    private AppInfo() {}

    /** One line each: Clouseau and plugin API versions, Java (vendor), OS (arch). No trailing newline. */
    static String lines(String appVersion) {
        return "Clouseau v" + appVersion + "\n"
                + "Plugin API v" + ClouseauApi.VERSION + "\n"
                + "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n"
                + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")";
    }
}
