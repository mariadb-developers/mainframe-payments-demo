# Design — High-throughput load beat (~10–15k ops/s) + GG CPU panel

*Status: approved (design), not yet implemented. 2026-06-16.*

## Goal

A demo beat that shows GridGain processing **~10–15k ops/sec while its CPU stays low
(~20–30%)** — "the grid is bored." Today a single generator pod is capped at ~500 ops/s
(single-threaded, synchronous, closed-loop client: throughput ≈ 1 / round-trip latency),
and all pods pile onto the one 4-vCPU `default-pool` node, so the **load tier**, not GG, is
the ceiling. The fix is to scale the load tier with real CPU and surface GG's idleness.

## Decisions (locked)

- **Target sizing:** impressive + headroom — size for ~24–30 pods so 10k is comfortable and
  ~15k is reachable live.
- **Generator tier:** a **dedicated, autoscaling node pool**, not pods crammed on default-pool.
- **Scope:** permanent capability, across both tracks, TDD'd.
- **Generator is a first-class *deployable unit*** (always the intent): it leverages a node
  pool and has deploy/teardown lifecycle + placement, like clusters/databases/monitors —
  rather than a transient `dataGenerate` dispatch that ensures a pool on the fly.

## Architecture

### Track B — toolkit (plugin): promote the data generator to a deployable element

Follow the existing element pattern (§8 of the demo CLAUDE.md): JSONSchema → `ConfiguredX` →
`SpecAssembler` → `deployment.yaml` additions → `deployX`/`teardownX` tasks.

- **New element** (name TBD, e.g. `data_generators`): references a `node_pool_templates` entry
  + a target cluster + the scenario (`ops.yaml`/`data.yaml`) it runs. Carries replica/pod
  count and per-pod rate.
- **Dedicated node pool + placement:** the element provisions its own `wp-<generator>` pool
  (mirrors `wp-<db>` / `wp-<monitor>`), and the generator Deployment gets a nodeSelector
  (+ toleration if the pool is tainted) so pods land there, off GG/DB/CDC nodes. This
  formalizes the "anti-co-location" intent that §1 describes but isn't wired today (pods
  currently land on default-pool).
- **Per-pod rate / replicas as first-class** (the §16 future-work item): `deployX` (or a
  `--replicas`/`--rate` override) sets pods + per-pod rate directly, so the demo backend no
  longer templates a runtime `ops.yaml` + `--ops`. For the pods-only UI, per-pod rate is set
  to an effectively-unbounded ceiling so each pod runs at its latency limit.
- **Schema:** additive new element ⇒ bump `CURRENT_SCHEMA_VERSION` + `MigrateVNtoVN+1` +
  `ConfigMigrationTest`; existing demo-configs must migrate forward (gate:
  `validateDemoConfiguration` stays green). Update the toolkit usage skill + `cdc-connectors`-
  style element doc.

### Sizing

- Pool: **e2-standard-8, autoscale 1→4** (8→32 vCPU). ~24 e2-CPU free under the us-west1 e2
  quota (72) after GG (C3), DBs, CDC, and the pg-gke monitor. C3 is reserved for GG (quota 24).
- ~0.5 vCPU per pod at ~500 ops/s ⇒ ~30 pods ≈ ~15 vCPU of real work; the autoscaler adds
  nodes as pods grow (set sensible pod CPU requests, e.g. ~400–500m, so scheduling forces
  scale-out instead of oversubscription).
- GG (2× c3-standard-8 = 16 vCPU) stays ~20–30% even at 15k — the contrast holds.

### Track A — demo (main)

- **Config:** add the generator `node_pool_templates` entry + the new generator element
  instance referencing it and the GG cluster.
- **Generator control (backend + UI):** replace the ops/sec slider with a **pods-only
  stepper** (1…~30), labeled "≈ N×500 ≈ X k ops/s". Backend fixes the per-pod rate to the
  unbounded ceiling (or uses B's first-class `--replicas` once available) and only varies pods.
- **GG CPU panel:** new `PrometheusCpuService` in the demo backend polls the already-deployed
  Prometheus for `avg(sys_CpuLoad)` across GG nodes (also available: `process_cpu_seconds_total`,
  `sys_GcCpuLoad`) and streams it on a new WS; a small CPU gauge sits beside the existing
  tps/latency panel (phase 6). New config `PAYMENTS_PROMETHEUS_URL` (dev default
  `http://localhost:9090`) + a `:9090` entry in `scripts/dev-port-forwards.sh`.

## Sequencing

1. **Track B first** — generator element + node pool placement + (optional) `--replicas`
   override land on a plugin feature branch; post a READY in `track-coordination.md`.
2. **Track A** — re-pin, add the config entries, build the pods-only control + CPU panel,
   verify, post INTEGRATED.

The CPU panel (Track A) is independent of B and can land first (Prometheus + `sys_CpuLoad`
already exist), giving an immediate "GG is bored" readout even before the bigger load tier.

## Testing

- **Plugin (B):** SpecAssembler/manifest unit tests (node pool + nodeSelector/toleration),
  the migration step (`ConfigMigrationTest`), `validateDemoConfiguration` regression.
- **Demo backend (A):** `PrometheusCpuService` query/parse test (mirror
  `GeneratorMetricsServiceTest`); pods-only generator-control templating test.
- **Frontend (A):** type-check + manual (no harness, by decision).
- **E2E:** deploy generator element → step pods to ~24 → UI shows ~10–15k tps with GG CPU
  ~20–30%; teardown removes the generator pool with no residue.

## Open items / risks

- **Generator element name + exact field surface** — B's call (this doc states the demo's
  needs; the toolkit owns the element design).
- **Autoscaler latency** — adding pods may briefly outrun node scale-up; pods sit Pending
  until a node joins. Acceptable for a demo; mention in presenter notes.
- **`sys_CpuLoad` labels** — confirm the per-node label (instance/host) for the `avg()` query
  during implementation.
