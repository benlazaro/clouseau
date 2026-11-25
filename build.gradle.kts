buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.apache.xmlgraphics:batik-transcoder:1.20")
        classpath("org.apache.xmlgraphics:batik-codec:1.20")
    }
}

val pf4jVersion      = "3.16.1"
val flatlafVersion   = "3.4.1"
val migLayoutVersion = "11.3"
val rstaVersion      = "3.4.0"
val junitVersion     = "5.10.3"
val assertjVersion   = "3.27.7"
val mockitoVersion   = "5.12.0"
val lombokVersion    = "1.18.34"
val slf4jVersion     = "2.0.13"

// Expose versions on the root project so subprojects can read them via rootProject.extra["..."]
extra["pf4jVersion"]      = pf4jVersion
extra["flatlafVersion"]   = flatlafVersion
extra["migLayoutVersion"] = migLayoutVersion
extra["rstaVersion"]      = rstaVersion
extra["lombokVersion"]    = lombokVersion
extra["slf4jVersion"]     = slf4jVersion

subprojects {
    apply(plugin = "java")

    group   = "com.droidenx.clouseau"
    // Releases pass -PappVersion=<tag>, and the Edge workflow -PappVersion=edge. Anything else
    // (the IDE, gradlew run) is 0.0.0-SNAPSHOT, so a development build is plain to see, and
    // never compared with releases.
    version = findProperty("appVersion") as String? ?: "0.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    dependencies {
        val compileOnly            by configurations
        val annotationProcessor    by configurations
        val implementation         by configurations
        val testImplementation     by configurations
        val testAnnotationProcessor by configurations
        val testRuntimeOnly        by configurations

        compileOnly("org.projectlombok:lombok:$lombokVersion")
        annotationProcessor("org.projectlombok:lombok:$lombokVersion")
        // Not in clouseau-api: its published POM would hand SLF4J to every plugin.
        if (project.name != "clouseau-api") implementation("org.slf4j:slf4j-api:$slf4jVersion")

        testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
        testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
        testImplementation("org.assertj:assertj-core:$assertjVersion")
        testImplementation("org.mockito:mockito-core:$mockitoVersion")
        testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> { useJUnitPlatform() }

    extra["pf4jVersion"]      = pf4jVersion
    extra["flatlafVersion"]   = flatlafVersion
    extra["migLayoutVersion"] = migLayoutVersion
    extra["rstaVersion"]      = rstaVersion
}
