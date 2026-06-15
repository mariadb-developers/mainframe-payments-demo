import java.util.concurrent.TimeUnit

plugins {
    kotlin("jvm")
    application
    id("com.github.node-gradle.node") version "7.1.0"
}

group = "com.gridgain.demo.payments"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "GridGain External Repository"
        url = uri("https://maven.gridgain.com/nexus/content/repositories/external")
    }
}

configurations.all {
    resolutionStrategy {
        force("org.yaml:snakeyaml:1.33")
        cacheChangingModulesFor(0, TimeUnit.SECONDS)
        cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
    }
}

val ktorVersion = "3.1.3"
val gg8Version = "8.9.18"
val jacksonVersion = "2.17.2"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson:$ktorVersion")

    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // JDBC connection pooling + drivers
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")

    // Kafka consumer (CDC tap reads Debezium topics)
    implementation("org.apache.kafka:kafka-clients:3.8.0")

    // GridGain 8 thin client + DemoAddressFinder helper
    implementation("org.gridgain:ignite-core:$gg8Version")
    implementation("com.gridgain.demo:gg8-client-finder:0.6.0-SNAPSHOT")

    // SnakeYAML force
    implementation("org.yaml:snakeyaml:1.33")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.gridgain.demo.payments.ui.ApplicationKt")
    // GG8 (ignite-core 8.9.18) uses reflective access to java.nio.Buffer.address
    // and other internals. Without these --add-opens flags, IgniteUtils.<clinit>
    // throws InaccessibleObjectException on Java 17, poisoning the JVM (every
    // subsequent JDBC and GG-client call fails with NoClassDefFoundError).
    // Mirrors the standard Ignite Java 17 launch profile.
    applicationDefaultJvmArgs = listOf(
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
        "--add-opens=java.base/java.math=ALL-UNNAMED",
        "--add-opens=java.base/javax.management=ALL-UNNAMED",
        // Run in UTC so JDBC timestamp conversions match the data stores. Postgres, GG, and
        // MariaDB all store naive timestamps in UTC (their pods + sessions are UTC) and the
        // in-cluster Kafka Connect sinks write UTC. With the backend JVM in the laptop's local
        // zone, MariaDB Connector/J converts those UTC datetimes to local on read, so the
        // analytics panel showed times 7h off the actual events (and off the UTC tailers).
        // Pinning the JVM to UTC keeps every read/write in one frame.
        "-Duser.timezone=UTC",
    )
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}

// ── Frontend build via node-gradle ──

node {
    version.set("20.11.1")
    download.set(true)
    nodeProjectDir.set(file("frontend"))
}

val frontendBuild = tasks.register<com.github.gradle.node.npm.task.NpmTask>("frontendBuild") {
    dependsOn(tasks.named("npmInstall"))
    npmCommand.set(listOf("run", "build"))
    inputs.dir("frontend/src")
    inputs.file("frontend/package.json")
    inputs.file("frontend/vite.config.ts")
    inputs.file("frontend/tsconfig.json")
    inputs.file("frontend/tailwind.config.js")
    inputs.file("frontend/postcss.config.js")
    inputs.file("frontend/index.html")
    outputs.dir("frontend/dist")
}

val copyFrontend = tasks.register<Copy>("copyFrontend") {
    dependsOn(frontendBuild)
    from("frontend/dist")
    into(layout.buildDirectory.dir("resources/main/static"))
}

tasks.named("processResources") {
    dependsOn(copyFrontend)
}
