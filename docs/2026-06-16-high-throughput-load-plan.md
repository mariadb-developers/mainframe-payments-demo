# High-throughput load beat (~10–15k ops/s) + GG CPU panel — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use ggcoder:subagent-driven-development (recommended) or ggcoder:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show GridGain processing ~10–15k ops/s while its CPU stays ~20–30%, by scaling the load tier onto a dedicated autoscaling node pool, simplifying the UI to a pods-only control, and adding a live GG-CPU readout.

**Architecture:** Three independently-shippable phases. **Phase A1 (GG CPU panel)** and **Phase A2 (pods-only control)** are Track A (demo repo, `main`) and need no toolkit change. **Phase B1 (generator deployable element)** is Track B (plugin repo, feature branch per the git-branch-policy) and is what unlocks the real load-tier scale-out. A1 can land first for an immediate "GG is bored" readout; A2's UI is usable immediately and gains real headroom once B1 lands.

**Tech Stack:** Kotlin/Ktor + Kotlin coroutines (demo backend), React 18 + TypeScript + Vite + Tailwind (demo frontend), Kotlin/Gradle plugin + JSONSchema (toolkit), Prometheus HTTP API (`sys_CpuLoad`), GKE node pools.

**Spec:** [docs/2026-06-16-high-throughput-load-design.md](2026-06-16-high-throughput-load-design.md). **Cross-track log:** [docs/track-coordination.md](track-coordination.md).

**Conventions for every task:** TDD (failing test first), DRY, YAGNI, frequent commits. Demo repo commits go to `main`; toolkit commits go to a feature branch. Demo backend tests: `./gradlew :demo-ui:test`. Frontend has **no test harness** (by decision) — verify with `npx tsc --noEmit` from `demo-ui/frontend/` + manual HMR check. Never use `cd <repo> && git`; use `git -C <repo>`.

---

## Phase A1 — GG CPU panel (Track A · demo `main` · independent · ship first)

**Pattern to mirror:** the existing generator-metrics path — `demo-ui/.../metrics/GeneratorMetricsService.kt` (+ `MetricsSnapshot.kt`), `routes/MetricsRoutes.kt` (WS), `frontend/src/hooks/useMetricsWebSocket.ts`, `components/MetricsPanel.tsx`. The CPU panel is the same shape with Prometheus as the source instead of Kafka.

**Files:**
- Create: `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/CpuSnapshot.kt`
- Create: `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/PrometheusCpuService.kt`
- Create: `demo-ui/src/test/kotlin/com/gridgain/demo/payments/ui/metrics/PrometheusCpuServiceTest.kt`
- Create: `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/routes/CpuRoutes.kt`
- Modify: `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/config/UiConfig.kt` (add `prometheusUrl`)
- Modify: `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/Application.kt` (construct service + register route)
- Modify: `scripts/dev-port-forwards.sh` (add `:9090` Prometheus forward)
- Create: `demo-ui/frontend/src/hooks/useCpuWebSocket.ts`
- Modify: `demo-ui/frontend/src/components/MetricsPanel.tsx` (add CPU gauge) and `frontend/src/App.tsx` (mount it)

### Task A1.1 — CpuSnapshot model + PrometheusCpuService (parse)

**Files:** Create `metrics/CpuSnapshot.kt`, `metrics/PrometheusCpuService.kt`, `test/.../metrics/PrometheusCpuServiceTest.kt`

- [ ] **Step 1: Write the failing test** — `PrometheusCpuServiceTest.kt`. Parse a Prometheus `query` JSON response for `avg(sys_CpuLoad)` and convert the 0..1 load to a percent. Sample real Prometheus shape:

```kotlin
package com.gridgain.demo.payments.ui.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PrometheusCpuServiceTest {
    private val service = PrometheusCpuService(prometheusUrl = "http://unused:9090")

    @Test
    fun `parses avg sys_CpuLoad scalar result into a percent`() {
        // Prometheus /api/v1/query with `avg(sys_CpuLoad)` returns a single vector sample.
        val json = """
            {"status":"success","data":{"resultType":"vector","result":[
              {"metric":{},"value":[1700000000.0,"0.214"]}]}}
        """.trimIndent()
        val s = service.parse(json)!!
        assertEquals(21.4, s.cpuPercent, 1e-6)   // 0.214 -> 21.4%
    }

    @Test
    fun `null on empty result, error status, or malformed json`() {
        assertNull(service.parse("""{"status":"success","data":{"resultType":"vector","result":[]}}"""))
        assertNull(service.parse("""{"status":"error","error":"boom"}"""))
        assertNull(service.parse("not json"))
        assertNull(service.parse(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails** — `./gradlew :demo-ui:test --tests "com.gridgain.demo.payments.ui.metrics.PrometheusCpuServiceTest"` → FAIL (`PrometheusCpuService`/`CpuSnapshot` unresolved).

- [ ] **Step 3: Write minimal implementation.**

`CpuSnapshot.kt`:
```kotlin
package com.gridgain.demo.payments.ui.metrics

/** GG cluster CPU readout for the /api/cpu WebSocket. cpuPercent is 0..100 (avg across GG nodes). */
data class CpuSnapshot(val updatedAtMs: Long, val cpuPercent: Double, val active: Boolean)
```

`PrometheusCpuService.kt` — the `parse` seam now; the poll loop in A1.3. Declare the **full constructor up front** (`cpuQuery` + an injectable `clockMs` clock, both defaulted) so arity doesn't change between tasks — this mirrors `GeneratorMetricsService`'s `clockMs: () -> Long` injectable-clock pattern (there is **no** `stamp()` helper there to copy). `parse` returns an **unstamped** snapshot (`updatedAtMs = 0L`); the A1.3 poll loop stamps it via `clockMs`. Use Jackson (already a dep):
```kotlin
package com.gridgain.demo.payments.ui.metrics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

class PrometheusCpuService(
    private val prometheusUrl: String,
    private val cpuQuery: String = "avg(sys_CpuLoad)",
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val mapper = ObjectMapper()

    /**
     * Parses a Prometheus /api/v1/query vector response; returns CPU% (0..100) or null if absent.
     * updatedAtMs is left 0L here (unstamped) — the poll loop (A1.3) stamps it via clockMs.
     */
    internal fun parse(json: String?): CpuSnapshot? {
        if (json.isNullOrBlank()) return null
        val root: JsonNode = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        if (root.path("status").asText() != "success") return null
        val first = root.path("data").path("result").firstOrNull() ?: return null
        val load = first.path("value").path(1).asText(null)?.toDoubleOrNull() ?: return null
        return CpuSnapshot(updatedAtMs = 0L, cpuPercent = load * 100.0, active = true)
    }
}
```
The A1.1 test constructs `PrometheusCpuService(prometheusUrl = "http://unused:9090")` — the defaulted `cpuQuery`/`clockMs` keep that call valid through A1.3.

- [ ] **Step 4: Run test to verify it passes** — same command → PASS.

- [ ] **Step 5: Commit**
```bash
git -C mainframe-payments-demo add demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/CpuSnapshot.kt \
  demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/PrometheusCpuService.kt \
  demo-ui/src/test/kotlin/com/gridgain/demo/payments/ui/metrics/PrometheusCpuServiceTest.kt
git -C mainframe-payments-demo commit -m "feat(demo-ui): PrometheusCpuService parse of avg(sys_CpuLoad)"
```

### Task A1.2 — Config + port-forward for Prometheus

**Files:** Modify `config/UiConfig.kt`, `scripts/dev-port-forwards.sh`

- [ ] **Step 1: Add `prometheusUrl` to UiConfig.** Mirror the existing `env("PAYMENTS_…", default)` fields (e.g. `kafkaConnectUrl`). Add field `prometheusUrl: String` and in the loader: `prometheusUrl = env("PAYMENTS_PROMETHEUS_URL", "http://localhost:9090")`. Also add `prometheusCpuQuery = env("PAYMENTS_PROMETHEUS_CPU_QUERY", "avg(sys_CpuLoad)")` so the metric/labels are tunable without a rebuild (see spec open item on `sys_CpuLoad` labels).

- [ ] **Step 2a: Confirm the Prometheus service name** (hard checkbox — single point of failure for the whole A1 manual verify): `kubectl get svc -n gg-prometheus-grafana`. Use the actual service name in the next step (the monitor deploy created `prometheus`/`grafana` services, but verify).
- [ ] **Step 2b: Add the `:9090` forward.** In `scripts/dev-port-forwards.sh`, append to the `FORWARDS=(...)` array (substitute the confirmed service name):
```
  "gg-prometheus-grafana|svc/prometheus|9090:9090|Prometheus (GG CPU panel)"
```

- [ ] **Step 3: Verify** — `./gradlew :demo-ui:compileKotlin` succeeds; `scripts/dev-port-forwards.sh restart` then `scripts/dev-port-forwards.sh status` shows `:9090 UP`.

- [ ] **Step 4: Commit**
```bash
git -C mainframe-payments-demo add demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/config/UiConfig.kt scripts/dev-port-forwards.sh
git -C mainframe-payments-demo commit -m "feat(demo-ui): PAYMENTS_PROMETHEUS_URL config + :9090 dev forward"
```

### Task A1.3 — CPU WebSocket route (poll loop + wiring)

**Files:** Modify `metrics/PrometheusCpuService.kt` (add poll-to-Flow), create `routes/CpuRoutes.kt`, modify `Application.kt`

- [ ] **Step 1 (test):** Add a test to `PrometheusCpuServiceTest` for the `idle()` snapshot — `active=false, cpuPercent=0.0`, `updatedAtMs` stamped from the injected `clockMs` (construct the service with a fixed `clockMs = { 1700000000000L }` and assert). This tests the pure `idle()` helper + the injected clock; do NOT test the HTTP loop. (There is no `stamp()` method on `GeneratorMetricsService` to mirror — the pattern being reused is its `clockMs` constructor param.)

- [ ] **Step 2:** Run → FAIL.

- [ ] **Step 3:** Implement in `PrometheusCpuService`: a `subscribe(): SharedFlow<CpuSnapshot>` + `start()`/`close()` background poller (daemon thread, ~2s cadence) that GETs `"$prometheusUrl/api/v1/query?query=" + URLEncoder.encode(cpuQuery)`, runs `parse`, stamps `updatedAtMs`, `tryEmit`s; on failure emits `idle()`. Mirror `GeneratorMetricsService` (replay=1, retry-with-backoff). Use `java.net.http.HttpClient` (JDK built-in) for the GET. Then `CpuRoutes.kt` mirrors `MetricsRoutes.kt`:
```kotlin
fun Route.cpuRoutes(service: PrometheusCpuService) {
    val mapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
    }
    webSocket("/cpu") {
        val job = launch { service.subscribe().collect { send(Frame.Text(mapper.writeValueAsString(it))) } }
        try { for (frame in incoming) { } } finally { job.cancel() }
    }
}
```
In `Application.kt`: construct `PrometheusCpuService(config.prometheusUrl, config.prometheusCpuQuery)`, call `.start()`, register `cpuRoutes(cpuService)` inside the `route("/api")` block next to `metricsRoutes(...)`, and `.close()` it in the shutdown hook (mirror `generatorMetricsService`).

- [ ] **Step 4:** `./gradlew :demo-ui:test` → PASS; manual: with forwards up + a load running, `wscat`/browser hits `ws://localhost:8081/api/cpu` and sees `{"cpu_percent":~20,...}` (or `/usr/bin/curl "http://localhost:9090/api/v1/query?query=avg(sys_CpuLoad)"` to confirm the source).

- [ ] **Step 5: Commit** — `feat(demo-ui): /api/cpu WebSocket streaming avg GG CPU from Prometheus`

### Task A1.4 — Frontend CPU gauge

**Files:** create `frontend/src/hooks/useCpuWebSocket.ts`, modify `frontend/src/components/MetricsPanel.tsx`, `frontend/src/App.tsx`

- [ ] **Step 1:** `useCpuWebSocket.ts` mirrors `useMetricsWebSocket.ts` (connect to `/api/cpu`, expose `{ cpuPercent, connected }`, history for a sparkline; reuse the same reconnect logic).

- [ ] **Step 2a (prereq):** `Sparkline` in `MetricsPanel.tsx` currently takes only `{ values, stroke }` and computes `max` from the values. Add an optional `max?: number` prop — when provided, use it as the y-scale ceiling; when omitted, keep today's auto-zoom (`Math.max(1, ...values)`). Without this, Step 2b's `max={100}` is a `tsc` type error (the only frontend gate).
- [ ] **Step 2b:** Add a third `Metric` row to `MetricsPanel.tsx` — "GG cluster CPU", value `${cpu.toFixed(0)}%`, stroke a distinct color, sparkline with `max={100}` so CPU reads against full scale (not auto-zoom). Wire the hook in `App.tsx` and pass it to `MetricsPanel` alongside the existing metrics stream; keep it visible in phase 6 (same `visible` gate as the tps/latency panel).

- [ ] **Step 3:** `npx tsc --noEmit` (from `demo-ui/frontend/`) → clean. Manual: restart demo-ui backend, refresh `:3000`, run load → the CPU gauge tracks ~20–30% while tps climbs.

- [ ] **Step 4: Commit** — `feat(demo-ui): GG CPU gauge on the metrics panel`

**Phase A1 done = a shippable "GG is bored" readout, no toolkit dependency.**

---

## Phase A2 — Pods-only generator control (Track A · demo `main`)

Drop the ops/sec total in favour of a pods stepper; pin per-pod rate to the latency ceiling so each pod runs flat-out. Keeps working today (templated `ops.yaml`); when B1 lands, switch the launch to the first-class `--replicas` lever.

**Files:**
- Modify: `demo-ui/.../services/GeneratorControlService.kt` (+ its test if present) — `setPods(pods)` / fixed per-pod ceiling
- Modify: `demo-ui/.../routes/GeneratorRoutes.kt`, `.../model/*` (`SetLoadRequest` → `SetPodsRequest`), `frontend/src/api/client.ts` + types
- Modify: `frontend/src/components/LoadSlider.tsx`, `frontend/src/App.tsx`

### Task A2.1 — Backend pods-only

- [ ] **Step 1 (test):** Create `demo-ui/src/test/.../services/GeneratorControlServiceTest.kt` (none exists today). Test `setPods(n)` writes a runtime ops with `rate.ops_per_second = PER_POD_CEILING` (a high constant, e.g. 100_000, so the `RateLimiter` never throttles and each pod runs at its latency limit) and `distribution.replicas = n`; `setPods(0)` stops. **Visibility:** `writeRuntimeOps(...)` and the companion consts (`MAX_PODS`, `PER_POD_CEILING`, `DEFAULT_PARTITION_COUNT`) are `private` today — widen them to `internal` as part of this step so the test compiles against them (mirrors `parse` being `internal`). Pin the stopped-state assertion explicitly: `setPods(0)` must return `running=false` with `replicas` reported as **0** (not 1) so the UI label reads "stopped", not "1 pods".

- [ ] **Step 2:** Run `./gradlew :demo-ui:test --tests "*GeneratorControlServiceTest"` → FAIL.

- [ ] **Step 3:** Add `setPods(replicas: Int)` calling the existing template/launch path with `perPodOps = PER_POD_CEILING` (no `ceilDiv`), `replicas` clamped to `[0, MAX_PODS]` (0 → stop). **Reconcile the clamp:** replace the existing `MAX_REPLICAS = 64` with a single `MAX_PODS = 30` used by the backend clamp, the UI stepper (A2.2), and `max_replicas` in config (A3) — one source of truth. (Pods beyond the pool's autoscale budget just go Pending; 30 matches the ~15k headroom target.) Add `PER_POD_CEILING` with a comment that it's the unthrottled per-pod rate. Delete `setLoad`/`SetLoadRequest` if nothing else references them (YAGNI).

- [ ] **Step 4:** `./gradlew :demo-ui:test` → PASS.

- [ ] **Step 5:** Route + model: add `SetPodsRequest(pods: Int)`; `post("/pods") { call.respond(service.setPods(call.receive<SetPodsRequest>().pods)) }`. Keep `GET /generator` returning `GeneratorState` (now `replicas` is the meaningful field; `targetOpsPerSecond` becomes derived/`replicas * ~500` estimate or dropped — keep it as an estimate field for the label).

- [ ] **Step 6: Commit** — `feat(demo-ui): pods-only generator control (per-pod rate pinned to ceiling)`

### Task A2.2 — Frontend pods-only

- [ ] **Step 1:** In `LoadSlider.tsx`, remove the ops range/number inputs and the Off button's rate semantics; keep the **pods stepper** (0…`MAX_PODS`) where 0 = stopped. **Bump the frontend `MAX_PODS` from 8 to 30** to match the backend `MAX_PODS` (A2.1 Step 3) and config `max_replicas` (A3). Label: `${pods === 0 ? 'stopped' : `${running ? '●' : '…'} ${pods} pods ≈ ${pods*500} ops/s`}`. Call `generatorApi.setPods(pods)` (add to `api/client.ts`; drop the old `setLoad`/`SetLoadRequest` types). Keep the `resetSignal` re-hydrate from the earlier reset fix.

- [ ] **Step 2:** `npx tsc --noEmit` clean; manual: stepper changes pod count; metrics panel aggregate (already summed) climbs with pods.

- [ ] **Step 3: Commit** — `feat(demo-ui): replace ops slider with pods-only stepper`

**Note:** until B1 lands, pods still schedule onto `default-pool` and contend past ~8 — A2 is UI-complete but the *throughput ceiling* only lifts after B1.

---

## Phase B1 — Generator as a deployable element (Track B · plugin · feature branch)

> **Ownership:** the toolkit owns the element's internal design (field names, class layout). These tasks state the **contract + tests the demo needs** and the element pattern to follow; B fills exact internals. Branch: `feat/generator-element` (per git-branch-policy). Coordinate via a READY entry in `track-coordination.md`.

**Pattern to mirror:** an existing element end-to-end — `databases` or `monitors`: JSONSchema (`src/main/resources/schema/<x>.schema.json`) → `ConfiguredX` DTO → `XSpecAssembler` → `deployment.yaml` additions → `deployX`/`teardownX` task in `plugin/tasks/`. Node-pool-per-element mirrors the `wp-<element>` provisioning the database/monitor deploys already do. Generator dispatch internals: `core/datagen/InClusterDataGenerateAction.kt`, `DistributedDataGeneratorManifestWriter.kt`, `plugin/tasks/DataGenerateTask.kt`.

### Task B1.1 — Schema + migration (TDD)

**Files:** new `schema/<generator>.schema.json`, edit `schema/configuration.schema.json`, bump `core/configuration/ConfiguredState.kt` `CURRENT_SCHEMA_VERSION` (currently 13 → 14), add `core/configuration/MigrateV13toV14.kt`, register in `ConfigMigration.kt` runner, add a case to `ConfigMigrationTest.kt`.

- [ ] **Step 1 (test):** Add `ConfigMigrationTest` case: a v13 config (no generator element) migrates to v14 cleanly and stays schema-valid (additive migration → no field moves; the migration just bumps the version and, if the element is required-with-default, seeds an empty map). Assert `schema_version == 14` and `validateDemoConfiguration`-equivalent validation passes.
- [ ] **Step 2:** Run the plugin test → FAIL.
- [ ] **Step 3:** Add the new top-level key to `configuration.schema.json` (`additionalProperties: { $ref: "<generator>.schema.json" }`, `default: {}`), author `<generator>.schema.json` (fields: `infrastructure`, target `cluster`, `k8s_node_pool_template`, `scenario` (ops/data refs), `replicas`/`max_replicas`, `per_pod_rate` or an `unbounded` flag; record sensible `default`s per the comprehensive-config rule). Bump `CURRENT_SCHEMA_VERSION` to 14; implement `MigrateV13toV14` (additive); register it.
- [ ] **Step 4:** Plugin test suite → PASS; `validateDemoConfiguration` against every workspace demo-config stays green.
- [ ] **Step 5: Commit** (feature branch) — `feat(schema): data-generator element type + v13→v14 migration`

### Task B1.2 — ConfiguredX + SpecAssembler + node pool + placement

**Files:** `core/configuration/Configured<Generator>.kt`, `core/.../<Generator>SpecAssembler.kt`, `deployment.yaml` schema additions, `core/datagen/DistributedDataGeneratorManifestWriter.kt`. **Reuse the existing shared placement helper `core/infrastructure/WorkloadScheduling.kt`** — it already centralizes `poolName(name) = "wp-$name"` and `forElement(name)` (nodeSelector keyed on `gridgain-demo/workload` + tolerations + anti-affinity). Do NOT hand-roll a nodeSelector in the manifest writer — the DB/monitor assemblers go through this helper, and duplicating it violates the workspace DRY rule.

- [ ] **Step 1 (test):** SpecAssembler unit test — given a generator entry referencing `k8s_node_pool_template: generator-gke-pool` + cluster, the assembled spec carries the resolved pool (machine type, autoscaling min/max) and the generator Deployment manifest carries the placement that `WorkloadScheduling.forElement("<generator>")` emits (the `gridgain-demo/workload` nodeSelector + tolerations) and the pool name `wp-<generator>`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement `Configured<Generator>` (no nullable types without approval; no parse-time defaults — JSONSchema defaults only), the `SpecAssembler` (mirror `MonitorSpecAssembler`/database assembler resolving its node pool via `WorkloadScheduling`), and have `DistributedDataGeneratorManifestWriter` apply `WorkloadScheduling.forElement(<generator>)` to the Deployment pod spec so pods land on `wp-<generator>`. Per-pod rate = unbounded ceiling when the element/flag says so.
- [ ] **Step 4:** Plugin tests → PASS.
- [ ] **Step 5: Commit** — `feat(datagen): generator element spec assembly + dedicated node pool placement`

### Task B1.3 — deploy/teardown tasks (+ pods/rate first-class)

**Files:** `plugin/tasks/Deploy<Generator>Task.kt`, `Teardown<Generator>Task.kt`, register in `GridGainDemoPlugin.kt`; optionally extend `DataGenerateTask` with `--replicas`/`--rate`.

- [ ] **Step 1 (test):** Task-level test (mirror an existing deploy-task test): the deploy renders + would-apply the node-pool create (`wp-<generator>`) and the generator Deployment with placement; teardown deletes both by name/label. `-PdryRun=true` renders without applying.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement the tasks (mirror `DeployDatabaseTask`/`DeployMonitorTask`: ensure node pool → apply manifests → wait Ready; teardown reverses). Add `--replicas`/`--rate` to `DataGenerateTask` so the demo backend can stop templating a runtime ops.yaml (the §16 nicety) — sets pods + per-pod rate directly.
- [ ] **Step 4:** Plugin tests → PASS.
- [ ] **Step 5: Commit** — `feat(plugin): deploy/teardown data-generator element + --replicas/--rate override`

### Task B1.4 — Docs + READY

- [ ] **Step 1:** Update `gridgain-demo-gradle-plugin/.claude/skills/gridgain-demo-toolkit/SKILL.md` (new task rows + element type + `CURRENT_SCHEMA_VERSION` → 14) and add an element-reference doc (mirror `docs/cdc-connectors.md`). Bump the skill's *Last updated*.
- [ ] **Step 2:** Post a **READY (B→A)** entry in `track-coordination.md`: pinnable commit, required demo-config edits (the generator `node_pool_templates` + element instance), verification steps. (Per the cross-track write rule, this is B's one permitted write in the demo repo.)
- [ ] **Step 3: Commit** (feature branch) — `docs: data-generator element usage + READY`

---

## Phase A3 — Integrate B1 into the demo (Track A · demo `main`, after B1 READY)

- [ ] **Step 1:** Re-pin the demo's `includeBuild` plugin checkout to B1's commit. Run `./gradlew validateDemoConfiguration` (migrates v13→v14) → green.
- [ ] **Step 2:** Add to `demo-config.yaml`: `node_pool_templates.generator-gke-pool` (e2-standard-8, autoscale 1→4) + the new generator element instance referencing it, the GG cluster, and the `mainframe-payments-load` scenario, `max_replicas: 30`.
- [ ] **Step 3:** Switch `GeneratorControlService` from templating a runtime ops.yaml to the plugin's `dataGenerate --replicas` (or the deploy/teardown element tasks) per B1's interface; update/keep the A2.1 test.
- [ ] **Step 4:** Deploy the generator element; step pods to ~24 in the UI.
- [ ] **Step 5 (verify E2E):** UI shows ~10–15k tps (aggregate) with the GG CPU gauge ~20–30%; generator pods land on `wp-generator-*` (not default-pool: `kubectl get pods -n <ns> -o wide`); `teardown` removes the generator pool with no residue.
- [ ] **Step 6: Commit** — `feat(demo): consume data-generator element; pods-only at 10-15k` and post **INTEGRATED** in `track-coordination.md`.

---

## Risks / notes
- **Autoscaler lag:** stepping pods past current node capacity leaves pods Pending until a node joins (~1–3 min). Acceptable; note in presenter docs.
- **`sys_CpuLoad` labels:** confirm the per-node label for `avg(...)`; the query is config-driven (`PAYMENTS_PROMETHEUS_CPU_QUERY`) so it's tunable without a rebuild.
- **e2 quota:** generator pool draws from the us-west1 e2 quota (72; ~24 used). Autoscale max 4 × e2-standard-8 = 32 vCPU fits. C3 stays reserved for GG.
- **Composite-build gotcha:** any `./gradlew` invocation rebuilds included JARs — restart the plugin UI / demo-ui backend after builds if they're running.
