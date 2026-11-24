// clouseau-core — UI-independent engine: parsing, file reading, tailing
// Rule: NO Swing/UI imports allowed here.

plugins {
    `java-library`
}

dependencies {
    api(project(":clouseau-api"))
    implementation("com.hierynomus:sshj:0.41.2")
    constraints {
        // sshj 0.41.2 asks for 1.84; 1.85 fixes GHSA-9pwp-9qqc-pr26 and GHSA-qp49-qgx5-5m26.
        // Drop these once sshj asks for 1.85 or later.
        listOf("bcprov", "bcpkix", "bcutil").forEach {
            implementation("org.bouncycastle:$it-jdk18on:1.87") { because("security fixes, see above") }
        }
    }
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
    testImplementation("org.apache.sshd:sshd-core:2.14.0")   // embedded SSH server for SshLogSource and SshRemote tests
    testImplementation("org.apache.sshd:sshd-sftp:2.14.0")   // its SFTP subsystem, for SshRemote.list
    testCompileOnly("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
    testRuntimeOnly("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
}
