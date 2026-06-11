# Track B Kickoff — Toolkit DB/CDC Element-Type Hardening

Short-form brief to start the parallel toolkit-enhancement effort. Full detail in
[toolkit-handoff.md](toolkit-handoff.md).

## Mission

Harden the GridGain Demo Toolkit (`gridgain-demo-gradle-plugin`) so its
Postgres / MariaDB / Kafka / Debezium support is first-class — **in parallel with, and
without disrupting,** the `mainframe-payments-demo` (public presentation < 2 weeks out).

## Where to work

- Worktree: `DemoGradleProject/gridgain-demo-gradle-plugin-toolkit` on branch
  **`feat/toolkit-db-cdc-hardening`** (cut from frozen baseline `a4a1e245`; pushed to
  `GridGain-Demos/gridgain-demo-gradle-plugin`).
- **Do NOT touch** `mainframe-payments-demo` or the demo's plugin checkout
  (`DemoGradleProject/gridgain-demo-gradle-plugin` @ `feat/mainframe-payments-elements`) —
  the demo live-builds against it via `includeBuild`.
- Git: feature branches off `feat/toolkit-db-cdc-hardening`; never commit to the toolkit `main`; push freely.

## Task #1 — the demo's blocker

Make the `cdc_connectors` element deploy + register **custom** Kafka Connect connectors, not
just the Debezium source. Today it brings up Kafka + stock `debezium/connect:3.0.0.Final` and
registers only `mainframe-to-gg-source`. The demo's two custom connectors are built but never
loaded/registered, so Kafka→GG and GG→Kafka don't run (GG + MariaDB panels stay empty):

- `cdc-sink` → `com.gridgain.demo.payments.cdcsink.GgSinkConnector` (Kafka→GG)
- `gg-cache-publisher` → `com.gridgain.demo.payments.ggcachepublisher.GgSourceConnector` (GG→Kafka)

These connector modules live in the **demo** repo (`mainframe-payments-demo/cdc-sink`,
`/gg-cache-publisher`) and already build **fat/shadow JARs** (the `com.gradleup.shadow` plugin
bundles ignite-core, Kafka Connect, and the JDBC drivers — loadable via plugin-path as-is). The
**toolkit** work is the *generic mechanism*: let `cdc_connectors` declare custom connector
plugins (custom Connect image, or plugin-path mount) and register their configs via the Connect
REST API — generalized, not demo-hardcoded.

## Contract — must NOT break

The demo's `demo-config.yaml` must keep validating + deploying:

- config schema for `databases` / `cdc_connectors` as used there; task names + `-P` params
  (`deployDatabase -PdatabaseName`, `deployCdcConnector -PconnectorName`, …);
  `schema_version` 13 (or add `MigrateV13toV14` + `ConfigMigrationTest`).
- **Regression gate:** `./gradlew validateDemoConfiguration` against the demo config stays green.

## How to work (workspace rules — see `DemoGradleProject/CLAUDE.md`)

TDD (tests first); no nullable types without approval; no parse-time defaults/fallbacks; rich
error messages; no `org.gradle.*` in `core/`; SnakeYAML pinned `1.33`; bump
`CURRENT_SCHEMA_VERSION` + add migration + `ConfigMigrationTest` on any breaking config change.
Re-evaluate `src/main/resources/tooling/gke_tool_requirements.yaml` — its gcloud/kubectl upper
bounds were widened expediently for the demo.

## Coordination

Cross-track comms run through **`mainframe-payments-demo/docs/track-coordination.md`** — the
single shared log. Read it at the start of each work session and append entries (you may write
*that one file* in the demo repo, nothing else there). It defines the entry types
(REQUEST / CONTRACT-CHANGE / READY / INTEGRATED / BLOCKER) and the integration protocol for
landing a toolkit change in the demo. When you have a pinnable commit for Task #1, post a
**READY** entry there.

## Out of scope

Don't merge to toolkit `main` until after the demo. Demo-critical plugin fixes land on the
frozen `feat/mainframe-payments-elements` (demo re-pins) — not cherry-picked from this branch.
