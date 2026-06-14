import java.util.concurrent.TimeUnit

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") version "8.3.5"
    `java-library`
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

val kafkaVersion = "3.8.0"
val gg8Version = "8.9.18"

dependencies {
    // Kafka Connect API — provided by the connect runtime at execution time.
    compileOnly("org.apache.kafka:connect-api:$kafkaVersion")

    // GG8 thin client — used to register the ContinuousQuery against the demo's
    // GG cluster from inside the Kafka Connect plugin classloader.
    implementation("org.gridgain:ignite-core:$gg8Version")

    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.slf4j:slf4j-api:2.0.13")

    // connect-api is compileOnly for the main set (the Connect runtime provides it);
    // tests construct Schema/Struct directly, so it must be on the test classpath.
    testImplementation("org.apache.kafka:connect-api:$kafkaVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    // Gradle 9 no longer bundles the JUnit Platform launcher on the test runtime.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
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

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes("Implementation-Title" to "GridGain cache publisher (Kafka Connect source)")
        attributes("Implementation-Version" to project.version.toString())
    }
}

tasks.named("build") { dependsOn(tasks.shadowJar) }
