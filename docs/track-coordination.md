# Track Coordination — Demo (A) ↔ Toolkit (B)

Two parallel efforts share this log to stay in sync without blocking each other:

- **Track A — Demo development** — `mainframe-payments-demo`, branch `main`. *Consumes* the toolkit.
- **Track B — Toolkit hardening** — `gridgain-demo-gradle-plugin`, branch `feat/toolkit-db-cdc-hardening`. *Produces* toolkit capabilities.

This file is the **single source of truth** for cross-track communication. Both tracks
**read it at the start of each work session** and **append** entries to the Log (bottom).

> **Write rule.** Track B's *only* permitted write in the `mainframe-payments-demo` repo is
> **this file** (it's a doc — it can't break the demo build); the rest of the demo repo is
> off-limits to B, and the toolkit source is off-limits to A. If a process can't reach the
> other repo, the human relays the entry.

## Current integration state

- **Demo pins the toolkit at:** plugin commit `a4a1e245` on `feat/mainframe-payments-elements`,
  consumed via `includeBuild`. Bump this only through the Integration Protocol below.
- **Contract (must-not-break):** see [toolkit-handoff.md §5](toolkit-handoff.md). Regression
  gate both tracks can run: `./gradlew validateDemoConfiguration` against the demo config — must stay green.
- **Open from B:** custom-connector deployment (charter Task #1) — blocks the demo's GG + MariaDB panels.

## Entry types (prefix each Log entry)

- **REQUEST** — one track asks the other for something (include *why* and *by-when* if it matters).
- **QUESTION / DECISION** — a question and, once resolved, its answer.
- **CONTRACT-CHANGE** — B announces a change to config schema / task interface / behavior the demo
  depends on. **Post before merging it.** Include migration notes if `schema_version` bumps.
- **READY** — B has shipped a capability. Include: (1) the plugin commit/version to pin, (2) any
  required demo-config edits, (3) how to verify.
- **INTEGRATED** — A has picked up a READY item (re-pinned, redeployed, verified). Note the new pin.
- **BLOCKER** — anything stopping a track; name who can unblock.

## Integration protocol (B ships → A picks up)

1. **B** posts a **READY** entry: new plugin commit, required demo-config edits, verification steps.
2. **A** advances the demo's pinned plugin commit to it, applies any demo-config edits, runs
   `validateDemoConfiguration`, redeploys the affected element(s), and verifies the relevant panels.
3. **A** posts **INTEGRATED** with the new pinned commit — or **BLOCKER** if it fails.
4. **B** does **not** merge to the toolkit's `main` until after the demo presentation. Demo-critical
   plugin fixes during the window land on `feat/mainframe-payments-elements` and A re-pins.

## Log (append newest at the bottom)

- **2026-06-11 · STATE (A)** — Bifurcation set up. Demo consolidated on `main` (Control Center
  removed, $0 start state, UI + keyboard nav). Toolkit baseline frozen at `a4a1e245`; Track-B
  branch `feat/toolkit-db-cdc-hardening` created with charter + kickoff. Demo is live for the
  Mainframe panel only.
- **2026-06-11 · REQUEST (A→B)** — Custom-connector deployment (charter Task #1) is the top need:
  it unblocks the GG + MariaDB panels (Kafka→GG via `cdc-sink`, GG→Kafka via `gg-cache-publisher`).
  Post a **READY** here when a pinnable commit exists.
- **2026-06-11 · QUESTION (A→B)** — The MariaDB **analytics** schema has FK constraints
  (`fk_tx_account`, `fk_tx_product`, `fk_account_customer`). The demo's reset code had assumed
  "FKs dropped at deploy" — now fixed on the A side (reset disables `FOREIGN_KEY_CHECKS` while
  truncating). **Heads-up for the `gg-to-mariadb` sink:** out-of-order CDC events (e.g. a
  transaction arriving before its account) will hit FK violations on the analytics side. When you
  wire the sink, decide: drop the FKs on the MariaDB analytics schema (matches the apparent
  original intent), upsert/retry, or apply in dependency order. Not blocking the demo now (sink
  not yet deployed).
- **2026-06-11 · NOTE (A→B)** — Correction to charter §1a + kickoff (now updated): the demo's
  connector JARs (`cdc-sink`, `gg-cache-publisher`) are **already fat/shadow** (`com.gradleup.shadow`;
  ~7.5k bundled classes incl. ignite-core + Kafka Connect + JDBC drivers) — loadable into Kafka
  Connect via plugin-path **as-is**, so no dependency-bundling is needed in the toolkit mechanism.
- **2026-06-11 · READY (B→A)** — Charter Task #1 (custom Kafka Connect connector deployment) is
  pinnable at plugin commit `2f29a328` on `feat/toolkit-db-cdc-hardening`. Five commits stacked
  over the frozen baseline: schema (`5b744f2f`) → DTO/spec/assembler (`56690b25`) → templates
  (`1e849a49`) → plugin deploy/destroy (`06fe90a9`) → docs (`2f29a328`). `schema_version` stays
  at 13 — purely additive, no migration. `./gradlew validateDemoConfiguration` against the demo's
  unmodified `demo-config.yaml` continues to pass (covered by an explicit
  `acceptsMinimalCompleteEntry` regression test in the schema-test class).

  **What landed.** Three optional fields on each `cdc_connectors.<entry>`:
  `kafka_connect.plugins[]` (URL + sha256 per JAR, init-container fetched into the Connect
  plugin.path under `subPath: plugins`), `kafka_connect.jvm_opts[]` (joined as `KAFKA_OPTS`),
  and top-level `connectors[]` (additional Connect REST registrations beyond the auto-generated
  Debezium source, with `__PLACEHOLDER__` → secretKeyRef substitution). Plugin PVC + per-connector
  Jobs/ConfigMaps wired into the deploy + destroy command lists with stale-delete idempotence
  and async readiness waits. Full element-type reference:
  [`docs/cdc-connectors.md`](https://github.com/GridGain-Demos/gridgain-demo-gradle-plugin/blob/feat/toolkit-db-cdc-hardening/docs/cdc-connectors.md).

  **Demo-config edits required** under `cdc_connectors.mainframe-to-gg`:
  - `kafka_connect.plugins[]` with two entries (`cdc-sink`, `gg-cache-publisher`), each with
    `url:` and `sha256:`. **See open question below — Track A picks the publish location.**
  - `kafka_connect.jvm_opts[]` with the GG8 `--add-opens` flags (per
    `project_gg8_jdk17_add_opens`) so the GG JDBC driver initialises in the Connect pod (JDK
    17/21).
  - Top-level `connectors[]` list under the entry with two items: `cdc-sink` (Kafka→GG sink,
    `dialect: gg8`, JDBC URL pointing at the in-cluster GG8 thin-client service) and
    `gg-cache-publisher` (GG→Kafka source, `gg.client.addresses` + `gg.caches`).

  Concrete YAML snippets in the doc above under
  [Additional connectors (`connectors[]`)](https://github.com/GridGain-Demos/gridgain-demo-gradle-plugin/blob/feat/toolkit-db-cdc-hardening/docs/cdc-connectors.md#additional-connectors-connectors).

  **Open coordination question (A internal).** The toolkit fetches each `plugins[*]` JAR from a
  URL at init-container time and verifies the sha256. *Where will the demo publish
  `cdc-sink-0.0.1-SNAPSHOT-all.jar` and `gg-cache-publisher-0.0.1-SNAPSHOT-all.jar`?* GitHub
  Releases of `mainframe-payments-demo` is the obvious place (GKE nodes have default internet
  egress for public release URLs). Once published, fill `url:` and the sha256
  (`sha256sum <module>/build/libs/<module>-0.0.1-SNAPSHOT-all.jar`).

  *Alternative if a public release isn't viable:* bake the JARs into a custom Connect image
  (Path A in the doc) — set `kafka_connect.image` to that custom tag and omit
  `kafka_connect.plugins[]`. The toolkit needs no other changes for this path.

  **Verification (Track A side):**
  1. Re-pin the demo's `includeBuild` target to `feat/toolkit-db-cdc-hardening @ 2f29a328`
     (the demo's plugin worktree is currently frozen at `a4a1e245` — either bump it to the new
     tip or switch the demo to a `mavenLocal()` pin per the toolkit-handoff §3 "optional stronger
     isolation" path).
  2. Apply the demo-config edits above with the published URLs+sha256.
  3. `./gradlew validateDemoConfiguration` — stays green.
  4. `./gradlew teardownCdcConnector -PconnectorName=mainframe-to-gg` (clean slate) →
     `./gradlew deployCdcConnector -PconnectorName=mainframe-to-gg`.
  5. Confirm:
     - Connect pod reaches `Ready` (init container `fetch-plugins` succeeded on both JARs —
       `kubectl describe pod` shows the init-container log and exit code).
     - `kubectl get jobs -n cdc-pipeline` shows three Completed `*-register` Jobs:
       `mainframe-to-gg-register` (Debezium), `mainframe-to-gg-cdc-sink-register`,
       `mainframe-to-gg-gg-cache-publisher-register`.
     - Mainframe-side transaction propagates to GG (existing Postgres → GG CDC path).
     - GG-side transaction appears on the Kafka topic published by `gg-cache-publisher`
       (`from-gg.public.*` or `from-mf.public.*` depending on `source` column), and `cdc-sink`
       consumes it back into GG / mainframe-proxy as the demo's flow expects.

  Post **INTEGRATED** once verified — or **BLOCKER** with the failing step.

  **Out of scope, flagged for later.** The MariaDB FK QUESTION (2026-06-11) still applies when a
  third `connectors[]` entry is added to sink GG events into the MariaDB analytics schema
  (re-using the `cdc-sink` plugin with `dialect: mariadb`). Decide upfront — drop FKs on
  analytics, upsert/retry, or apply in dependency order — before that wiring lands.
- **2026-06-11 · CLEANUP** — Bifurcation collapsed back onto `main`. There was no plugin
  capability the demo consumed that wasn't equally plugin-mainline material, so the
  Track-A/Track-B split had been imposing coordination overhead for no structural benefit.
  Actions taken:
  - `origin/main` fast-forwarded from `d5485d4a` to `2f29a328` (20-commit FF, no rewrite).
    Includes all Track-A `databases`/`cdc_connectors` element-type work *and* Track-B's
    `kafka_connect.plugins[]`/`jvm_opts[]`/top-level `connectors[]` additions.
  - Demo's plugin checkout (`gridgain-demo-gradle-plugin/`) switched from
    `feat/mainframe-payments-elements` → `main`. Both consumers (demo + toolkit-dev) now
    build against `main`.
  - Bifurcation docs (`docs/toolkit-kickoff.md`, `docs/toolkit-handoff.md`) replaced with
    a single short `docs/track-b-history.md` recording what happened.
  - Final post-cleanup tip is plugin commit `891eb808` (= `2f29a328` + one docs-cleanup
    commit). **The pin in the READY entry above should be read as `main` rather than
    `feat/toolkit-db-cdc-hardening@2f29a328` — same content, simpler ref.**
  - Branches `feat/toolkit-db-cdc-hardening` and `feat/mainframe-payments-elements`
    deleted from origin and locally. Toolkit worktree
    (`gridgain-demo-gradle-plugin-toolkit/`) removed.
  - No code change on the demo side. Track A's local `feat/mainframe-payments-elements`
    plugin checkout had been fast-forwarded to `2f29a328` already (locally `[ahead 8]`),
    so the switch to `main` was a no-op for the working tree.

  The READY-entry verification checklist still applies; just substitute `main` for the
  pin. Post INTEGRATED after the deploy reproduces the GG-side ↔ Kafka ↔ GG path through
  the two custom connectors.
- **2026-06-12 · INTEGRATED (A)** — Custom Kafka Connect CDC integration is live and verified
  end-to-end. The GG panel now shows 5 customers + 10 products + 5 account balances; all three
  connectors (`mainframe-to-gg-source`, `cdc-sink`, `gg-cache-publisher`) report RUNNING with
  RUNNING tasks; a GG-side account write publishes to `from-mf.public.account`.

  **What it took (demo side, on `main`):**
  - *Custom Connect image was arm64-only* → `ImagePullBackOff` on amd64 GKE. Rebuilt multi-step
    for `linux/amd64`; now `ghcr.io/escapedcanadian/mainframe-payments-connect:0.0.3`
    (`connect-image/Dockerfile` now tracked). **Build amd64 explicitly** — a plain `docker build`
    on an Apple-silicon laptop publishes an unrunnable image.
  - *cdc-sink converter mismatch* — the Debezium source emits schemaless JSON, so the sink needs
    `value/key.converter.schemas.enable=false` (added to the `cdc-sink` connector config).
  - *gg-cache-publisher cache names* — `gg.caches` must be the GG cache names `SQL_PUBLIC_<TABLE>`
    (the task strips the prefix for the topic), not bare `Customer`.
  - *§6 account colocation* — `account` is now composite PK `(account_id, customer_id)` +
    `affinity_key: customer_id`; `Account ⋈ Customer ON customer_id` returns all rows WITHOUT
    `distributedJoins`. Both custom connectors were made composite-PK-aware (introspect the PK via
    JDBC metadata; gg-cache-publisher extracts PK fields from the composite `BinaryObject` key).
  - *Debezium replication-user secret* (`mainframe-to-gg-debezium-auth`) is recreated by
    `scripts/create-demo-secrets.sh` and must be re-run after any CDC teardown (teardown deletes
    the namespace and the secret with it).

  **Track-B (plugin) fixes — on branch `fix/cdc-connector-state-backcompat`, NOT yet merged to
  plugin `main` (per the no-merge-until-after-demo protocol):**
  1. **BUG** `fix(state)` — the `connectors`/`kafka_connect.plugins`/`kafka_connect.jvm_opts` fields
     added by Task #1 lacked `emptyList()` defaults on the deployed spec, while
     `CURRENT_SCHEMA_VERSION` stayed at 9. Any pre-existing `deployment.yaml` then failed
     deserialization → `Corrupted state` bricking every CDC task. Fixed with defaults +
     `CdcConnectorStateBackCompatTest`. **B should fold this into Task #1 before merging.**
  2. **FEATURE** `feat(data-model)` — data-model `affinity_key` support (GG8 `WITH "AFFINITY_KEY"`,
     GG9 `COLOCATE BY`, validated against the PK). Needed for §6 colocation.

  **Known follow-ups (not blocking the GG/MariaDB panels' read path):**
  - Data-model zone `replicas: 1` (no backups) → a GG node recycle (e.g. GKE master upgrade /
    node-pool churn) marks partitions LOST; reads degrade and writes fail until
    `control.sh --cache reset_lost_partitions` is run (done once here). Consider `replicas: 2`.
  - The outbound `gg-to-postgres` / `gg-to-mariadb` JDBC sinks are still not deployed; the MariaDB
    FK QUESTION (2026-06-11) applies when they land.
  - `transaction` colocation (§6) is not done — the table has no `customer_id` column to colocate
    on; only `account` (which the balances join needs) was colocated.
- **2026-06-12 · UPDATE (A)** — Resolved the `replicas: 1` resilience follow-up. The PAYMENTS zone
  is now `replicas: 2`. Required a third Track-B plugin commit on
  `fix/cdc-connector-state-backcompat`: `feat(data-model)` renders a zone's replicas as a GG8
  `CREATE TABLE ... WITH "BACKUPS=<replicas-1>"` clause (combined with `AFFINITY_KEY`) — previously
  GG8 ignored zone replicas entirely (no `CREATE ZONE`), so caches were created with 0 backups and a
  single node recycle lost partitions. All four `SQL_PUBLIC_*` caches now report `BACKUPS=1`;
  dropped+recreated+re-snapshotted, data and balances verified. **The plugin branch now carries
  three commits (deserialize-backcompat, affinity_key, backups) for B to fold into Task #1 / merge.**
- **2026-06-12 · DECISION (A)** — `transaction` colocation (§6) is **deferred**, intentionally, not
  just unimplemented. To colocate `transaction` on `customer_id` it would need that column as a PK
  member (affinity must be a PK column), so every writer must supply it. Curated/CDC/phase-3
  (`executePurchase`) transactions carry a real customer and would be fine — but the **data
  generator can't produce a `customer_id` consistent with a generated transaction's account**: it
  only does immediate-`parent-fk-ref`, and its F4 `MultiFkToSameParentValidator` treats multi-FK to
  *different* parents as independent (account vs customer chosen separately). Consistent colocation
  would require a new data-generator value source (transitive / copy-parent-column ref). Since **no
  GG query joins `transaction`** today (balances = Account⋈Customer, already colocated; phase-6
  analytics run on MariaDB), the cost isn't justified yet. Revisit options if a GG transaction-join
  query appears: (1) add the generator feature for full consistency, or (2) colocate and accept
  independently-chosen `customer_id` on phase-5 *generated* load only (illustrative data).
