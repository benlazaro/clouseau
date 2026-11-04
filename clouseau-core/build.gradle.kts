// clouseau-core — UI-independent engine: parsing, file reading, tailing
// Rule: NO Swing/UI imports allowed here.

plugins {
    `java-library`
}

dependencies {
    api(project(":clouseau-api"))
    implementation("com.hierynomus:sshj:0.38.0")
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
    testCompileOnly("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
    testRuntimeOnly("org.pf4j:pf4j:${rootProject.extra["pf4jVersion"]}")
}
