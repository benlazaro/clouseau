// clouseau-example-plugin — a complete, minimal third-party plugin: a logfmt LogParser and
// a demo LogSourceProvider.
// Copy this module to start your own plugin. Like any plugin, it depends only on
// clouseau-api (compileOnly); the app provides it at runtime. Not shipped with the app.

plugins {
    java
}

val pluginId      = "clouseau-example-logfmt"
val pluginVersion = "1.0.0"

dependencies {
    compileOnly(project(":clouseau-api"))
    compileOnly("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
    // Generates META-INF/extensions.idx from @Extension, which PF4J reads to find extensions.
    annotationProcessor("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")

    testImplementation(project(":clouseau-api"))
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
            "Plugin-Provider" to "Clouseau",
            "Plugin-Name"     to "logfmt example",
            "Plugin-Description" to "Example plugin: a logfmt parser and a demo live source",
        )
    }
}
