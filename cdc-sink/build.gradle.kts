import java.util.concurrent.TimeUnit

plugins {
    kotlin("jvm")
    // Use the gradleup fork (renamed from john-rengelman). 8.1.1 is incompatible
    // with Gradle 9 due to a removed file-copy property.
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
val jacksonVersion = "2.17.2"

dependencies {
    // Kafka Connect API — `compileOnly` because Kafka Connect provides it at runtime
    // on the pod's classpath. Bundling it would risk classloader conflicts.
    compileOnly("org.apache.kafka:connect-api:$kafkaVersion")

    // JDBC drivers for all three dialects. Bundled into the fat JAR so the
    // sink plugin is self-contained. Kafka Connect loads each plugin in an
    // isolated classloader (plugin.path layout), so bundling here doesn't
    // conflict with the other plugins running in the same pod.
    implementation("org.gridgain:ignite-core:$gg8Version")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")

    // Jackson for parsing the Debezium event envelope JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.slf4j:slf4j-api:2.0.13")
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
    // Kafka Connect's PluginUtils refuses to load plugin JARs whose manifest
    // sets `Multi-Release: true` unless the JAR has a top-level Main-Class —
    // keep the manifest minimal.
    manifest {
        attributes("Implementation-Title" to "GridGain CDC sink")
        attributes("Implementation-Version" to project.version.toString())
    }
}

tasks.named("build") { dependsOn(tasks.shadowJar) }
