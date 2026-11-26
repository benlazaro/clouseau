// clouseau-example-plugin — a complete, minimal third-party plugin: a logfmt LogParser and
// a demo LogSourceProvider.
//
// Copy this folder to start your own plugin: it builds on its own (this file and
// settings.gradle.kts are all it needs; run Gradle 8 on JDK 17 or 21), and nothing in it comes
// from Clouseau's build. Inside the Clouseau repository it is also part of Clouseau's build,
// whose settings.gradle.kts wins over the one here.
//
// Like any plugin, it builds against the released plugin API from Maven Central (compileOnly);
// the app provides it at runtime. Not shipped with the app. Because it uses the released API
// rather than this repository's, clouseau-plugin's tests, which load its JAR, check that a plugin
// built for the released API still loads.

plugins {
    java
}

// Name your plugin <name>-clouseau-plugin and use that as its artifact name, JAR name and Plugin-Id:
// Browse finds plugins by that name, and the plugin list (plugins.json) takes only names that
// follow it. clouseau-<name>-plugin is kept for plugins the Clouseau project publishes.
val pluginId      = "logfmt-clouseau-plugin"
val pluginVersion = "1.0.0"
// The plugin API it's built for (ClouseauApi.VERSION of an api-v* release on Maven Central).
val clouseauApi   = "0.3.0"
// The PF4J that Clouseau runs plugins with.
val pf4jVersion   = "3.16.1"

repositories {
    mavenCentral()
}

// Clouseau runs on Java 21; the API on Maven Central won't resolve for an older target.
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

dependencies {
    compileOnly("com.droidenx.clouseau:clouseau-api:$clouseauApi")
    compileOnly("org.pf4j:pf4j:$pf4jVersion")
    // Generates META-INF/extensions.idx from @Extension, which PF4J reads to find extensions.
    annotationProcessor("org.pf4j:pf4j:$pf4jVersion")

    testImplementation("com.droidenx.clouseau:clouseau-api:$clouseauApi")
    testImplementation("org.pf4j:pf4j:$pf4jVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
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
