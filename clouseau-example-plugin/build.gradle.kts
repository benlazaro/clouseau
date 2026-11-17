// clouseau-example-plugin — a complete, minimal third-party plugin: a logfmt LogParser and
// a demo LogSourceProvider.
// Copy this module to start your own plugin. Like any plugin, it builds against the released
// plugin API from Maven Central (compileOnly); the app provides it at runtime. Not shipped
// with the app. Because it uses the released API rather than this repository's, clouseau-plugin's
// tests, which load its JAR, check that a plugin built for the released API still loads.

plugins {
    java
}

val pluginId      = "clouseau-example-logfmt"
val pluginVersion = "1.0.0"
// The plugin API it's built for (ClouseauApi.VERSION of an api-v* release on Maven Central).
val clouseauApi   = "0.2.0"

dependencies {
    compileOnly("com.droidenx.clouseau:clouseau-api:$clouseauApi")
    compileOnly("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
    // Generates META-INF/extensions.idx from @Extension, which PF4J reads to find extensions.
    annotationProcessor("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")

    testImplementation("com.droidenx.clouseau:clouseau-api:$clouseauApi")
    testImplementation("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
}

tasks.jar {
    archiveBaseName.set(pluginId)
    archiveVersion.set(pluginVersion)
    // PF4J reads the plugin's identity from the manifest.
    manifest {
        attributes(
            "Plugin-Id"       to pluginId,
            "Plugin-Version"  to pluginVersion,
            // The API version it was built for; a Clouseau with an incompatible API refuses it.
            "Plugin-Requires" to ">=$clouseauApi",
            "Plugin-Provider" to "Clouseau",
            "Plugin-Name"     to "logfmt example",
            "Plugin-Description" to "Example plugin: a logfmt parser and a demo live source",
        )
    }
}
