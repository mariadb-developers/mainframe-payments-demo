import java.util.concurrent.TimeUnit

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.yaml:snakeyaml:2.2")
        classpath("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    }
}

plugins {
    java
    id("com.gridgain.demo.plugin") version "0.5.1-SNAPSHOT"
    // Declared here so :demo-ui can apply kotlin("jvm") without re-specifying the version.
    // The included plugin build pulls Kotlin onto the shared classpath, which would
    // otherwise conflict with a versioned `plugins { kotlin("jvm") version ... }` in
    // the subproject.
    kotlin("jvm") version "2.2.0" apply false
}

group = "org.gridgain.demo"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()  // Required for SNAPSHOT dependencies
    mavenCentral()
    maven {
        name = "GridGain External Repository"
        url = uri("https://maven.gridgain.com/nexus/content/repositories/external")
    }
}

configurations.all {
    resolutionStrategy {
        // Force all SnakeYAML dependencies to use version 1.33
        force("org.yaml:snakeyaml:1.33")

        // This demo is GG8-only (CLAUDE.md §15: GG9 variant out of scope). The
        // toolkit's plugin and UI bring GG9 client deps transitively; without
        // these forces, Gradle's "highest version wins" resolution drags the
        // entire runtimeClasspath up to 9.x and the GG8 JDBC thin driver class
        // (`org.apache.ignite.IgniteJdbcThinDriver`, present in ignite-core 8.x
        // but removed in 9.x) disappears — :deployDataModel can't find it.
        force("org.gridgain:ignite-core:8.9.18")
        force("org.gridgain:ignite-indexing:8.9.18")

        // Ensure we don't cache corrupted results
        cacheChangingModulesFor(0, TimeUnit.SECONDS)
        cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
    }
}

dependencies {
    // Explicitly add standard SnakeYAML to override any Android variants
    implementation("org.yaml:snakeyaml:1.33")
    // Data generator runtime — resolved by the dataGenerate task to build the forked JVM's classpath
    "dataGeneratorGg8Runtime"("com.gridgain.demo:gridgain-demo-data-generator-gg8:0.0.1-SNAPSHOT")
    // Plugin dependency for ActiveNodesLoader - code gracefully falls back if not available
    implementation("com.gridgain.demo:gridgain-demo-gradle-plugin:0.5.1-SNAPSHOT")
    // UI project — provides the Ktor server for launchPluginUi task
    runtimeOnly("com.gridgain.demo:gridgain-demo-ui:0.5.1-SNAPSHOT")
    // GG8 (8.9.18) — the deployed cluster is GG8 (CLAUDE.md §15: GG9 variant out of scope).
    // Inherited GG9 entries removed; they caused JDBC handshake failures against the GG8 cluster.
    implementation("org.gridgain:ignite-core:8.9.18")
    implementation("org.gridgain:ignite-indexing:8.9.18")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// Make validateRequirements part of the standard verification lifecycle
tasks.named("check").configure {
    dependsOn("validateRequirements")
}

// Ensure launchPluginUi rebuilds the UI project (including frontend) when sources change.
// Without this, Gradle's composite build may not detect changes in the UI project.
tasks.named("launchPluginUi") {
    inputs.files(configurations.named("runtimeClasspath"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Fix duplicate files in distribution tasks
tasks.withType<Tar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
