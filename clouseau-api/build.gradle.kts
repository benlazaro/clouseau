// clouseau-api — the plugin contract
// Third-party plugins only ever depend on THIS module.
// Rule: no Swing, no core internals, no heavy runtime deps.
//
// Released on its own (tags api-v*, .github/workflows/api-release.yml), with its own version:
// ClouseauApi.VERSION, read here so the jar, the POM and the code can't disagree.

plugins {
    java
    `maven-publish`
    signing
    id("me.champeau.gradle.japicmp") version "0.4.6"
}

val apiVersion: String = Regex("""VERSION\s*=\s*"([^"]+)"""")
    .find(file("src/main/java/com/droidenx/clouseau/api/ClouseauApi.java").readText())
    ?.groupValues?.get(1)
    ?: error("ClouseauApi.VERSION not found")
version = apiVersion

dependencies {
    // PF4J annotations (@ExtensionPoint, @Extension) — compile-only, no runtime cost
    compileOnly("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
}

java {
    withSourcesJar()
    withJavadocJar()
}

// A stable module name for plugins built as Java modules; without it, the name would come from
// the jar's file name.
tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "com.droidenx.clouseau.api")
    }
}

// ── Compatibility with the released API ─────────────────────────────────────────────────────
// checkApiCompatibility (part of check) compares this jar with the last API on Maven Central and
// fails on any change that would break a plugin built against it: a removed or changed class,
// method or field, or a new abstract method in an interface (binary-compatible by the JLS, but
// every plugin implementing the interface would then fail when Clouseau calls it). The report is
// build/reports/japicmp/. After an API release is published, set apiBaseline to it.
//
// A deliberate break (a new minor version while 0.x, a new major after): bump ClouseauApi.VERSION,
// set allowBreakingChanges to true for that release, and back to false once it's the baseline.
val apiBaseline = "0.3.0"
val allowBreakingChanges = false

val apiBaselineJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
// The released jar, fetched by its Maven Central path under a made-up group: asked for as
// com.droidenx.clouseau:clouseau-api, Gradle would resolve it to this project (same coordinates)
// and compare the jar with itself.
repositories {
    ivy {
        name = "releasedApi"
        url = uri("https://repo1.maven.org/maven2/")
        patternLayout { artifact("com/droidenx/clouseau/[module]/[revision]/[module]-[revision].[ext]") }
        metadataSources { artifact() }
        content { includeGroup("released-clouseau-api") }
    }
}
dependencies {
    apiBaselineJar("released-clouseau-api:clouseau-api:$apiBaseline@jar")
}

val checkApiCompatibility = tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("checkApiCompatibility") {
    group = "verification"
    description = "Fails if the API breaks plugins built against clouseau-api $apiBaseline."
    oldClasspath.from(apiBaselineJar)
    newClasspath.from(tasks.jar)
    // PF4J's ExtensionPoint is compileOnly: on neither classpath.
    ignoreMissingClasses.set(true)
    // The report lists every change; the build fails only on a binary or source incompatibility.
    // (onlyBinaryIncompatibleModified would hide a new abstract method, which is source-incompatible.)
    onlyBinaryIncompatibleModified.set(false)
    failOnSourceIncompatibility.set(!allowBreakingChanges)
    txtOutputFile.set(layout.buildDirectory.file("reports/japicmp/api-compatibility.txt"))
    htmlOutputFile.set(layout.buildDirectory.file("reports/japicmp/api-compatibility.html"))
}
tasks.named("check") { dependsOn(checkApiCompatibility) }

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        links("https://docs.oracle.com/en/java/javase/21/docs/api/")
        addStringOption("Xdoclint:all,-missing", "-quiet")
    }
}

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "clouseau-api"
            from(components["java"])
            pom {
                name.set("Clouseau plugin API")
                description.set("The contract Clouseau log viewer plugins implement: parsers, formatters, " +
                        "syntax highlighters and live log sources.")
                url.set("https://benlazaro.github.io/clouseau/guide/developers.html")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/benlazaro/clouseau/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("benlazaro")
                        name.set("Ben Lazaro")
                        url.set("https://github.com/benlazaro")
                    }
                }
                scm {
                    url.set("https://github.com/benlazaro/clouseau")
                    connection.set("scm:git:https://github.com/benlazaro/clouseau.git")
                    developerConnection.set("scm:git:ssh://git@github.com/benlazaro/clouseau.git")
                }
            }
        }
    }
    repositories {
        // A Maven-layout folder the release workflow bundles for Maven Central and attaches to the release.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}

// Signed only when a key is given (the release workflow's SIGNING_KEY / SIGNING_PASSWORD secrets),
// so local builds and publishToMavenLocal need no key. Maven Central requires the signatures.
// (A workflow passes an empty string when the secret isn't set, so blank counts as none.)
val signingKey = providers.environmentVariable("SIGNING_KEY").orNull?.takeIf { it.isNotBlank() }
if (signingKey != null) {
    signing {
        useInMemoryPgpKeys(signingKey, providers.environmentVariable("SIGNING_PASSWORD").orNull)
        sign(publishing.publications["api"])
    }
}
