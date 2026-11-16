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
        // A Maven-layout folder the release workflow uploads from (and, later, bundles for Maven Central).
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
