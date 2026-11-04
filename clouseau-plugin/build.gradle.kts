// clouseau-plugin — discovers, loads, and manages plugin lifecycle via PF4J

plugins {
    java
}

dependencies {
    implementation(project(":clouseau-core"))
    implementation("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
}

// The end-to-end test loads the example plugin's JAR through PF4J, as the app would.
// It is passed in as a file, not put on the test classpath, so its classes come only
// from the plugin class loader.
val examplePlugin: Configuration by configurations.creating { isTransitive = false }

dependencies {
    examplePlugin(project(":clouseau-example-plugin"))
}

// A provider object, not a doFirst lambda, so the configuration cache can store the task.
class ExamplePluginJarArg(
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val jar: FileCollection
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dclouseau.examplePlugin.jar=${jar.singleFile.absolutePath}")
}

tasks.test {
    jvmArgumentProviders.add(ExamplePluginJarArg(examplePlugin))
}
