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
    val kafkaConnectUrl: String,
    val cdcSinkConnectorName: String,
    val mariaDbSinkConnectorName: String,
    val projectDirectory: Path,
    val metricsTopic: String,
    val opsFile: Path,
    val generatorScenario: String,
    val generatorNamespace: String,
    val generatorDeploymentName: String,
    val prometheusUrl: String,
    val prometheusCpuQuery: String,
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
            val opsFile = env(
                "PAYMENTS_OPS_FILE",
                projectDir.resolve("src/main/resources/generator/ops.yaml").toString(),
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
                // Dev default targets the broker's EXTERNAL listener (advertises localhost:9094),
                // reachable via the payments-proxy port-forward (scripts/dev-port-forwards.sh).
                // The PLAINTEXT listener (:9092) advertises the in-cluster FQDN, so a laptop
                // consumer bootstrapped there connects but can never fetch (the advertised address
                // is unresolvable off-cluster) and the tailers stay silently empty. In-cluster
                // deployments override this to the internal Kafka service via PAYMENTS_KAFKA_BOOTSTRAP.
                kafkaBootstrapServers = env("PAYMENTS_KAFKA_BOOTSTRAP", "localhost:9094"),
                cdcTopicPrefix = env("PAYMENTS_CDC_TOPIC_PREFIX", "mainframe-to-gg"),
                // Kafka Connect REST API — drives the cdc-sink connector's pause/resume for the
                // phase-2 "bring GridGain online" beat. The toolkit registers the connector under
                // "<cdc_connectors-entry-name>-<connectors[].name>", i.e. the demo-config entry
                // `mainframe-to-gg` + connector `cdc-sink` => `mainframe-to-gg-cdc-sink` (NOT the
                // bare `cdc-sink`). In dev, the payments-proxy exposes Connect at :8083 (scripts/dev-port-forwards.sh).
                kafkaConnectUrl = env("PAYMENTS_KAFKA_CONNECT_URL", "http://localhost:8083"),
                cdcSinkConnectorName = env("PAYMENTS_CDC_SINK_CONNECTOR", "mainframe-to-gg-cdc-sink"),
                // The outbound GG→MariaDB JDBC sink, paused/resumed for the phase-5 beat. NOT yet
                // deployed by the toolkit (tracked as a BLOCKER) — until it is, pause/resume 404s and
                // the demo surfaces that. Default follows the toolkit's "<entry>-<connectors[].name>"
                // convention; confirm the actual name when the sink lands and override here if needed.
                mariaDbSinkConnectorName = env("PAYMENTS_MARIADB_SINK_CONNECTOR", "mainframe-to-gg-gg-to-mariadb"),
                projectDirectory = projectDir,
                // The data generator publishes live throughput/latency to this Kafka topic ~1s
                // (ops.yaml metrics block); GeneratorMetricsService consumes it off kafkaBootstrapServers.
                metricsTopic = env("PAYMENTS_METRICS_TOPIC", "generator-metrics"),
                // The data generator's scenario file — its `scenarios[].read_ratio` becomes the
                // workload descriptor on the phase-6 dashboard's latency panel. Read once at
                // startup; the UI subtitle falls back to "mixed workload" if unparseable.
                opsFile = Paths.get(opsFile),
                // The scenario name dataGenerate runs (ops.yaml scenarios[].name). dataGenerate
                // requires --scenario, so GeneratorControlService passes this.
                generatorScenario = env("PAYMENTS_GENERATOR_SCENARIO", "mainframe-payments-load"),
                // The k8s namespace the distributed generator Deployment lands in (the target GG
                // cluster's namespace). GeneratorControlService deletes prior runs here on a clean
                // stop via `kubectl delete -l gridgain.com/scenario=<scenario>`. Defaults to the
                // GG cluster namespace; matches clusterName for this demo.
                generatorNamespace = env("PAYMENTS_GENERATOR_NAMESPACE", "mainframe-payments-gg8"),
                // The data-generator element's Deployment name (= the data_generators.<name> entry).
                // setPods scales this Deployment via `kubectl scale` in generatorNamespace.
                generatorDeploymentName = env("PAYMENTS_GENERATOR_DEPLOYMENT", "payments-load"),
                // The deployed pg-gke Prometheus, queried for the GG cluster's CPU (the "GG is bored
                // at high load" readout). Dev default targets the payments-proxy's :9090 listener
                // (scripts/dev-port-forwards.sh); in-cluster deployments override to the internal
                // service via PAYMENTS_PROMETHEUS_URL.
                prometheusUrl = env("PAYMENTS_PROMETHEUS_URL", "http://localhost:9090"),
                // PromQL for the GG CPU gauge. sys_CpuLoad is Ignite's per-node CPU gauge (0..1);
                // avg() blends the GG nodes. Tunable without a rebuild if the metric/labels differ.
                prometheusCpuQuery = env("PAYMENTS_PROMETHEUS_CPU_QUERY", "avg(sys_CpuLoad)"),
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
