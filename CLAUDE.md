# MariaDB & GridGain Mainframe Payments Demo

## Context

This demo shows how GridGain (in-memory data grid) and MariaDB (operational RDBMS) can be used to incrementally move high-throughput, low-latency applications off mainframes and onto a modern software stack. The story is migration-by-phases: the legacy mainframe stays authoritative on day one; GridGain front-ends it with a write-through cache; MariaDB lands as the modern operational target while the mainframe is being retired.

Audience: sales engineering, technical PoCs, and customer-facing demos at events. Target runtime: **5–10 minutes** end-to-end. This is NOT a benchmark, a DR scenario, or a multi-region story.

This document follows the structure pioneered by [gridgain-demo-data-generator/CLAUDE.md](gridgain-demo-data-generator/CLAUDE.md): numbered §-sections, each tagged with one of:
- **`in scope`** — implemented in v1 (the full 6-step demo).
- **`designed, deferred`** — contracts must accommodate; no code in v1.
- **`future`** — acknowledged direction; not designed in this document.

The demo's runtime, infrastructure, and most of the data path are owned by the GridGain Demo Toolkit (the `gridgain-demo-gradle-plugin` and its sibling sub-projects). What this project adds is the demo-specific scenario, configuration, presenter UI, and the toolkit extensions required to deploy non-GridGain elements (Postgres mainframe proxy, MariaDB) as first-class plugin elements.

---

## §1 Architecture & Deployment *(in scope)*

**Single-cloud k8s, single-cluster.** The demo deploys onto one GKE Kubernetes cluster. All demo components (GG8 cluster, Postgres mainframe-proxy, MariaDB, the CDC pipeline, the demo UI) run inside that cluster with separate node pools so they do not contend for resources.

**Topology.**

```
                         ┌────────────────────────┐
                         │  Mainframe Proxy DB    │
                         │  (Postgres on GKE)     │
                         └────────────────────────┘
                              |                ▲
                              │ CDC            │ Write-through
                              │ (Postgres → GG)│ (GG CacheStore)
                              ▼                │
                         ┌────────────────────────┐
                         │  GridGain 8 cluster    │
                         │  (GKE, 2–3 nodes)      │
                         └────────────────────────┘
                                                │
                                                │ Write-through
                                                │ (GG CacheStore)
                                                ▼
                         ┌────────────────────────┐
                         │  MariaDB               │
                         │  (GKE, single node)    │
                         └────────────────────────┘

  ┌──────────────────────────────────────────────────────────┐
  │  Demo UI (React app)                                     │
  │  Three panels: Mainframe (VT100) | GG | MariaDB          │
  │  Reads from each backing store via a Ktor backend (§5)   │
  └──────────────────────────────────────────────────────────┘
```

Two write paths leave GG **in parallel** — one to Postgres, one to MariaDB. They are independent CacheStores; there is no cross-store atomicity. Consistency between the two sinks is eventual.

CDC flows **one way only**, mainframe-proxy → GG. The pipeline is **Debezium + Apache Kafka**, both deployed in-cluster on the supporting-services node pool (§7 details the topology, §8 the plugin packaging). It powers the demo beat where a mainframe-side transaction propagates to GG. There is no Postgres → MariaDB CDC path; MariaDB receives data only from GG's second write-through.

**Slim-down delta from the toolkit-dev starting config.** The copied [mainframe-payments-demo/src/main/resources/demo-config.yaml](mainframe-payments-demo/src/main/resources/demo-config.yaml) currently inherits the 6-cluster, two-cloud starting point. The implementation pass will reduce it to: one `infrastructures` entry (GKE in one region); one `clusters` entry (GG8); two new `databases` entries (Postgres mainframe-proxy, MariaDB); one new `cdc_connectors` entry (mainframe → GG, deploying Debezium + Kafka under the hood). No `monitors` entry — Control Center is out of scope (§15).

**Node pools.** GG runs on its own node pool, per existing toolkit norms. Postgres, MariaDB, and the demo UI run on a separate "supporting services" pool, to avoid co-locating non-GG workloads with the cluster. The data generator, when deployed in-cluster, runs on yet a third pool — the data-generator's hard anti-co-location rule already enforces this.

---

## §2 Demo Flow *(in scope)*

The demo proceeds in fixed phases. Panels are hidden until their phase is activated. We do not anticipate going backward through phases.

**Pre-demo state (t=0).** All systems are running. CDC is live. Both write-through paths are wired. The mainframe proxy is seeded with the predefined transaction list (see §10). The data generator is deployed but idle. The demo UI opens at phase 0 — the Mainframe panel is already visible.

**Phase 0 — Mainframe panel only.** The presenter selects a transaction from the VT100-styled list (for example, *"Raghu buys an NVIDIA GeForce RTX 5080 Graphics card for $1349.99"*). The balances section updates with values read from the mainframe-proxy database.

**Phase 1 — Reveal the Mainframe → GG event queue.** A phase control on the UI (see §3) reveals the Mainframe → GG event window. The CDC sink is paused (it has been since reset) and the tailer styles its events as **queued** (bright/white). No GridGain panel and no bring-online controls yet — just the queue itself, so the audience sees that mainframe changes are being captured into a durable buffer even though GridGain isn't online. This is the "set the scene" beat before the cutover.

**Phase 2 — Bring GridGain online (without losing events).** The phase control reveals the GridGain panel — initially **empty** — and mounts the three bring-online buttons above the Mainframe → GG window. The feed is still paused. The presenter brings GG online in four deliberate steps, demonstrating a zero-loss cutover. The **dump and load are deliberately separate** — that gap is the whole point:

1. **Bulk Dump** captures a point-in-time snapshot of the current mainframe data and holds it in the demo backend. GG is **not** touched yet.
2. The presenter executes a transaction on the Mainframe panel. It appears in the event window as **queued** (white, not yet applied) — GG stays unchanged (feed paused), and crucially this transaction is **not** in the snapshot taken a moment ago.
3. **Bulk Load** applies the held snapshot into GG; the GG balances populate. They are already **stale** — they reflect the dump, so they miss the transaction fired in step 2.
4. **Unpause Event Feed** resumes the sink; the buffered Kafka backlog — including that in-flight transaction — drains into GG. The queued events flip to **applied** (struck through), and the GG balance jumps to reconcile what the snapshot missed.

Because capture was running before the snapshot was taken, the transaction that arrived *between the dump and the load* is not lost — it was waiting, durably, in Kafka. A bulk snapshot alone would have dropped it; the event stream is what makes the cutover lossless. The matching balances confirm GG is now in sync, and the audience saw exactly how it got there. Implementation: the demo backend pauses/resumes the real `cdc-sink` Kafka Connect connector; Bulk Dump reads a Postgres snapshot and holds it; Bulk Load writes that held snapshot to GG (see §7).

**Phase 3 — Execute a GridGain transaction.** The presenter selects a transaction from the GG panel. The transaction posts to GG, which debits the customer's account. GG's write-through CacheStores fan out: one update lands in the Postgres mainframe proxy, the other lands in MariaDB. The UI animates the data-flow arrow from GG to the mainframe proxy. Both balance panels update and agree.

**Phase 4 — Execute another mainframe-side transaction.** A second selection from the mainframe panel updates the mainframe proxy. CDC propagates the change to GG. Both balance panels update and agree.

**Phase 5 — Bring MariaDB online (without losing events).** The MariaDB panel and the GG → MariaDB window reveal, with that feed **paused** (MariaDB starts empty). This mirrors phase 2's beat, but the data dump comes **from GridGain**: the presenter clicks **Bulk Dump** (capture + hold a GG snapshot in the demo backend), fires transactions in the gap, then **Bulk Load** (write the held — now stale — snapshot into MariaDB), then **Unpause Event Feed** to resume the real GG→MariaDB sink so buffered `from-(gg|mf)` events drain in and reconcile what the snapshot missed. The proof is presenter-driven via the panel's analytic queries (e.g. *"Raghu's transactions"*) run before/after: firing transactions in **both** the Mainframe panel and the GridGain panel and re-running the query shows MariaDB capturing **both** sources — it's the unified modern analytics store (MF activity reaches it via GG → publisher → sink; GG activity via publisher → sink). Whether the presenter fires-while-paused (query unchanged → unpause → reflects both, the no-events-lost reading) or unpauses-then-fires-live is a runtime choice — the controls are the same. The panel offers a small set of analytic queries (row counts, a customer's transactions, a recent-window sum, top spenders, and an **end-of-day settlement**); each result replaces the previous one. The settlement query is the "reconciliation" beat: treating the demo as a payments processor, every purchase debits the purchaser and credits the product's supplier (brand derived from the product name), and the query reports the amounts due **from each purchaser** and **to each supplier** (the two sides reconcile — total debits = total credits). It is just a query on the MariaDB panel — not wired into the phase-6 load.

**Phase 6 — Scale up the transaction load.** A slider on the demo UI controls the data generator's transaction rate. As the rate climbs, GG processes traffic and the write-through fan-out continues to both sinks. Animations may fall behind at high rates; a "constant" animation mode is acceptable when the live rate exceeds what the renderer can keep up with (see §3).

---

## §3 Phase Model & Presenter Controls *(in scope)*

The phase is the demo's control plane. Implementation responsibilities are split between the UI and the underlying data plane.

**Phase state machine.** Phases are linear and forward-only: `0 → 1 → 2 → 3 → 4 → 5 → 6`. The current phase is held in UI state; transitions are triggered by a UI control (a "next phase" button, a slider, or a dropdown — the exact UI affordance is the implementer's choice). Going back to a previous phase is not supported in v1; presenter recovery is a full reset (see §14).

**Per-phase visibility:**

| Phase | Mainframe panel | GG panel | MariaDB panel | Generator        | Animations                       | Connector tailers (§5)                    |
|-------|-----------------|----------|---------------|------------------|----------------------------------|-------------------------------------------|
| 0     | visible         | hidden   | hidden        | idle             | off                              | none                                      |
| 1     | visible         | hidden   | hidden        | idle             | off                              | Postgres→GG (CDC) visible, **paused/buffering** — no bring-online controls yet |
| 2     | visible         | visible (empty→loaded) | hidden | idle    | balance-update only              | (CDC tailer still **paused** + bring-online controls revealed above it) |
| 3     | visible         | visible  | hidden        | idle             | + GG → mainframe-proxy data flow | + GG→Postgres                             |
| 4     | visible         | visible  | hidden        | idle             | + mainframe-proxy → GG data flow | (CDC tailer now shows traffic)            |
| 5     | visible         | visible  | visible (empty→loaded) | idle    | analytics query results          | + GG→MariaDB **paused/buffering** + bring-online controls |
| 6     | hidden          | hidden   | hidden        | active           | none (dashboard mode)            | none (replaced by perf dashboard)         |

Note: GG→Postgres write-through is live from phase 3 (no beat) so the mainframe panel reflects GG transactions. The GG→MariaDB feed, by contrast, is **paused from reset until phase 5** — MariaDB gets its own bring-online beat — and its tailer reveals at phase 5 alongside the MariaDB panel (earlier reveal would have no spatial anchor).

**Phase 6 swaps the data-plane layout for a centered three-panel performance dashboard** (Throughput, GG Latency, GG CPU) — see [`docs/2026-06-22-phase6-performance-dashboard-design.md`](docs/2026-06-22-phase6-performance-dashboard-design.md). The three data panels, all connector tailers, and the inter-panel flow animations unmount at phase 6 because audiences can't read them at high ops/sec; what stays is the header (phase indicator, threads stepper, Off button, Reset button) and the dashboard itself.

**Phase 1 reveal-only beat.** Phase 1 reveals the Mainframe → GG event window with **no controls** — just the queue itself, with events styled **queued** (bright/white) because the feed is paused. This isolates the "events are being captured into a durable buffer" idea from the cutover mechanics that follow in phase 2.

**Phase-2 bring-online sub-flow.** Phase 2 reveals the GridGain panel and mounts three sequential buttons above the Mainframe → GG window: **Bulk Dump** (capture + hold a Postgres snapshot), **Bulk Load** (apply the held snapshot to GG), then **Unpause Event Feed** (resume the `cdc-sink`). Disabled states enforce the order. These controls appear only in phase 2; the feed has been paused since reset (§14). The Mainframe → GG window keeps styling events **queued** (white) while paused, then **applied** (struck through) once the feed is unpaused and the backlog drains. The presenter fires the in-flight mainframe transaction manually **between Bulk Dump and Bulk Load**, so the loaded snapshot is stale and the unpaused feed reconciles it; once unpaused the feed stays live for phases 3–6.

**Phase-5 bring-online sub-flow.** Phase 5 mirrors phase 2 for MariaDB, with the dump coming **from GridGain**: three buttons above the GG → MariaDB window — **Bulk Dump** (capture + hold a GG snapshot), **Bulk Load** (apply the held snapshot to MariaDB), then **Unpause Event Feed** (resume the GG→MariaDB sink). The window styles events two-tone (queued/applied) during the beat. The presenter proves it with the MariaDB panel's analytic queries run before/after, firing transactions in both the Mainframe and GridGain panels to show MariaDB unifies both sources. The pause/resume targets the **real GG→MariaDB JDBC sink by name** (`PAYMENTS_MARIADB_SINK_CONNECTOR`); that sink is a toolkit deliverable still in progress, so until it lands Unpause surfaces a "connector not found" error while **Bulk Load works regardless**.

**Generator control.** The phase-6 control is **manual**: a continuous slider (with an exact numeric input) sets the **total** target write rate in ops/sec, and a **pods** stepper sets how many generator pods run. The backend splits the total across pods and (re)launches a distributed run; adding pods is the real lever for saturating GG, since a single pod is capped by GG round-trip latency. Each change first stops any running generator **by k8s label** (so runs never pile up), then relaunches at the new load — see §10. The earlier off/slow/medium/fast stepped slider was removed: it passed a gradle property the plugin never read, so it never varied the load. Truly live, in-flight rate change (no relaunch) is deferred to §16.

**Animation policy.** Real-time per-event animation is the default. When generator rate exceeds renderer capacity (a threshold the implementer picks), the UI switches to a "constant" animation mode — a continuous flow stream — rather than dropping frames.

---

## §4 Bootstrapping vs. Demo Surface *(in scope)*

The demo opens with the data plane already running. The "bootstrapping" is intentionally invisible.

**Running before the presenter starts (hidden from view):**
- GKE cluster, all node pools, all workloads (GG, Postgres, MariaDB, Kafka, Debezium, generator).
- CDC **capture** live (Debezium → Kafka). The inbound `cdc-sink` (Kafka → GG) starts **paused**, so GG is **empty** and the mainframe seed buffers durably in Kafka — GG is brought online explicitly during phase 2's beat (§2). This paused/empty starting state is (re)established by a demo reset (§14); if Kafka Connect is unreachable, reset fails-soft and the old behaviour (CDC refills GG immediately) applies.
- GG's write-through CacheStores, wired to both Postgres and MariaDB.
- Seeded fixture data in the Postgres mainframe-proxy (the predefined transaction list and starting balances).
- (Cluster monitoring is out of scope — see §15.)
- Demo UI process running; only the Mainframe panel is rendered.

**Revealed progressively (per §3):** panels, data-flow animations, and the data generator's activity.

The presenter never narrates the bootstrapping. If the bootstrapping is in an unhealthy state at the start of the demo, the recovery path is a full tear-down and redeploy (see §14).

---

## §5 Demo UI *(in scope)*

A new standalone React application owned by this project. It is **not** an extension of [gridgain-demo-ui](gridgain-demo-ui/) (which is the toolkit's config-and-ops surface; mixing the two would conflate operator tooling with audience-facing presentation).

**Location.** The proposed location is `mainframe-payments-demo/ui/`. Confirm during implementation.

**Tech stack baseline.** React 18 + TypeScript + Vite + Tailwind CSS, mirroring [gridgain-demo-ui/frontend](gridgain-demo-ui/frontend/) for consistency in tooling, build, and developer ergonomics.

**Panels.**
- **Mainframe panel** (bottom-left). VT100-styled — green-on-black, monospaced font, cursor-key navigation, Enter to execute. Reads from Postgres mainframe-proxy.
- **GridGain panel** (top-center). Modern UI / smartphone-app aesthetic. Dropdowns to select customer and product, "Purchase" button to execute. Reads from GG cache.
- **MariaDB panel** (bottom-right). SQL-CLI styled — a prompt, a query-picker dropdown, results clear between queries. Reads from MariaDB.

**Single-plane-of-glass intent.** All three panels share one screen, framed by a header with the phase control and an animation surface where data-flow lines visualize traffic between the data planes.

**UI → data path.** The browser cannot speak the GG8 thin-client protocol or JDBC directly, so a server-side proxy is required for the GG and SQL panels. A thin **Ktor backend** owned by this demo project sits in front of all three data planes — the browser talks HTTP/WebSocket to it; it talks to GG via [gridgain-demo-client-utils](gridgain-demo-client-utils/) and to Postgres/MariaDB via JDBC. Mirrors the [gridgain-demo-ui](gridgain-demo-ui/) backend pattern. Proposed location for the backend module: `mainframe-payments-demo/ui-backend/` (confirm during implementation).

**Animation.** Data-flow lines between panels are SVG `<path>` elements; motion comes from animating `stroke-dashoffset` on a `requestAnimationFrame` loop. No animation-library dependency. The same approach scales to the high-rate "constant flow" mode (§3) by switching from per-event pellets to a continuous dash pattern.

**Connector traffic tailers.** A dedicated horizontal tailer row runs alongside the connector lines, hosting three rolling-log tailers — one per data path:
- **GG → Postgres tailer** (alongside the GG-to-mainframe-proxy write-through arrow).
- **GG → MariaDB tailer** (alongside the GG-to-MariaDB write-through arrow).
- **Postgres → GG tailer** (alongside the CDC arrow).

Each tailer is a compact one-line-per-event scrolling feed, e.g.:
```
12:34:56.789  INSERT  transaction  CUST-42  ACCT-99  -1349.99  [corr=a3f2]
```
Hovering or clicking a line expands it to the full payload.

**Cross-tailer correlation.** Every GG cache update carries a correlation id (see §7) that propagates to both write-through CacheStores and to the CDC event stream. When a single cache update fans out, the resulting events in the GG→Postgres and GG→MariaDB tailers display the same id and briefly co-highlight (a matched color pulse). This visually demonstrates the parallel-write story.

**Rolling buffer.** Each tailer holds the last N events in memory (N TBD during implementation; sized for legibility at low rates, with auto-pruning that prevents memory growth during phase 5's load run). Buffer is in-memory only; no persistence in v1 (see §16 for a future-work entry on session recording).

**Reveal cadence.** Tailers appear alongside their corresponding connector lines per the §3 phase model. The CDC tailer is visible from phase 1 — revealed before the GridGain panel so the audience sees the event queue itself before any cutover mechanics. The GG→MariaDB tailer is held until phase 5 so it has a spatial anchor (the MariaDB panel) to attach to.

**Phase 6 swaps to the perf dashboard.** At phase 6 the data panels, all three connector tailers, and the inter-panel flow animations don't render at all — they're replaced by the centered three-panel performance dashboard (Throughput, GG Latency, GG CPU), so the audience reads the numbers that actually tell the "GG handles the load" story. The earlier "Not displayed under high load" placeholder on the GG→Postgres / GG→MariaDB tailers is no longer needed — at phase 6 those tailers are gone. (Per `generator-targets-own-caches`, generated load doesn't reach those stores anyway, so dropping the tailers also drops nothing of value.)

**Phase-1/2 two-tone styling.** Across the reveal beat (phase 1) and the bring-online beat (phase 2), the Mainframe → GG window distinguishes **queued** events (bright/white — buffered in Kafka while the feed is paused, not yet in GG) from **applied** events (dimmed + struck through — drained into GG once the feed is unpaused). A header badge reads "⏸ buffering" while paused and "● applying" once live. The three bring-online buttons (Bulk Dump, Bulk Load, Unpause Event Feed) sit directly above this window only in phase 2. Outside phases 1–2 the window uses the normal per-token styling. The window scrolls chronologically (newest at the bottom, auto-scrolled into view) so it reads as a live feed.

**Backend implementation.** The Ktor backend exposes three WebSocket channels — one per tailer. Sources:
- *GG→Postgres / GG→MariaDB:* the CacheStore implementations wrap each write with a tap that emits to a Kotlin `Flow`; the Ktor backend forwards items to the WebSocket subscribers.
- *Postgres→GG (CDC):* the demo backend subscribes to the relevant Kafka topic that Debezium publishes to and forwards events to the WebSocket. Reuses Kafka infrastructure already in the demo (§7) rather than introducing a separate tap.

At phase-5 rates, the backend sub-samples and/or batches WebSocket pushes so the browser doesn't drown; the sub-sampling policy is downstream design.

---

## §6 Data Model Surface *(in scope)*

This section describes entities and structural constraints. Column-level schemas live in `data.yaml` and the deployed DDL — not in this document.

**Entities.**
- **Customer** — a name (Faker-generated for the generator's load; a curated short list of named customers for the predefined demo transactions, see §10) and a customer-id PK.
- **Account** — belongs to one Customer; has a balance. Most demos use one account per customer; the model permits multiple.
- **Transaction** — belongs to one Account (debit or credit). Carries a Product reference for purchases, or a "payment" type for credits.
- **Product** — a fixed list of ~100 tech "toys" (graphics cards, headsets, gaming peripherals). Static fixture data.

**Relations.** `Customer → Account → Transaction`. `Transaction → Product` (nullable for payment-type transactions).

**Affinity / colocation.** Account and Transaction colocate on `customer_id` (GG8 `affinityKey` config). This is the colocation key end-to-end; it must match between the GG cache config and the SQL PKs in Postgres and MariaDB.

**Cache-vs-table mapping rules.** GG holds the entities as KV caches (one cache per entity). Postgres and MariaDB hold them as SQL tables with matching primary keys. The two write-through CacheStores serialize cache values into row updates against their respective JDBC connections. Schema parity between GG cache value classes and the two SQL row shapes is the implementer's responsibility.

---

## §7 Write-Through & CDC Contracts *(in scope for parallel writes; designed, deferred for failure handling)*

**Two parallel write-through paths.** Every cache update in GG triggers two CacheStore writes: one to Postgres, one to MariaDB. The two stores are independent — there is no cross-store atomicity. Failure of one store does not roll back the other or the cache update. Eventual consistency between the two sinks is acceptable for v1.

**Rejected alternatives.**
- *Chained writes (GG → Postgres → CDC → MariaDB).* Lighter on infrastructure but couples MariaDB's freshness to Postgres CDC lag. Doesn't fit the demo's "GridGain feeds the modern stack directly" story.
- *CDC fan-out from Postgres to GG and MariaDB.* Would make GG a CDC consumer rather than the originator, which is the opposite of the modernization story this demo tells.

**Correlation ids.** Every GG cache update is assigned a correlation id (UUID or short-hash) before it fans out to the two CacheStores. The id is carried through both write paths and emitted on each path's event tap so the connector tailers (§5) can co-highlight matching events across the GG→Postgres and GG→MariaDB streams. The id is also stamped on CDC events flowing the other direction. Whether the id is persisted as a column on the Postgres/MariaDB rows or kept transient on the event tap only is downstream design — both choices keep the tailer experience intact; persisting it enables future cross-system queries.

**One-way CDC: mainframe-proxy → GG.** Captures Postgres WAL events and applies them to the corresponding GG caches. Powers phase 4 (mainframe-initiated update propagates to the modern stack).

**Pause/resume for the bring-online beat.** The inbound `cdc-sink` connector can be paused and resumed at runtime via the Kafka Connect REST API; the demo backend drives this for the phase-2 zero-loss cutover (§2). While paused, Debezium keeps capturing to Kafka and the backlog drains into GG on resume from the connector's committed offset — that durability is what guarantees no events are lost across the snapshot/cutover window. The Bulk Dump step reads a Postgres snapshot and holds it in the demo backend; the separate Bulk Load step writes that held snapshot to GG (idempotent MERGE, rows kept `source='mf'`), independent of the streaming sink. Splitting dump from load is deliberate — the gap is where the in-flight transaction is fired (§2). This is a *runtime* operation on the deployed connector — it needs no change to the `cdc_connectors` element (§8); the toolkit need only keep the sink a REST-controllable Kafka Connect connector.

**CDC technology: Debezium + Apache Kafka.** Debezium's Postgres connector reads the logical replication slot and publishes change events to Kafka. A Kafka Connect sink (lightweight, custom) consumes those events and applies them to GG caches via [gridgain-demo-client-utils](gridgain-demo-client-utils/). Both Kafka and Kafka Connect run in-cluster on the supporting-services node pool. The `cdc_connectors` plugin element type (§8) packages this stack — JSONSchema, manifest generation, deploy/teardown — so the implementation choice does not leak into the configuration surface.

Kafka is deployed as part of the CDC stack but is **not** promoted to its own plugin element type in v1 (see §15). A second demo that needs Kafka outside the CDC context would justify that promotion (see §16).

**Failure handling.** *(designed, deferred)* — see §14.

---

## §8 Toolkit Plugin Extensions *(in scope)*

This demo requires two new first-class element types in the `gridgain-demo-gradle-plugin`. Both follow the existing element pattern (Infrastructures, Clusters, Monitors): JSONSchema validation, ConfiguredX types, a SpecAssembler subclass, deployment.yaml schema additions, and `deployX` / `teardownX` Gradle tasks.

**`databases` element type.** Variants: `postgres`, `mariadb`. Each variant carries image and tag, resource requests, node pool reference, JDBC port, init-DDL location, and an optional auth-secret reference. Deployable into any infrastructure that supports k8s. Reusable across future demos.

**`cdc_connectors` element type.** Configures a CDC pipeline between two named elements (source: a `databases` entry; sink: a `clusters` entry). Carries the source-table list, sink-cache list, mapping rules, and Debezium + Kafka resource configuration (Kafka broker count, retention, Kafka Connect resource requests, Debezium connector configuration). The element's deployment manifests bring up Kafka, Kafka Connect, and the Debezium Postgres connector together as one logical unit.

**Event tap.** The element exposes its event stream for downstream consumers (the demo UI's CDC tailer, in this case) via the Kafka topic Debezium publishes to. Reusable design: any future demo that wants connector observability subscribes to the same topic. No separate SSE or HTTP bridge is added at the element-type level; consumers that can't reach Kafka directly stand up their own bridge.

**Schema versioning.** Adding the new element types bumps `CURRENT_SCHEMA_VERSION` in [gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredState.kt](gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredState.kt) and adds a `MigrateVNtoVN+1` step under [gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/](gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/). All existing demo-configs in the workspace must continue to migrate forward cleanly (validated by `ConfigMigrationTest`).

Concrete file additions are downstream work and not described here.

---

## §9 Toolkit Plugin Consumption *(in scope)*

> **Using the toolkit?** Read the toolkit usage skill at
> `gridgain-demo-gradle-plugin/.claude/skills/gridgain-demo-toolkit/SKILL.md` (task surface, element
> types, generator dispatch) and, for load generation, the
> `gridgain-demo-data-generator/.claude/skills/gridgain-demo-data-generator/SKILL.md` skill. They
> don't auto-load here (they live in their own repos), so open them explicitly — they capture the
> non-obvious behavior (e.g. `dataGenerate` has no rate-override property; multi-pod rate is per-pod).

This demo invokes the plugin's standard task surface only. Per the workspace rule, no bespoke Gradle tasks live in this project's [mainframe-payments-demo/build.gradle.kts](mainframe-payments-demo/build.gradle.kts).

**Tasks invoked:**
- `validateDemoConfiguration` — confirms the demo's `demo-config.yaml` is schema-valid before deployment.
- `deployInfrastructure` — provisions the GKE cluster and node pools.
- `deployCluster` — deploys the GG8 cluster.
- `deployDatabase` *(new, per §8)* — deploys Postgres and MariaDB.
- `deployCdcConnector` *(new, per §8)* — wires the mainframe-proxy → GG pipeline.
- `deployMonitor` — deploys the `pg-gke` Prometheus/Grafana stack that feeds the demo UI's GG CPU gauge.
- `deployClusterMonitoring` — attaches `pg-gke` to the GG cluster (deploys the otel-collector + routes GG telemetry into Prometheus). Required after `deployCluster` + `deployMonitor`; without it, no GG metrics reach Prometheus and the CPU gauge stays empty. (CC is still out of scope per §15 — this task wires the Prom/Grafana monitor only.)
- `deployDataModel` — provisions caches in GG.
- `dataGenerate` — runs the payment-shaped scenarios (§10).
- `launchPluginUi` — operator-facing only; not part of the audience-facing demo flow.
- The corresponding `teardownX` tasks for cleanup.

**Demo UI is launched separately** from the plugin. It is a React app; its lifecycle is managed by `npm run dev` (development) or a static-served bundle (presentation), not a Gradle task.

---

## §10 Data Generator Integration *(in scope)*

The demo uses the existing [gridgain-demo-data-generator](gridgain-demo-data-generator/) without forking. Payment-shaped scenarios are authored in `data.yaml` and `ops.yaml`, consuming the generator's existing primitives (cohort buckets, `parent-fk-ref` relations, `business_event` transactions, Faker providers).

**`data.yaml` shape.**
- *Customer* schema: Faker first names; static cohort distribution (a heavy-tail bucket where, for example, 10% of customers do 50% of transactions).
- *Account* schema: one per Customer (cohort multiplier = 1).
- *Transaction* schema: cohort buckets per Account (multiplier varies; some accounts get many transactions, some get a few). Affinity column: `customer_id`.
- *Product* schema: a `yaml-data` provider sourcing from a 100-row fixture file shipped with this demo.

**`ops.yaml` shape.**
- One scenario, `mainframe-payments-load`, `business_event` transaction scope, `constant` rate (slider-driven, see below), `time` duration controlled by the UI.
- Target: the GG8 cluster (the same cluster the demo UI reads from).
- Provisioning: `skip` for v1 — the demo deploys caches via the plugin's `deployDataModel`, not the generator.

**Predefined sample transactions** (for phases 1–4, before load injection): a small curated YAML list shipped with this demo. Examples from the original vision draft:
- *Raghu buys an NVIDIA GeForce RTX 5080 Graphics card for $1349.99*
- *Sonya buys a Meta Quest 3S VR Headset for $435.50*
- *Raghu pays $1000.00 on his account*

Some customers in the curated list have multiple transactions so the analytics queries (phase 6) return meaningful results.

**Generated transactions** (phase 5): same shape as the curated list, but the customer name comes from Faker and the product is drawn from the 100-item fixture. A small fraction of customers re-appear across multiple transactions so the data has realistic skew.

**Tailer interaction at phase 5.** Generator-produced transactions land in GG, fan out to both write-through paths, and therefore appear in the GG→Postgres and GG→MariaDB tailers (§5) carrying correlation ids (§7). Correlation co-highlighting may be subtle at top rates — at high volume the tailer's job is to convey rate, not individual events; the rolling buffer auto-prunes per §5.

**Rate control: manual.** The phase-6 control sets the **total** target write rate (continuous slider + exact numeric input, 0 = off) and a **pod count**. The plugin reads the generator rate only from the ops file (there is no `dataGenerate` rate-override property), so the demo backend (`GeneratorControlService`) templates a *runtime* `ops.yaml` — the canonical scenario with `rate.ops_per_second` set to ceil(total / pods) and an injected `distribution: {replicas, partition_count}` block — and invokes `dataGenerate --ops=<runtime file>`. The plugin then dispatches a Deployment of N worker pods (each single-threaded; it does **not** divide the rate across pods, which is why the backend pre-divides). Distributed mode is also fire-and-forget — `dataGenerate` returns once the Deployment is Ready, so the UI stays responsive. Each change first tears down any prior run with `kubectl delete deployment,configmap,serviceaccount,role,rolebinding -l gridgain.com/scenario=<scenario>`, so runs never pile up (the old best-effort stop left orphaned Jobs accumulating, and never actually changed the load). Truly live, in-flight rate change (adjusting a running generator without relaunch) is `designed, deferred` (§16 Future Work).

---

## §11 Configuration Surface *(in scope)*

This demo's [mainframe-payments-demo/src/main/resources/demo-config.yaml](mainframe-payments-demo/src/main/resources/demo-config.yaml) carries the slim, demo-domain-named configuration.

**Delta from the inherited toolkit-dev starting point:**
- *Remove:* all AWS infrastructures and clusters; all GG9 clusters; the second GG8 cluster and its DCR partnership; both `cc-aws` and `pg-gcp` monitors unless reused.
- *Rename:* infrastructure to `mainframe-payments-gke`; cluster to `mainframe-payments-gg8`. Drop all "example" / "smoke" / personal-account names.
- *Add:* `databases.postgres-mainframe-proxy`, `databases.mariadb-analytics`, `cdc_connectors.mainframe-to-gg` (depends on §8 landing).
- *Adjust:* admin email and any account identifiers to demo-neutral values, not personal credentials.

This rewrite is a follow-up task; this CLAUDE.md describes the target, not the change.

---

## §12 Generated Output & State *(in scope for plugin artifacts; designed, deferred for demo-UI state)*

Runtime state lives under `demoOutputDirectory`, per the toolkit-wide convention:
- `deployment.yaml` — plugin-owned, tracks deployed elements.
- `data-generator/state/state.yaml` — generator-owned, tracks key emissions across runs.
- **CDC offsets.** Debezium uses a Kafka offset topic as the source of truth. A small periodic exporter — packaged with the `cdc_connectors` element — writes a snapshot of current offsets to `cdc-connectors/state/state.yaml` under `demoOutputDirectory`. Mirrors the data-generator's `state/state.yaml` pattern so presenters can spot-check CDC health without Kafka tooling.
- Demo-UI ephemeral state — in-memory; not persisted between demo runs. If presenter recovery becomes a requirement, this is the spot to revisit.

---

## §13 Dependencies *(in scope)*

- Java 17 (workspace rule).
- Kotlin Gradle plugin (matches plugin/UI projects).
- SnakeYAML pinned at `1.33` (workspace rule).
- Node toolchain via Homebrew (`/opt/homebrew/bin/node`), as for [gridgain-demo-ui](gridgain-demo-ui/).
- Postgres container image + JDBC driver (versions TBD; track GG8 CacheStore compatibility).
- MariaDB container image + JDBC driver (versions TBD).
- GG8 client (matches the plugin's pinned version).
- React 18 + TypeScript + Vite + Tailwind CSS for the demo UI.
- No animation library — SVG + `requestAnimationFrame` natively.
- Apache Kafka container image + Kafka Connect + Debezium Postgres connector (versions TBD; track compatibility with the chosen Postgres image).

No new dependencies in the plugin itself beyond what the new element types require.

---

## §14 Failure Behavior During Live Demo *(designed, deferred)*

Default stance for v1: failures during the live demo are not handled gracefully. The presenter's recovery path is a full tear-down and redeploy before the next session.

**Visible failure modes:**
- *CDC lag.* The phase-4 mainframe-initiated update may not appear in GG immediately. Acceptable as long as it arrives within a few seconds.
- *Write-through error to one sink.* The cache update succeeds; one sink is stale. No automatic remediation.
- *Generator stall during phase 5.* Slider becomes unresponsive. Restarting the scenario is the workaround.

**Future iteration** may add a "reset to phase 0" UI control that re-seeds fixtures and resets generator state without a full toolkit redeploy.

---

## §15 Out of Scope

- **MaxScale.** The current demo doesn't have a story that justifies it. A single MariaDB node is sufficient.
- **Control Center / cluster monitoring.** Removed from the demo — the three-panel UI doesn't read from it, and the CC cluster-attach added deploy friction (attach-job timeout) for no audience value. `deployClusterMonitoring`, the `monitors` (`cc-gke`) entry, the `cc-gke-pool` node pool, the `ignite-agent` connector template, and the Control Center license/images have been removed from this demo's `demo-config.yaml`. The toolkit retains monitor support for other demos.
- **Multi-region / DCR.** Single-cloud, single-region. The modernization story doesn't require geographic distribution at this stage.
- **GG9 variant.** GG8 only in v1.
- **Kafka as a first-class plugin element type.** Apache Kafka **is** deployed in v1 (as part of the `cdc_connectors` element's stack — see §7, §8), but it is not promoted to a standalone plugin element type with independent lifecycle. A second demo that needs Kafka outside the CDC context would justify that promotion (§16).
- **Presenter authentication / authorization.** The demo UI is intentionally open; no login.
- **Real mainframe emulator.** The Postgres mainframe-proxy is intentional — it stands in for the legacy system. No COBOL, no CICS, no z/OS simulation.
- **Benchmark claims.** Numbers shown during the demo are illustrative, not measured against a baseline.

---

## §16 Future Work

- GG9 variant of the same demo, contrasting GG8's push DCR with GG9's pull DCR.
- MaxScale integration, if the MariaDB scaling story becomes a demo beat.
- Multi-region active-active variant with cross-region DCR.
- Additional analytics queries on the MariaDB panel (top-spenders, trend analysis, anomaly flagging).
- Presenter "reset" controls (per §14).
- Truly live, in-flight generator rate change — adjusting the rate of a running generator without relaunching the distributed run (today each change tears down and relaunches). A first-class `dataGenerate --rate` / `--replicas` override in the plugin would also let the demo backend skip templating a runtime `ops.yaml` + `--ops`.
- Kafka promoted to its own plugin element type if a second demo needs it outside the CDC context.
- Pause / scrub controls on the connector tailers (§5) so presenters can stop the rolling feed and point at specific events.
- Tailer session recording — persist the event streams during a demo run so they can be replayed for training or post-demo analysis.
- Replacing the Postgres mainframe-proxy with a true mainframe emulator (e.g., Hercules + a COBOL-on-CICS stack), if a customer engagement justifies the effort.

---

## §17 Open Design Questions

All v1 design blockers identified during planning are resolved:
- **UI → data path** (§5): single Ktor backend owned by this demo, mirroring the [gridgain-demo-ui](gridgain-demo-ui/) backend pattern.
- **Animation approach** (§5): SVG `<path>` animation via `stroke-dashoffset` on a `requestAnimationFrame` loop; no animation library.
- **CDC technology** (§7): Debezium + Apache Kafka, both packaged under the new `cdc_connectors` plugin element type.
- **Generator rate control** (§10): manual — a continuous total-ops/sec slider + numeric input + pod-count stepper; the backend templates a runtime `ops.yaml` (rate + `distribution`) and relaunches a distributed run, stopping prior runs by k8s label. Truly in-flight (no-relaunch) rate change deferred to §16.
- **CDC offsets persistence** (§12): Kafka offset topic as source of truth, mirrored to `demoOutputDirectory/cdc-connectors/state/state.yaml`.

No outstanding blockers for v1 implementation. This section is retained as a landing spot for blockers raised by future iterations.

---

## §18 Verification *(for any future implementation work)*

End-to-end test plan for the v1 demo:

1. **Config validation.** `./gradlew validateDemoConfiguration` against this demo's `demo-config.yaml` succeeds.
2. **Schema migration.** Any pre-element-type-bump `demo-config.yaml` in the workspace migrates forward without manual intervention; a unit test in `ConfigMigrationTest.kt` covers the new step.
3. **Topology deploy.** `deployInfrastructure` + `deployCluster` + `deployDatabase` × 2 + `deployCdcConnector` produces a healthy GKE topology. All pods report `Ready`.
4. **Fixture seeding.** Predefined transactions and starting balances appear in the Postgres mainframe-proxy.
5. **Phase 0.** The demo UI loads with the Mainframe panel already visible. Selecting a curated transaction updates the balances pane from Postgres.
6. **Phase 1.** Phase-transition control reveals the Mainframe → GG event window with no controls; events style as queued because the feed is paused.
7. **Phase 2.** Phase-transition control reveals the GG panel and the bring-online controls; the four-step beat (Bulk Dump → in-flight transaction → Bulk Load → Unpause) leaves GG balances matching the mainframe within CDC tolerance (sub-second is the target).
8. **Phase 3.** GG-side transaction propagates to both Postgres and MariaDB; UI animation visible; balance panels agree post-update.
9. **Phase 4.** Mainframe-side transaction propagates to GG via CDC within tolerance; UI animation visible.
10. **Phase 5.** Generator runs at the slider-selected rate; rate changes are observable in both UI and metrics; the system remains stable across the rate range advertised for the demo.
11. **Phase 6.** Both analytics queries return non-trivial, sane results.
12. **Connector tailers.** Each tailer reveals on its expected phase per the §3 table; events surface within sub-second latency at low rates; correlation ids co-highlight matching GG→Postgres and GG→MariaDB events; the in-memory rolling buffer auto-prunes during phase 5 (no unbounded memory growth in the Ktor backend or the browser).
13. **Teardown.** `teardownX` tasks remove all deployed elements; no residual k8s resources, no orphaned PVs.
