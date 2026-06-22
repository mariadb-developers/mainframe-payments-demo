# Phase 6 Performance Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use ggcoder:subagent-driven-development (recommended) or ggcoder:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the demo enters phase 6, replace the three data-plane panels (Mainframe / GG / MariaDB) and the three connector tailers with a centered three-panel performance dashboard (Throughput, GG Latency, GG CPU) sized to ~75% of the body width.

**Architecture:** Frontend-mostly change. The data streams already exist (`/api/metrics` for tps + latency from the data generator's Kafka topic; `/api/cpu` for `avg(sys_CpuLoad)` from Prometheus). The only new backend work is parsing the scenario's existing `read_ratio` field from `ops.yaml` at startup and surfacing it as an immutable string on `MetricsSnapshot`. The frontend gets a new `PerfDashboard` component (big number + ~30s sparkline per metric) and a small `App.tsx` visibility-map update; the existing `MetricsPanel` is retired in the same change.

**Tech Stack:** Kotlin/Ktor backend (JUnit Jupiter + kotlin-test), React 18 + TypeScript + Vite frontend, SnakeYAML for parsing (already on the classpath via SnakeYAML 1.33 forced version).

**Reference:** [`docs/2026-06-22-phase6-performance-dashboard-design.md`](./2026-06-22-phase6-performance-dashboard-design.md) — approved spec.

**Known deviation from spec:** The spec called for "A `PerfDashboard` test renders the three Metric blocks given fixture streams." This repo has no frontend test framework set up (no Vitest, Jest, or @testing-library). Adding one to ship one component test is disproportionate. The plan substitutes a structured manual smoke (Task 7) for the frontend. Backend tests (Task 1) are fully TDD'd against the existing JUnit Jupiter + kotlin-test infrastructure.

---

## File Structure

**Created:**
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/WorkloadRatioService.kt` — parses `read_ratio` from `ops.yaml` once at startup, returns the formatted `"<reads>:<writes>"` percentage string (or `null` on any failure).
- `demo-ui/src/test/kotlin/com/gridgain/demo/payments/ui/metrics/WorkloadRatioServiceTest.kt` — happy path, missing file, bad YAML, missing scenario, missing field.
- `demo-ui/src/test/resources/workload-ratio/valid-ops.yaml`, `missing-scenario-ops.yaml`, `missing-ratio-ops.yaml` — fixture files.
- `demo-ui/frontend/src/components/Sparkline.tsx` — the SVG sparkline extracted from `MetricsPanel.tsx` so `PerfDashboard` can reuse it (it's the same visual primitive at a different size).
- `demo-ui/frontend/src/components/PerfDashboard.tsx` — three-panel horizontal dashboard for phase 6, replaces `MetricsPanel`.

**Modified:**
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/MetricsSnapshot.kt` — add `val rwRatio: String? = null` field. Wire name is `r_w_ratio`: the existing `MetricsSnapshot` docstring documents that the WebSocket route re-serializes the class to snake_case for the frontend (`types/api.ts` confirms — every existing field is snake_case there).
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/GeneratorMetricsService.kt` — accept the ratio string (not the service) via constructor, default `null`. Stamp it inside `aggregate(...)` and `idle()` — both are the only emit sources, so the single `flow.tryEmit` site inherits the field automatically.
- `demo-ui/src/test/kotlin/com/gridgain/demo/payments/ui/metrics/GeneratorMetricsServiceTest.kt` — extend with a case asserting `rwRatio` propagates onto emitted snapshots.
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/Application.kt` — wire `WorkloadRatioService(config.opsFile, config.generatorScenario).readWriteRatio()` into the `GeneratorMetricsService` constructor at startup; log the loaded value for the smoke check.
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/config/UiConfig.kt` — add **one** new field: `opsFile: Path`. Default resolves via the existing `projectDirectory` pattern (matches `clientEndpointsFile`'s handling of the `:demo-ui:run` working-directory mismatch). **Do NOT add a scenario-name field — `generatorScenario` already exists** (line ~78, reads `PAYMENTS_GENERATOR_SCENARIO`). Reuse it.
- `demo-ui/frontend/src/types/api.ts` — extend the existing `MetricsSnapshot` interface (line ~74) with `r_w_ratio: string | null`. The `useMetricsWebSocket` hook imports this type, so no separate hook edit needed.
- `demo-ui/frontend/src/App.tsx` — visibility map adds `perfDashboard: phase >= 6`, gates data panels + tailers + flow animations on `phase < 6`, renders `<PerfDashboard ...>` where today's `<MetricsPanel ...>` lives.
- `CLAUDE.md` — §3 phase-6 row + §5 high-load suppression paragraph.

**Deleted (in same change after smoke):**
- `demo-ui/frontend/src/components/MetricsPanel.tsx` — replaced by `PerfDashboard`; no other consumers (`grep MetricsPanel demo-ui/frontend/src` should return only `App.tsx`'s import line at start of Task 5).

---

## Task 1 — Backend: WorkloadRatioService (TDD)

**Files:**
- Create: `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/WorkloadRatioService.kt`
- Create: `demo-ui/src/test/kotlin/com/gridgain/demo/payments/ui/metrics/WorkloadRatioServiceTest.kt`
- Create: `demo-ui/src/test/resources/workload-ratio/valid-ops.yaml`
- Create: `demo-ui/src/test/resources/workload-ratio/missing-scenario-ops.yaml`
- Create: `demo-ui/src/test/resources/workload-ratio/missing-ratio-ops.yaml`

- [ ] **Step 1: Write the fixture files**

`demo-ui/src/test/resources/workload-ratio/valid-ops.yaml`:
```yaml
schema_version: 4
scenarios:
  - name: mainframe-payments-load
    rate: { kind: constant, ops_per_second: 2000 }
    read_ratio: 0.20
  - name: some-other-scenario
    read_ratio: 0.75
```

`demo-ui/src/test/resources/workload-ratio/missing-scenario-ops.yaml`:
```yaml
schema_version: 4
scenarios:
  - name: not-the-one-we-want
    read_ratio: 0.20
```

`demo-ui/src/test/resources/workload-ratio/missing-ratio-ops.yaml`:
```yaml
schema_version: 4
scenarios:
  - name: mainframe-payments-load
    rate: { kind: constant, ops_per_second: 2000 }
```

- [ ] **Step 2: Write the failing test**

`demo-ui/src/test/kotlin/com/gridgain/demo/payments/ui/metrics/WorkloadRatioServiceTest.kt`:
```kotlin
package com.gridgain.demo.payments.ui.metrics

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkloadRatioServiceTest {

    private fun fixture(name: String): Path =
        Path.of("src/test/resources/workload-ratio").resolve(name)

    @Test
    fun `valid ops returns formatted reads-to-writes percent`() {
        val s = WorkloadRatioService(fixture("valid-ops.yaml"), scenarioName = "mainframe-payments-load")
        assertEquals("20:80", s.readWriteRatio())
    }

    @Test
    fun `missing file returns null`(@TempDir tmp: Path) {
        val s = WorkloadRatioService(tmp.resolve("does-not-exist.yaml"), scenarioName = "mainframe-payments-load")
        assertNull(s.readWriteRatio())
    }

    @Test
    fun `unparseable yaml returns null`(@TempDir tmp: Path) {
        val bad = tmp.resolve("bad.yaml").apply { writeText(": : : not yaml") }
        val s = WorkloadRatioService(bad, scenarioName = "mainframe-payments-load")
        assertNull(s.readWriteRatio())
    }

    @Test
    fun `missing scenario returns null`() {
        val s = WorkloadRatioService(fixture("missing-scenario-ops.yaml"), scenarioName = "mainframe-payments-load")
        assertNull(s.readWriteRatio())
    }

    @Test
    fun `missing read_ratio returns null`() {
        val s = WorkloadRatioService(fixture("missing-ratio-ops.yaml"), scenarioName = "mainframe-payments-load")
        assertNull(s.readWriteRatio())
    }

    @Test
    fun `read_ratio of 0_75 formats as 75 to 25`() {
        val s = WorkloadRatioService(fixture("valid-ops.yaml"), scenarioName = "some-other-scenario")
        assertEquals("75:25", s.readWriteRatio())
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo
./gradlew :demo-ui:test --tests 'com.gridgain.demo.payments.ui.metrics.WorkloadRatioServiceTest'
```

Expected: compile error — `WorkloadRatioService` not defined.

- [ ] **Step 4: Write minimal implementation**

`demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/WorkloadRatioService.kt`:
```kotlin
package com.gridgain.demo.payments.ui.metrics

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parses the data generator's scenario file once to surface the read/write split as a workload
 * descriptor for the phase-6 dashboard. Returns "<reads>:<writes>" percentages (e.g. "20:80")
 * or null on any failure — the UI's latency-panel subtitle falls back to "mixed workload" when null.
 *
 * The ratio is a property of the scenario config, not measured telemetry, so this is read once
 * at startup and never refreshed.
 */
class WorkloadRatioService(
    private val opsFile: Path,
    private val scenarioName: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun readWriteRatio(): String? {
        if (!Files.isRegularFile(opsFile)) {
            log.warn("Workload ratio: ops file not found at {} — subtitle will fall back", opsFile)
            return null
        }
        val parsed: Any? = try {
            Files.newBufferedReader(opsFile).use { Yaml().load<Any?>(it) }
        } catch (e: Exception) {
            log.warn("Workload ratio: failed to parse {} — {}", opsFile, e.message)
            return null
        }
        val scenarios = (parsed as? Map<*, *>)?.get("scenarios") as? List<*> ?: run {
            log.warn("Workload ratio: no 'scenarios' list in {}", opsFile)
            return null
        }
        val scenario = scenarios
            .filterIsInstance<Map<*, *>>()
            .firstOrNull { it["name"] == scenarioName } ?: run {
                log.warn("Workload ratio: scenario '{}' not in {}", scenarioName, opsFile)
                return null
            }
        val readRatio = (scenario["read_ratio"] as? Number)?.toDouble() ?: run {
            log.warn("Workload ratio: scenario '{}' has no read_ratio", scenarioName)
            return null
        }
        val readPct = (readRatio * 100).toInt().coerceIn(0, 100)
        return "$readPct:${100 - readPct}"
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :demo-ui:test --tests 'com.gridgain.demo.payments.ui.metrics.WorkloadRatioServiceTest'
```

Expected: 6 tests, all PASS.

- [ ] **Step 6: Commit**

```bash
git add demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/WorkloadRatioService.kt \
        demo-ui/src/test/kotlin/com/gridgain/demo/payments/ui/metrics/WorkloadRatioServiceTest.kt \
        demo-ui/src/test/resources/workload-ratio
git commit -m "feat(ui-backend): parse read/write ratio from ops.yaml at startup

For the phase-6 dashboard's latency subtitle. Returns '<reads>:<writes>'
percentage or null on any failure (UI subtitle falls back to 'mixed workload').
Read once, not streamed.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2 — Backend: surface `rwRatio` on `MetricsSnapshot`

**Files:**
- Modify: `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/MetricsSnapshot.kt`
- Modify: `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/GeneratorMetricsService.kt`
- Modify: `demo-ui/src/test/kotlin/com/gridgain/demo/payments/ui/metrics/GeneratorMetricsServiceTest.kt`

- [ ] **Step 1: Confirm JSON shape by reading the test you'll extend**

```bash
grep -nE 'aggregate|idle|emit|emitSnapshot|MetricsSnapshot\(' \
  demo-ui/src/test/kotlin/com/gridgain/demo/payments/ui/metrics/GeneratorMetricsServiceTest.kt | head
```
Confirm the test names that construct or assert on `MetricsSnapshot`. You'll extend whichever covers `aggregate()` (the throughput-weighted combiner) — that's where the field needs to propagate.

- [ ] **Step 2: Write the failing test addition**

In `GeneratorMetricsServiceTest.kt`, add a new test asserting that an `rwRatio` passed via the constructor flows through `aggregate(...)` onto the returned snapshot:

```kotlin
@Test
fun `aggregate stamps rwRatio on the returned snapshot`() {
    val service = GeneratorMetricsService(
        kafkaBootstrapServers = "n/a",
        topic = "n/a",
        rwRatio = "20:80",  // <-- new constructor param
    )
    val now = 1_000L
    val a = MetricsSnapshot(
        updatedAtMs = now,
        observedTps = 1000.0, avgLatencyMs = 1.0,
        totalOps = 1000L, errorCount = 0L,
        targetTps = 2000.0, runId = "r", active = true,
        rwRatio = null,  // <-- inbound from generator carries no ratio; service stamps it
    )
    val b = a.copy(observedTps = 500.0, avgLatencyMs = 2.0)

    val agg = service.aggregate(listOf(a, b), now)

    assertEquals("20:80", agg.rwRatio)
}
```

`idle()` is private and there's no existing idle test to amend — that's fine. `idle()` mirrors `aggregate()`'s stamping pattern (Step 5 below adds `rwRatio = this.rwRatio` to both call sites of the data-class constructor); coverage of the field-plumbing comes from the aggregate test, and the idle path is exercised at runtime by Task 7's smoke #4 (threads = 0 still shows the workload subtitle).

- [ ] **Step 3: Run to confirm it fails**

```bash
./gradlew :demo-ui:test --tests 'com.gridgain.demo.payments.ui.metrics.GeneratorMetricsServiceTest'
```
Expected: compile failure (`rwRatio` not on `MetricsSnapshot` or constructor).

- [ ] **Step 4: Add the field to `MetricsSnapshot.kt`**

```kotlin
data class MetricsSnapshot(
    val updatedAtMs: Long,
    val observedTps: Double,
    val avgLatencyMs: Double,
    val totalOps: Long,
    val errorCount: Long,
    val targetTps: Double,
    val runId: String,
    val active: Boolean,
    val rwRatio: String? = null,  // <— new; serialized via Jackson per existing convention
)
```

`= null` default keeps the existing test/production constructions valid.

- [ ] **Step 5: Wire it through `GeneratorMetricsService`**

In `GeneratorMetricsService.kt`:
1. Add a constructor param `private val rwRatio: String? = null` (default keeps existing call sites valid).
2. In `aggregate()`, when building the returned `MetricsSnapshot`, pass `rwRatio = this.rwRatio`.
3. In `idle()`, do the same.

That's it — there's only one `flow.tryEmit(...)` site (around line 111) and it emits either `aggregate(...)` or `idle()`, so both emit paths now carry the field with no extra copy needed.

- [ ] **Step 6: Run tests to verify pass**

```bash
./gradlew :demo-ui:test --tests 'com.gridgain.demo.payments.ui.metrics.GeneratorMetricsServiceTest'
./gradlew :demo-ui:test --tests 'com.gridgain.demo.payments.ui.metrics.WorkloadRatioServiceTest'
```
Expected: both green.

- [ ] **Step 7: Wire startup**

In `UiConfig.kt`, add **one** new field — `val opsFile: Path` — populated following the existing `clientEndpointsFile` pattern (which already handles the `:demo-ui:run` working-directory mismatch via `projectDirectory` + `findDemoProjectRoot()`):

```kotlin
val opsFile = env(
    "PAYMENTS_OPS_FILE",
    projectDir.resolve("src/main/resources/generator/ops.yaml").toString(),
)
// …in the UiConfig(...) constructor call below:
opsFile = Paths.get(opsFile),
```

Do **not** add a scenario-name field — `generatorScenario` already exists on `UiConfig` (line ~78) for exactly this value.

In `Application.kt` (where `GeneratorMetricsService` is constructed), wire the ratio in and log it:
```kotlin
val rwRatio = WorkloadRatioService(config.opsFile, config.generatorScenario).readWriteRatio()
logger.info("Workload R/W ratio for scenario '${config.generatorScenario}' loaded from ${config.opsFile}: ${rwRatio ?: "(none — subtitle will fall back)"}")
val metricsService = GeneratorMetricsService(
    // …existing args…
    rwRatio = rwRatio,
)
```

- [ ] **Step 8: Smoke the startup log line**

Restart the UI (Task 7's stop/start procedure works once it's defined; for now just kill the existing `:demo-ui:run` Java process holding port 8081 and re-run). Then:
```bash
grep -m1 'Workload R/W ratio' /tmp/demo-ui.log
```
Expected: a line confirming the ratio was loaded (e.g. `… loaded from /Users/…/src/main/resources/generator/ops.yaml: 20:80`). If the line says `(none — subtitle will fall back)`, check that `projectDir` resolved correctly — most likely cause is launching from the wrong working directory.

- [ ] **Step 9: Commit**

```bash
git add demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/MetricsSnapshot.kt \
        demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/GeneratorMetricsService.kt \
        demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/Application.kt \
        demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/config/UiConfig.kt \
        demo-ui/src/test/kotlin/com/gridgain/demo/payments/ui/metrics/GeneratorMetricsServiceTest.kt
git commit -m "feat(ui-backend): expose rwRatio on MetricsSnapshot

Stamped on every emitted snapshot (live + idle) from the configured
WorkloadRatioService. Frontend's phase-6 dashboard uses it as the
latency-panel workload descriptor.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3 — Frontend: extract `Sparkline` component

**Files:**
- Create: `demo-ui/frontend/src/components/Sparkline.tsx`
- Modify: `demo-ui/frontend/src/components/MetricsPanel.tsx` (import from the new file instead of defining inline; this keeps MetricsPanel working until Task 6 retires it)

- [ ] **Step 1: Copy the `Sparkline` function out of `MetricsPanel.tsx`**

Move the function (and its TypeScript prop type) to `demo-ui/frontend/src/components/Sparkline.tsx` verbatim, then `export function Sparkline(...)`. Keep the same `W = 448`, `H = 68`, `pad = 2` defaults — the dashboard uses a different size via SVG's `preserveAspectRatio="none"`, so no parameterisation needed here.

- [ ] **Step 2: Update `MetricsPanel.tsx` to import from the new module**

Remove the inline `Sparkline` from `MetricsPanel.tsx`. Add `import { Sparkline } from './Sparkline'`.

- [ ] **Step 3: Smoke**

```bash
# Stop existing UI first; see Task 7 stop/start procedure.
./gradlew :demo-ui:run
```
Open `http://localhost:8081/`, step to phase 6, set threads to 4, confirm the top-right `MetricsPanel` still renders its sparklines (no visual change expected — this is a pure refactor).

- [ ] **Step 4: Commit**

```bash
git add demo-ui/frontend/src/components/Sparkline.tsx \
        demo-ui/frontend/src/components/MetricsPanel.tsx
git commit -m "refactor(ui): extract Sparkline to its own component

Preparing for reuse in PerfDashboard. No behaviour change.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4 — Frontend: `PerfDashboard` component

**Files:**
- Create: `demo-ui/frontend/src/components/PerfDashboard.tsx`

- [ ] **Step 1: Confirm the TS `MetricsSnapshot` interface exposes `r_w_ratio`**

```bash
grep -nE 'r_w_ratio' demo-ui/frontend/src/types/api.ts
```
If absent (it will be on a clean run of Task 2), add `r_w_ratio: string | null` to the `MetricsSnapshot` interface at `demo-ui/frontend/src/types/api.ts` (around line 74) — that field rides on `latest`, not `history`. Confirm `useMetricsWebSocket.ts` imports `MetricsSnapshot` from `types/api.ts` so the new field is in scope without further edits.

- [ ] **Step 2: Write `PerfDashboard.tsx`**

```tsx
import type { MetricsStream } from '@/hooks/useMetricsWebSocket'
import type { CpuStream } from '@/hooks/useCpuWebSocket'
import { Sparkline } from './Sparkline'

/**
 * Phase-6 performance dashboard. Three big-number panels — Throughput, GG Latency, GG CPU —
 * in a horizontal row, centered at ~75% body width with a sparkline under each. Replaces the
 * data panels / tailers / animations the audience can't read at high ops/sec (CLAUDE.md §3, §5).
 */
export function PerfDashboard({
  stream,
  cpu,
  visible,
}: {
  stream: MetricsStream
  cpu: CpuStream
  visible: boolean
}) {
  if (!visible) return null
  const { history, latest } = stream
  const tps = latest?.observed_tps ?? 0
  const latency = latest?.avg_latency_ms ?? 0
  const rwRatio = latest?.r_w_ratio  // string like "20:80" or null/undefined
  const rwSubtitle =
    rwRatio && /^\d+:\d+$/.test(rwRatio)
      ? `≈ ${rwRatio.replace(':', ' : ')} reads / writes`
      : 'mixed workload'

  return (
    <div className="flex justify-center items-center w-full py-6">
      <div className="grid grid-cols-3 gap-5 w-[75%] max-w-6xl">
        <Stat
          label="Throughput"
          value={tps < 10 ? tps.toFixed(1) : Math.round(tps).toLocaleString()}
          unit="ops / sec"
          values={history.map((p) => p.tps)}
          stroke="#10b981"
        />
        <Stat
          label="GG Latency"
          value={latency.toFixed(1)}
          unit="ms"
          subtitle={rwSubtitle}
          values={history.map((p) => p.latencyMs)}
          stroke="#3b82f6"
        />
        <Stat
          label="GG CPU"
          value={cpu.cpuPercent.toFixed(0)}
          unit="%"
          subtitle="avg both nodes"
          values={cpu.history}
          stroke="#f59e0b"
          max={100}
        />
      </div>
    </div>
  )
}

function Stat({
  label,
  value,
  unit,
  subtitle,
  values,
  stroke,
  max,
}: {
  label: string
  value: string
  unit: string
  subtitle?: string
  values: number[]
  stroke: string
  max?: number
}) {
  return (
    <div className="text-center border border-surface-700 rounded-lg bg-surface-900/90 px-5 py-6">
      <div className="text-[11px] uppercase tracking-[.1em] text-surface-400 font-mono">{label}</div>
      <div className="my-2 leading-none">
        <span className="text-5xl font-semibold tabular-nums text-surface-100">{value}</span>
        {unit && <span className="ml-1 text-2xl text-surface-300">{unit === 'ms' || unit === '%' ? unit : ''}</span>}
      </div>
      {unit !== 'ms' && unit !== '%' && (
        <div className="text-xs text-surface-400 mt-1">{unit}</div>
      )}
      {subtitle && <div className="text-xs text-surface-400 mt-1">{subtitle}</div>}
      <div className="mt-4">
        <Sparkline values={values} stroke={stroke} max={max} />
      </div>
    </div>
  )
}
```

(The slightly fiddly `unit` rendering keeps the visual unit alongside the number for `ms` and `%`, and on a separate line for `ops / sec` — matching the brainstorm option C mockup.)

- [ ] **Step 3: Build to catch type errors**

```bash
cd demo-ui/frontend && npx tsc --noEmit
```
Expected: clean. If `latest.r_w_ratio` errors, you missed updating the TS type in Step 1.

- [ ] **Step 4: Commit**

```bash
git add demo-ui/frontend/src/components/PerfDashboard.tsx \
        demo-ui/frontend/src/types/api.ts
git commit -m "feat(ui): PerfDashboard component for phase 6

Three-panel horizontal layout — Throughput, GG Latency, GG CPU — big numbers
with sparklines. Reuses the streams MetricsPanel was already consuming;
latency subtitle reads the rwRatio added in the prior commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5 — Frontend: App.tsx phase-6 wiring

**Files:**
- Modify: `demo-ui/frontend/src/App.tsx`

- [ ] **Step 1: Read the existing `visibility()` function**

```bash
grep -nB 1 -A 20 'function visibility' demo-ui/frontend/src/App.tsx
```
Confirm exactly what each boolean controls before editing.

- [ ] **Step 2: Update `visibility()`**

For every existing flag controlling a data-plane panel or a tailer or an animation, append `&& phase < 6` to its expression (or restructure to a single `const isLoadPhase = phase >= 6`). Specifically:
- `mainframePanel: phase >= 1 && phase < 6`
- `ggPanel: phase >= 2 && phase < 6`
- `cdcTailer: phase >= 2 && phase < 6`
- `ggToPostgresTailer: phase >= 3 && phase < 6`
- `ggToMariaTailer: phase >= 5 && phase < 6`
- `mariaPanel: phase >= 5 && phase < 6`
- `loadSlider: phase >= 6` (unchanged)
- Add: `perfDashboard: phase >= 6`

If `App.tsx` separately gates the flow-arrow animation rendering, gate it on `phase < 6` too. If `MetricsPanel` is rendered today gated on phase 6, replace its render-site (not its visibility flag) — see Step 3.

- [ ] **Step 3: Replace `<MetricsPanel ... />` with `<PerfDashboard ... />`**

```bash
grep -n 'MetricsPanel' demo-ui/frontend/src/App.tsx
```
Update the import line and the render site. Pass the same `stream` / `cpu` props; gate `visible` on `vis.perfDashboard`.

- [ ] **Step 4: Type-check**

```bash
cd demo-ui/frontend && npx tsc --noEmit
```
Expected: clean.

- [ ] **Step 5: Commit**

```bash
git add demo-ui/frontend/src/App.tsx
git commit -m "feat(ui): wire PerfDashboard into phase-6 visibility map

Gates Mainframe / GG / MariaDB panels and all three connector tailers on
phase < 6; renders PerfDashboard at phase >= 6. Inter-panel flow animations
are skipped at phase 6 (no panels to flow between).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6 — Update CLAUDE.md §3 + §5

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update §3 phase-6 row**

Find the visibility table in §3. Edit the phase-6 row so it reads (approximately):

| 6 | hidden | hidden | hidden | active | perf dashboard (3 panels) | none |

…and add a one-line note under the table: "Phase 6 swaps the data-panel layout for a centered three-panel dashboard (Throughput, GG Latency, GG CPU) — see [`docs/2026-06-22-phase6-performance-dashboard-design.md`](docs/2026-06-22-phase6-performance-dashboard-design.md)."

- [ ] **Step 2: Update §5 high-load suppression paragraph**

Find the paragraph that describes replacing the GG→Postgres / GG→MariaDB tailers with "Not displayed under high load" placeholders during phase 6. Rewrite it: at phase 6 the tailers (and the data panels) don't render at all — they're replaced by the performance dashboard. The "Not displayed under high load" placeholder is no longer needed.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): phase-6 visibility now describes the perf dashboard

§3 visibility table and §5 high-load suppression paragraph updated to
match the implemented behaviour: at phase 6 the data panels and tailers
unmount, replaced by the three-panel performance dashboard.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7 — Smoke verification + retire `MetricsPanel.tsx`

**Files:**
- Delete: `demo-ui/frontend/src/components/MetricsPanel.tsx`

- [ ] **Step 1: Stop the existing UI**

```bash
UI_PID=$(lsof -nP -iTCP:8081 -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $2}')
[ -n "$UI_PID" ] && kill "$UI_PID" && sleep 3
[ -n "$UI_PID" ] && lsof -nP -iTCP:8081 -sTCP:LISTEN >/dev/null 2>&1 && kill -9 "$UI_PID"
lsof -nP -iTCP:8081 -sTCP:LISTEN >/dev/null 2>&1 && echo "STILL UP — investigate" || echo "8081 FREE"
```

- [ ] **Step 2: Start the UI in the background**

```bash
./gradlew :demo-ui:run > /tmp/demo-ui.log 2>&1 &
until lsof -nP -iTCP:8081 -sTCP:LISTEN >/dev/null 2>&1 || grep -qE 'BUILD FAILED|Exception in thread "main"' /tmp/demo-ui.log 2>/dev/null; do sleep 2; done
lsof -nP -iTCP:8081 -sTCP:LISTEN >/dev/null 2>&1 && echo "UI UP" || (echo "UI FAILED"; tail -30 /tmp/demo-ui.log; exit 1)
```

- [ ] **Step 3: Smoke checklist (manual, in browser)**

Open `http://localhost:8081/` and verify each, in order:

1. **Phase 1–5 unchanged.** Step the phase control from 1 → 5. The Mainframe panel, GG panel, MariaDB panel, all three connector tailers, and the flow animations behave exactly as before. The top-right corner where `MetricsPanel` used to sit is empty (you removed it).
2. **Phase 6 layout swap.** Step to phase 6. The three data panels and all three tailers disappear. The three perf panels appear centered in the body at ~75% width — Throughput on the left, GG Latency in the middle, GG CPU on the right.
3. **Header unchanged.** The phase indicator, threads stepper, Off button, and Reset button are all still where they were, with no visual changes.
4. **Threads = 0 reads sensible.** With threads at 0, the dashboard shows `0` ops/sec, `0.0 ms` latency, and current idle CPU (low single-digit %). Latency subtitle shows the workload descriptor (e.g. `≈ 20 : 80 reads / writes`).
5. **Threads up → numbers move.** Click + on the threads stepper a few times (settle ≤4 to stay polite). Within ~10s, Throughput climbs, GG CPU follows, all three sparklines start to populate.
6. **Threads back to 0.** Numbers decay to idle.
7. **Subtitle fallback.** Stop the UI, set `PAYMENTS_GENERATOR_SCENARIO=does-not-exist`, restart, return to phase 6. Latency subtitle now reads `mixed workload`. Restore the env var afterward.

If any check fails, fix in place (it's almost certainly a styling or visibility-map miss), repeat from Step 1.

- [ ] **Step 4: Confirm no other references to `MetricsPanel`**

```bash
grep -r 'MetricsPanel' demo-ui/frontend/src/
```
Expected: zero matches. (App.tsx's import was removed in Task 5 Step 3.)

- [ ] **Step 5: Delete `MetricsPanel.tsx`**

```bash
rm demo-ui/frontend/src/components/MetricsPanel.tsx
cd demo-ui/frontend && npx tsc --noEmit
```
Expected: clean type-check.

- [ ] **Step 6: Stop the UI cleanly so the deletion ships in a fresh build**

```bash
UI_PID=$(lsof -nP -iTCP:8081 -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $2}')
[ -n "$UI_PID" ] && kill "$UI_PID"
```

- [ ] **Step 7: Commit**

```bash
git add -u demo-ui/frontend/src/components/MetricsPanel.tsx
git commit -m "chore(ui): retire MetricsPanel; PerfDashboard owns phase-6 metrics

PerfDashboard fully covers the phase-6 metrics surface. Smoke verified
phases 1–6, threads-up/-down, and rwRatio fallback before delete.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Done

All commits on `main` per the project's branch policy (the demo repo commits to main; toolkit projects use feature branches). The branch should now have 6 commits added by this plan (one per task, except Task 7 which is a delete + smoke). Push when comfortable — pushing is pre-authorized for the demo repos per project memory.
