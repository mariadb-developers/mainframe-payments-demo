# Design — Phase 6 performance dashboard

*Status: design approved, not yet implemented. 2026-06-22.*

## Goal

At phase 6 the demo audience can't get useful information out of the Mainframe / GridGain
/ MariaDB panels — they update too slowly relative to the generator's ops rate, and the
inter-panel data-flow animations devolve into a stream that conveys neither rate nor
specific events. The fix is to **swap the body of the screen for a three-panel
performance dashboard** the moment phase 6 begins, so the audience's eye lands on
*Throughput*, *GG Latency*, and *GG CPU* — the numbers that actually tell the "GG handles
the load" story.

## Decisions (locked during brainstorming)

- **Three panels, in order:** *Throughput* (measured ops/sec), *GG Latency*
  (avg, throughput-weighted), *GG CPU* (avg `sys_CpuLoad` across both GG nodes).
- **Visual style:** the hybrid "big number + ~30s sparkline" treatment (option C in the
  brainstorm). Big numbers are the audience-from-the-back-of-the-room read; the
  sparkline lets the presenter point at motion when the threads are dialled up.
- **Layout:** three equal-width panels in a horizontal row, centered, together occupying
  roughly 75% of the available body width.
- **What disappears at phase 6:** the Mainframe panel, the GridGain panel, the MariaDB
  panel, all three connector tailers, and the inter-panel data-flow animations.
- **What stays:** the top control row (phase indicator, threads stepper, Off button,
  Reset button). No change to that surface.
- **GG-Latency subtitle:** the small caption under the latency value is a *workload
  descriptor* — the read-to-write ratio, e.g. `≈ 80 : 20 reads / writes`. This replaces
  the misleading "p50" we sketched (the underlying value is an average, not a
  percentile) and gives the audience load-shape context without inviting questions
  about statistical methodology.

## Architecture

### What already exists

- **`/api/metrics`** WebSocket — streams `MetricsSnapshot { observed_tps, avg_latency_ms }`
  every ~1s from `GeneratorMetricsService`, which consumes the data generator's
  `generator-metrics` Kafka topic and aggregates throughput-weighted latency across pods.
- **`/api/cpu`** WebSocket — streams `CpuSnapshot { cpu_percent, history }` every ~2s
  from `PrometheusCpuService`, polling `avg(sys_CpuLoad)` against the in-cluster
  Prometheus through the laptop port-forward.
- **`MetricsPanel` component** — already renders the three metrics (Throughput, GG
  Latency, GG CPU) with sparklines, just in a compact `w-[30rem]` top-right overlay.
  Hand-rolled SVG sparkline, no charting library (per CLAUDE.md §5).
- **`useMetricsWebSocket` / `useCpuWebSocket` hooks** — manage the rolling history that
  feeds the sparklines.

Everything below is *layout*; no new data plumbing is required.

### What changes

- **`PerfDashboard` (new component)** — a phase-6-only layout that renders the same
  three metrics (consuming the same `MetricsStream` and `CpuStream` props that
  `MetricsPanel` already takes) but in dashboard mode: three big-number panels in a
  horizontal row, centered, ~75% body width, each with its own sparkline below.
  This is a separate component (not a prop-flag on `MetricsPanel`) because the layout
  is fundamentally different — different sizes, different arrangement, different role
  in the page. `MetricsPanel` is deleted in the same change after a manual smoke check
  confirms the new dashboard renders correctly, since `MetricsPanel` is only used at
  phase 6 today (its docstring confirms this) — keeping it as a fallback would just be
  dead code.
- **`App.tsx` visibility map** — extend the existing per-phase visibility computation:
  - `mainframePanel`, `ggPanel`, `mariaPanel`: gated to `phase >= 1 && phase < 6` (and
    their existing per-phase gates).
  - `cdcTailer`, `ggToPostgresTailer`, `ggToMariaTailer`: gated `phase < 6`.
  - `perfDashboard`: `phase >= 6`.
  - Data-flow animations: skipped entirely when `phase >= 6`.
  - The header row's existing visibility (PhaseControl, LoadSlider, ResetButton) is
    unchanged.
- **R/W ratio source** — parsed once from the data generator's scenario in `ops.yaml`
  (counting `select` vs. `insert`/`update` ops in the scenario's operation mix).
  Preferred over deriving from generator telemetry because the scenario file is the
  stable source of truth and doesn't require new metrics. Exposed by adding an
  immutable `r_w_ratio` field (e.g. `"80:20"` or `null`) to `MetricsSnapshot` — cheaper
  than a new route, at the cost of one extra small field in each ~1s WS frame.
  If the scenario file isn't available or parseable at startup, the field is `null`
  and the subtitle falls back to the neutral label `mixed workload`.

### Data flow

```
                    /api/metrics ws                /api/cpu ws
                    ┌────────────┐                 ┌────────────┐
generator-metrics ──┤ Metrics    │       prom ─────┤ Cpu        │
   Kafka topic      │ Snapshot   │       :9090     │ Snapshot   │
                    └─────┬──────┘                 └─────┬──────┘
                          ▼                              ▼
                  useMetricsWebSocket            useCpuWebSocket
                          ▼                              ▼
                       MetricsStream                  CpuStream
                                    ▼     ▼
                              ┌──────────────────┐
                              │   PerfDashboard  │
                              │  (phase 6 only)  │
                              └──────────────────┘
```

No new backend streams. R/W ratio is a one-shot value alongside `MetricsSnapshot`
(or its own `/api/workload` GET) — the frontend reads it once on mount.

## Edge cases

- **Stale generator metrics.** `GeneratorMetricsService` already idles to a
  zero-throughput snapshot after `stalenessMs` (5s) with no new Kafka records. The
  dashboard renders the idle snapshot as `0` ops/sec, `0` ms latency — visually obvious
  but not broken. No new handling required.
- **CPU WebSocket disconnect.** `useCpuWebSocket` reconnects with backoff. While
  disconnected, the panel shows the last value and a flat sparkline tail. Acceptable —
  matches the current behaviour of `MetricsPanel`.
- **R/W ratio missing.** If the backend can't determine the ratio (scenario file
  missing, parse failure), the subtitle falls back to `mixed workload` rather than
  rendering empty space. Logged on the backend; never surfaced to the audience.
- **Resize / small viewport.** The 75% body width is a maximum; at narrow widths the
  three panels can wrap to a stacked layout (the only intended viewing environment is
  a presenter's projector, but a graceful narrow fallback costs nothing).
- **Backwards in phases (phase 6 → phase 5).** Per CLAUDE.md §3 phases are forward-only;
  the dashboard unmounts on its way to phase 6 means it would re-mount with a fresh
  `useMetricsWebSocket` history if the user *did* go back. We won't design for this
  beyond what `phase < 6` already gives us.

## Testing

- **Unit (frontend).** A `PerfDashboard` test renders the three Metric blocks given
  fixture streams; asserts headlines, units, and that sparkline points equal the
  history array. The existing `MetricsPanel` tests, if any, can be removed or ported.
- **Visual smoke.** With the generator at 0 threads → dashboard shows `0 ops/sec`,
  `0.0 ms`, current GG CPU (idle, ≤ 5%). Stepping threads up via the existing
  stepper → numbers rise and the sparklines move.
- **R/W ratio.** A backend unit test confirms the ratio computation against a
  hand-crafted `ops.yaml` (e.g. 4 selects + 1 insert → `≈ 80 : 20`), and the fallback
  fires when the file is unparseable.

## Out of scope

- **Phase-6 entry / exit animations.** Snap transition is fine; the demo doesn't gain
  from a slide-in.
- **Reintroducing the data-flow animations** to the dashboard. The point of the change
  is to remove them.
- **Showing per-node CPU.** Average across both GG nodes is what the existing CPU
  service emits, and what the audience can hold in their head. Per-node breakdown
  belongs on a Grafana board, not the demo.
- **p50 / p95 / p99 latency.** We're using the average the generator already reports;
  upgrading to percentiles is a separate (future) ask.
- **Configurable panel layout.** Three panels, fixed order, fixed sizes. No
  preferences surface.

## Files expected to change

- `demo-ui/frontend/src/components/PerfDashboard.tsx` — new.
- `demo-ui/frontend/src/components/MetricsPanel.tsx` — retired (or kept until the
  switchover is verified and then deleted).
- `demo-ui/frontend/src/App.tsx` — visibility map + render the new component.
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/...` — small additions to expose
  the R/W ratio (exact files TBD in implementation plan).
- `demo-ui/frontend/src/__tests__/PerfDashboard.test.tsx` (or similar) — new tests.
- `CLAUDE.md` §3 visibility table — phase-6 row updated to reflect the dashboard mode.
- `CLAUDE.md` §5 high-load suppression — current text describes replacing tailers with
  a placeholder during phase 6; with the dashboard, the tailers don't exist at phase 6
  at all, so that paragraph needs a small rewrite.
