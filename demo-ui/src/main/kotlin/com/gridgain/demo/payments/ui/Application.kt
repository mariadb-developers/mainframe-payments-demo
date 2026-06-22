package com.gridgain.demo.payments.ui

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.gridgain.demo.payments.ui.config.UiConfig
import com.gridgain.demo.payments.ui.metrics.GeneratorMetricsService
import com.gridgain.demo.payments.ui.metrics.PrometheusCpuService
import com.gridgain.demo.payments.ui.metrics.WorkloadRatioService
import com.gridgain.demo.payments.ui.routes.bringOnlineRoutes
import com.gridgain.demo.payments.ui.routes.cpuRoutes
import com.gridgain.demo.payments.ui.routes.demoRoutes
import com.gridgain.demo.payments.ui.routes.generatorRoutes
import com.gridgain.demo.payments.ui.routes.gridGainRoutes
import com.gridgain.demo.payments.ui.routes.mainframeRoutes
import com.gridgain.demo.payments.ui.routes.mariaDbRoutes
import com.gridgain.demo.payments.ui.routes.metricsRoutes
import com.gridgain.demo.payments.ui.routes.phaseRoutes
import com.gridgain.demo.payments.ui.routes.tailerRoutes
import com.gridgain.demo.payments.ui.services.BulkLoadService
import com.gridgain.demo.payments.ui.services.ConnectorControlService
import com.gridgain.demo.payments.ui.services.DemoResetService
import com.gridgain.demo.payments.ui.services.GeneratorControlService
import com.gridgain.demo.payments.ui.services.GridGainService
import com.gridgain.demo.payments.ui.services.MainframeProxyService
import com.gridgain.demo.payments.ui.services.MariaDbBulkLoadService
import com.gridgain.demo.payments.ui.services.MariaDbService
import com.gridgain.demo.payments.ui.services.PhaseService
import com.gridgain.demo.payments.ui.tailer.KafkaTailerTap
import com.gridgain.demo.payments.ui.tailer.TailerService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.*
import kotlin.time.Duration.Companion.seconds

fun main() {
    val config = UiConfig.fromEnvironment()
    // DemoAddressFinder discovers client-endpoints.yaml via this system property
    // (or env var GG_DEMO_CLIENT_ENDPOINTS, or CWD-relative build/demo-output/client/...).
    // Set it from UiConfig so we get a stable resolution regardless of launch directory.
    if (System.getProperty("gg.demo.client.endpoints") == null) {
        System.setProperty("gg.demo.client.endpoints", config.clientEndpointsFile.toString())
    }
    val server = embeddedServer(Netty, port = config.serverPort) {
        configurePlugins()
        configureRouting(config)
    }
    server.start(wait = true)
}

fun Application.configurePlugins() {
    install(ContentNegotiation) {
        jackson {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        anyHost()
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 60.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
        }
        exception<NoSuchElementException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (cause.message ?: "Not found")))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Internal server error")),
            )
        }
    }
}

fun Application.configureRouting(config: UiConfig) {
    val tailerService = TailerService()
    val mainframeService = MainframeProxyService(config)
    val mariaDbService = MariaDbService(config)
    val gridGainService = GridGainService(config)
    val phaseService = PhaseService()
    val generatorService = GeneratorControlService(config)
    // Workload descriptor for the phase-6 dashboard's latency subtitle — read once from the
    // generator's ops.yaml at startup, stamped onto every emitted MetricsSnapshot.
    val rwRatio = WorkloadRatioService(config.opsFile, config.generatorScenario).readWriteRatio()
    environment.log.info(
        "Workload R/W ratio for scenario '{}' loaded from {}: {}",
        config.generatorScenario,
        config.opsFile,
        rwRatio ?: "(none — subtitle will fall back)",
    )
    val generatorMetricsService = GeneratorMetricsService(
        kafkaBootstrapServers = config.kafkaBootstrapServers,
        topic = config.metricsTopic,
        rwRatio = rwRatio,
    )
    generatorMetricsService.start()
    val cpuService = PrometheusCpuService(config.prometheusUrl, config.prometheusCpuQuery)
    cpuService.start()
    val cdcSinkControlService = ConnectorControlService(config.kafkaConnectUrl, config.cdcSinkConnectorName)
    val mariaSinkControlService = ConnectorControlService(config.kafkaConnectUrl, config.mariaDbSinkConnectorName)
    val bulkLoadService = BulkLoadService(mainframeService, gridGainService)
    val mariaBulkLoadService = MariaDbBulkLoadService(gridGainService, mariaDbService)
    val resetService = DemoResetService(
        mainframeService = mainframeService,
        mariaDbService = mariaDbService,
        gridGainService = gridGainService,
        phaseService = phaseService,
        generatorService = generatorService,
        cdcSinkControlService = cdcSinkControlService,
        mariaSinkControlService = mariaSinkControlService,
        bulkLoadService = bulkLoadService,
        mariaBulkLoadService = mariaBulkLoadService,
        connectBaseUrl = config.kafkaConnectUrl,
    )
    // Three tailer taps, one per UI panel — each reads from Kafka directly so
    // the panel reflects exactly what the corresponding sink applies:
    //   cdc            (mainframe → GG)  : Debezium-Postgres output, filtered to source='mf'
    //   gg-to-postgres (GG → Postgres)   : publisher's from-gg.* topics (only GG-originated rows)
    //   gg-to-mariadb  (GG → MariaDB)    : publisher's from-(gg|mf).* topics (everything)
    val taps = listOf(
        KafkaTailerTap(
            kafkaBootstrapServers = config.kafkaBootstrapServers,
            topicPatterns = listOf("${java.util.regex.Pattern.quote(config.cdcTopicPrefix)}\\.public\\..*"),
            tailerSource = "cdc",
            sourceFilter = "mf",
            tailerService = tailerService,
        ),
        KafkaTailerTap(
            kafkaBootstrapServers = config.kafkaBootstrapServers,
            topicPatterns = listOf("from-gg\\.public\\..*"),
            tailerSource = "gg-to-postgres",
            sourceFilter = null,
            tailerService = tailerService,
        ),
        KafkaTailerTap(
            kafkaBootstrapServers = config.kafkaBootstrapServers,
            topicPatterns = listOf("from-(gg|mf)\\.public\\..*"),
            tailerSource = "gg-to-mariadb",
            sourceFilter = null,
            tailerService = tailerService,
        ),
    )
    taps.forEach { it.start() }

    environment.monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        taps.forEach { it.close() }
        generatorMetricsService.close()
        cpuService.close()
        mainframeService.close()
        mariaDbService.close()
        gridGainService.close()
    }

    routing {
        route("/api") {
            mainframeRoutes(mainframeService)
            gridGainRoutes(gridGainService)
            mariaDbRoutes(mariaDbService)
            phaseRoutes(phaseService)
            tailerRoutes(tailerService)
            metricsRoutes(generatorMetricsService)
            cpuRoutes(cpuService)
            generatorRoutes(generatorService)
            bringOnlineRoutes(cdcSinkControlService, bulkLoadService, mariaSinkControlService, mariaBulkLoadService)
            demoRoutes(resetService)
            // Connector health for the UI's warning pill — surfaces any FAILED Connect task
            // (connector RUNNING but applying nothing) so a dead sink isn't silent.
            get("/connectors/health") {
                call.respond(
                    com.gridgain.demo.payments.ui.model.ConnectorHealth(
                        failedTasks = ConnectorControlService.listFailedTasks(config.kafkaConnectUrl),
                    ),
                )
            }
            get("/health") {
                call.respond(mapOf("status" to "ok"))
            }
        }
        staticResources("/", "static") {
            default("index.html")
        }
    }
}
