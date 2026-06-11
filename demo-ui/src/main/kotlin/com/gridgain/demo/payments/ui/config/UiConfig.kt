package com.gridgain.demo.payments.ui.config

import java.nio.file.Path
import java.nio.file.Paths

data class UiConfig(
    val serverPort: Int,
    val mainframeJdbcUrl: String,
    val mainframeUsername: String,
    val mainframePassword: String,
    val mariaDbJdbcUrl: String,
    val mariaDbUsername: String,
    val mariaDbPassword: String,
    val clusterName: String,
    val clientEndpointsFile: Path,
    val kafkaBootstrapServers: String,
    val cdcTopicPrefix: String,
    val projectDirectory: Path,
) {
    companion object {
        fun fromEnvironment(): UiConfig {
            // user.dir is the demo-ui subproject when launched via :demo-ui:run; the
            // demo project's outputs live in the parent. Walk up until we find a
            // gradle.properties (anchors the demo project root).
            val projectDir = Paths.get(
                env("PAYMENTS_PROJECT_DIR", findDemoProjectRoot().toString()),
            ).toAbsolutePath()
            val clientEndpoints = env(
                "PAYMENTS_CLIENT_ENDPOINTS",
                projectDir.resolve("build/gridgain/output/client/client-endpoints.yaml").toString(),
            )
            return UiConfig(
                serverPort = env("PAYMENTS_UI_PORT", "8081").toInt(),
                // Defaults match scripts/create-demo-secrets.sh — change them there and here together.
                mainframeJdbcUrl = env("PAYMENTS_MAINFRAME_JDBC_URL", "jdbc:postgresql://localhost:5432/payments"),
                mainframeUsername = env("PAYMENTS_MAINFRAME_USER", "payments"),
                mainframePassword = env("PAYMENTS_MAINFRAME_PASSWORD", "payments-pw-replace-me"),
                mariaDbJdbcUrl = env("PAYMENTS_MARIADB_JDBC_URL", "jdbc:mariadb://localhost:3306/payments"),
                mariaDbUsername = env("PAYMENTS_MARIADB_USER", "payments"),
                mariaDbPassword = env("PAYMENTS_MARIADB_PASSWORD", "payments-pw-replace-me"),
                clusterName = env("PAYMENTS_GG_CLUSTER", "mainframe-payments-gg8"),
                clientEndpointsFile = Paths.get(clientEndpoints),
                kafkaBootstrapServers = env("PAYMENTS_KAFKA_BOOTSTRAP", "localhost:9092"),
                cdcTopicPrefix = env("PAYMENTS_CDC_TOPIC_PREFIX", "mainframe-to-gg"),
                projectDirectory = projectDir,
            )
        }

        private fun env(name: String, default: String): String =
            System.getenv(name) ?: System.getProperty(name) ?: default

        private fun findDemoProjectRoot(): Path {
            var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (dir != null) {
                if (dir.resolve("gradle.properties").toFile().exists() &&
                    dir.resolve("src/main/resources/demo-config.yaml").toFile().exists()
                ) {
                    return dir
                }
                dir = dir.parent
            }
            return Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        }
    }
}
