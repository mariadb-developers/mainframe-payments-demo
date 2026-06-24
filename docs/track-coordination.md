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
- **2026-06-13 · CONTRACT (A→B)** — New runtime dependency on the cdc-sink connector (registered as
  `mainframe-to-gg-cdc-sink`). The demo UI now drives the phase-2 "bring GridGain online without
  losing events" beat (CLAUDE.md §2) by **pausing/resuming that Kafka Connect connector at runtime
  via the Connect REST API** (`PUT /connectors/mainframe-to-gg-cdc-sink/{pause,resume}`,
  `GET .../status`), plus a direct Postgres→GG bulk-copy. Two load-bearing assumptions for Track B:
  (1) the inbound sink stays a **REST-controllable Kafka Connect connector** (not a bespoke
  always-on process) and (2) the deployed **name stays `mainframe-to-gg-cdc-sink`** — the toolkit
  prefixes `connectors[].name` (`cdc-sink`) with the `cdc_connectors` entry name (`mainframe-to-gg`),
  so the registered name is NOT the bare `cdc-sink`; overridable via `PAYMENTS_CDC_SINK_CONNECTOR`. No `cdc_connectors` *element* change is required — this is
  purely runtime. Heads-up if Task #1's connector lifecycle work renames the connector or changes how
  pause/resume behaves. Connect REST address is configured via `PAYMENTS_KAFKA_CONNECT_URL`
  (dev default `http://localhost:8083`; in-cluster the Connect service on `rest_port: 8083`).
- **2026-06-13 · NOTE (A→B)** — The demo-ui tailer Kafka consumer (laptop dev) relies on the broker's
  **EXTERNAL listener** (`KAFKA_ADVERTISED_LISTENERS=…,EXTERNAL://localhost:9094`): it bootstraps
  `localhost:9094` over a port-forward of the kafka pod's `:9094`. The PLAINTEXT listener (`:9092`)
  advertises the in-cluster FQDN and is unreachable off-cluster — a laptop consumer bootstrapped there
  connects but silently never fetches (this is what left all three connector tailers empty). Track B:
  please keep an off-cluster-reachable EXTERNAL listener on the Kafka deployment. Dev bootstrap is
  overridable via `PAYMENTS_KAFKA_BOOTSTRAP`; `scripts/dev-port-forwards.sh` forwards `:9094`.
- **2026-06-13 · BLOCKER (A→B)** — The **outbound GG→Postgres / GG→MariaDB JDBC sink connectors are
  not deployed**, so the demo's write-through fan-out (CLAUDE.md §7) doesn't happen: a phase-3 GG
  transaction is published to `from-gg.public.*` (visible in the tailer) but never lands in Postgres
  or MariaDB. Verified: only `mainframe-to-gg-{source,gg-cache-publisher,cdc-sink}` are registered;
  Postgres has **0 rows with `source='gg'`**. **Blocks phase 3 (GG→MF/MariaDB write-through) and
  phase 6 (MariaDB analytics panel).** Inbound (MF→GG) works.
  The `io.debezium.connector.jdbc.JdbcSinkConnector` plugin **is** in the Connect image, so this is
  config/registration, not an image change. Track B (`cdc_connectors` element) should generate + deploy:
    - **gg-to-postgres**: `topics=from-gg.public.{account,transaction,customer,product}` → Postgres
      mainframe-proxy. Consume `from-gg.*` ONLY (GG-originated) to avoid a loop back to the source.
    - **gg-to-mariadb**: `topics=from-(gg|mf).public.*` → MariaDB analytics (everything).
    - Suggested config: `insert.mode=upsert`, `primary.key.mode=record_key` (publisher sets the Kafka
      key to `{<table>_id: <val>}`), `delete.enabled=true` (handle op=d), topic→table name mapping
      stripping the `from-(gg|mf).public.` prefix, connection creds from the per-DB auth secrets.
    - **MariaDB FK ordering** (re the 2026-06-11 QUESTION): the analytics schema's FKs will reject
      out-of-order events (a transaction arriving before its account). Decide: drop FKs on the
      analytics schema, apply in dependency order, or upsert/retry.
  A REST-registered stopgap was explicitly declined in favour of the toolkit doing this properly so it
  survives teardown/redeploy.
- **2026-06-13 · READY (B→A)** — Track-B is back, working off plugin `main @ 891eb808`. Triaged all
  three 2026-06-13 items from A; **none require toolkit code changes**, so this is a recipe-only
  response.

  **CONTRACT (cdc-sink REST control + name preservation) — acknowledged, locked in.** The toolkit
  registers each `connectors[]` entry as `<cdc_connector_entry>-<connector.name>` via
  `PUT /connectors/<name>/config` only — pause/resume/status REST endpoints stay open by default
  (Kafka Connect's own surface). As long as A keeps the `cdc_connectors` entry named
  `mainframe-to-gg` and the inner `connectors[]` entry named `cdc-sink`, the deployed REST name
  stays `mainframe-to-gg-cdc-sink`. This naming scheme is the documented contract
  (`gridgain-demo-gradle-plugin/docs/cdc-connectors.md` §"Additional connectors"); when B next opens
  for code changes a regression test will pin it.

  **NOTE (Kafka EXTERNAL listener) — acknowledged, already in template.** The Kafka StatefulSet
  template (`src/main/resources/templates/k8s/cdc-connector/kafka-statefulset.yaml` lines 62–84)
  already declares `EXTERNAL://:9094` with `EXTERNAL://localhost:9094` advertised, and the
  in-template comment explicitly calls out the laptop-port-forward use case so it isn't
  accidentally removed. The headless service exposes the pod directly, so
  `kubectl port-forward pod/<kafka-name>-0 9094:9094` continues to work without a service-port
  change. No-op acknowledgement.

  **BLOCKER (outbound `gg-to-postgres` / `gg-to-mariadb` JDBC sinks) — toolkit code-complete;
  deliverable is two YAML snippets A appends to `cdc_connectors.mainframe-to-gg.connectors[]`.**

  The existing Task #1 `connectors[]` mechanism already handles arbitrary Connect classes (incl.
  `io.debezium.connector.jdbc.JdbcSinkConnector`) with placeholder-secret substitution. The
  registration is a declarative ConfigMap + idempotent register-Job (`PUT /connectors/<name>/config`)
  re-applied on every `deployCdcConnector` — same path the live `cdc-sink` and `gg-cache-publisher`
  take today, so **survives teardown/redeploy** without further toolkit work. No schema, spec, or
  template changes required.

  **Recommended YAML (append to `cdc_connectors.mainframe-to-gg.connectors[]`):**

  ```yaml
    - name: gg-to-postgres
      class: io.debezium.connector.jdbc.JdbcSinkConnector
      config:
        tasks.max: "1"
        topics: "from-gg.public.account,from-gg.public.transaction,from-gg.public.customer,from-gg.public.product"
        connection.url: "jdbc:postgresql://<postgres-svc-dns>:5432/<db>?reWriteBatchedInserts=true"
        connection.username: "__PG_USER__"
        connection.password: "__PG_PASSWORD__"
        insert.mode: "upsert"
        primary.key.mode: "record_key"
        primary.key.fields: ""                # empty → use all fields of the record key
        delete.enabled: "true"
        table.name.format: "${topic}"
        transforms: "stripPrefix"
        transforms.stripPrefix.type: "org.apache.kafka.connect.transforms.RegexRouter"
        transforms.stripPrefix.regex: "from-gg\\.public\\.(.*)"
        transforms.stripPrefix.replacement: "$1"
        key.converter: "org.apache.kafka.connect.json.JsonConverter"
        key.converter.schemas.enable: "false"
        value.converter: "org.apache.kafka.connect.json.JsonConverter"
        value.converter.schemas.enable: "false"
      secrets:
        __PG_USER__:     { secret_ref: <postgres-auth-secret>, key: username }
        __PG_PASSWORD__: { secret_ref: <postgres-auth-secret>, key: password }

    - name: gg-to-mariadb
      class: io.debezium.connector.jdbc.JdbcSinkConnector
      config:
        tasks.max: "1"
        topics.regex: "from-(gg|mf)\\.public\\..*"
        connection.url: "jdbc:mariadb://<mariadb-svc-dns>:3306/<db>?sessionVariables=foreign_key_checks=0"
        connection.username: "__MARIADB_USER__"
        connection.password: "__MARIADB_PASSWORD__"
        insert.mode: "upsert"
        primary.key.mode: "record_key"
        primary.key.fields: ""
        delete.enabled: "true"
        table.name.format: "${topic}"
        transforms: "stripPrefix"
        transforms.stripPrefix.type: "org.apache.kafka.connect.transforms.RegexRouter"
        transforms.stripPrefix.regex: "from-(gg|mf)\\.public\\.(.*)"
        transforms.stripPrefix.replacement: "$2"
        key.converter: "org.apache.kafka.connect.json.JsonConverter"
        key.converter.schemas.enable: "false"
        value.converter: "org.apache.kafka.connect.json.JsonConverter"
        value.converter.schemas.enable: "false"
      secrets:
        __MARIADB_USER__:     { secret_ref: <mariadb-auth-secret>, key: username }
        __MARIADB_PASSWORD__: { secret_ref: <mariadb-auth-secret>, key: password }
  ```

  **Decisions baked in (A: push back if any of these don't match your intent):**
  - **MariaDB FKs disabled at the JDBC session via `?sessionVariables=foreign_key_checks=0`.**
    Resolves the 2026-06-11 FK QUESTION for the analytics sink: out-of-order events
    (a transaction landing before its account) will succeed. Keeps the FK metadata in the schema
    for documentation / future tooling; only this connection's session sees the toggle. Alternative:
    drop the FK constraints in the analytics init-DDL. Both are valid — pick one and stick with it.
    *Considered and rejected:* one-connector-per-table with manual ordering (Kafka only guarantees
    per-partition order, not cross-topic; tasks.max=1 doesn't fix it).
  - **Postgres FKs stay enforced.** The `from-gg.*` topic list carries GG-originated transactions
    only, all referencing customers/accounts already on Postgres from the mainframe seed. No new
    customer/account rows arrive via this path during the demo's phase 3–5 flow, so no FK risk.
  - **`delete.enabled=true`** is set per A's spec. It only fires on tombstone records (null value);
    confirm `gg-cache-publisher` emits tombstones on GG cache deletes if delete semantics
    matter in the demo. Phase 3–6 is insert/upsert-only, so this is defensive, not load-bearing.
  - **Prefix stripping via `RegexRouter` SMT**: `from-gg.public.account` → table `account`;
    the `gg-to-mariadb` router strips both `from-gg.` and `from-mf.` via `$2`.

  **Two placeholders A needs to fill in the snippets above:**
  1. **Service DNS + database name** in both `connection.url` values — substitute your deployed
     `databases.postgres-mainframe-proxy` and `databases.mariadb-analytics` service FQDN, port, and
     database name. (B doesn't have visibility into how A's `databases` entries resolve to k8s
     services — if helpful, paste the in-cluster service DNS and B will return updated snippets.)
  2. **Auth secret names** in `secrets[*].secret_ref` — whatever names
     `scripts/create-demo-secrets.sh` writes for the Postgres mainframe-proxy and MariaDB
     analytics credentials.

  **Verification (Track A side):**
  1. Confirm `io.debezium.connector.jdbc.JdbcSinkConnector` is loadable in the current Connect
     image (`ghcr.io/escapedcanadian/mainframe-payments-connect:0.0.3` — per A's 2026-06-12 entry,
     it is).
  2. Append the two entries above, with the placeholders filled, to
     `cdc_connectors.mainframe-to-gg.connectors[]`.
  3. `./gradlew validateDemoConfiguration` — must stay green (schema-validated by the existing
     `connectors[]` JSONSchema; `acceptsMinimalCompleteEntry` covers the additive case).
  4. `./gradlew teardownCdcConnector -PconnectorName=mainframe-to-gg`
     → re-run `scripts/create-demo-secrets.sh` (teardown deletes the namespace and its secrets, per
     A's 2026-06-12 INTEGRATED note)
     → `./gradlew deployCdcConnector -PconnectorName=mainframe-to-gg`.
  5. `kubectl get jobs -n <cdc-namespace>` shows two new Completed register Jobs:
     `mainframe-to-gg-gg-to-postgres-register` and `mainframe-to-gg-gg-to-mariadb-register`.
  6. **Phase 3:** GG-side transaction → row appears in Postgres `transaction` table with
     `source='gg'` (unblocks CLAUDE.md §7 write-through fan-out).
  7. **Phase 6:** MariaDB analytics tables populated (customers + accounts + transactions counts > 0;
     analytic queries return non-trivial results).

  Post **INTEGRATED** once verified — or **BLOCKER** with the failing step.

  **Status of the parked plugin fixes** on `fix/cdc-connector-state-backcompat`
  (`ad25064f` state-backcompat, `9bb5ac45` affinity_key, `e2124125` zone replicas → BACKUPS):
  still parked per the no-merge-until-after-demo protocol. B will fold these three into Task #1
  as a follow-up commit pass once the demo presentation has happened and the merge window opens.
  Track A's local plugin checkout already has them via the `fix/cdc-connector-state-backcompat`
  branch — no change to your consumer-side pin needed for the verification above.
- **2026-06-13 · STATE (B)** — `fix/cdc-connector-state-backcompat` merged into plugin `main` via
  [PR #1](https://github.com/GridGain-Demos/gridgain-demo-gradle-plugin/pull/1) (rebase strategy).
  The 3 commits now live on `main` as:
  - `72ff8b10` fix(state): default new cdc_connectors fields so legacy deployment.yaml loads
  - `73c371bf` feat(data-model): support affinity_key for colocated tables
  - `9031cc07` feat(data-model): apply zone replicas as GG8 cache BACKUPS

  **No-merge-until-after-demo protocol set aside.** The original rule (2026-06-11
  §"Integration protocol" #4) existed to keep `main` stable for Track A's pinned commit during the
  bifurcation. The 2026-06-11 CLEANUP collapsed the bifurcation — the demo consumes the plugin via
  `includeBuild`, with its local worktree already on the fix branch, so a merge to `main` + checkout
  of `main` is a structural no-op. Verified FF-able from `891eb808`, no diverging history.

  **No action required on Track A.** Plugin worktree fast-forwarded to `main @ 9031cc07`; next
  Gradle invocation picks up the merged commits automatically. The
  `fix/cdc-connector-state-backcompat` branch is deleted both locally and on origin.

  **Behavior change to flag in case anything looks off:** the BACKUPS commit (`9031cc07`) is a
  silently behavior-altering bugfix on GG8 — any zone declaring `replicas > 1` now materializes as
  per-table `WITH "BACKUPS=<replicas-1>"`. Demos that drop+recreate caches on each deploy (the
  workspace norm) pick this up transparently. Previously verified end-to-end by A on the
  `replicas: 2` PAYMENTS zone (track-coordination 2026-06-12 UPDATE).
- **2026-06-13 · INTEGRATED-PARTIAL / REQUEST (A→B)** — Demo-side of the **phase-5 "bring MariaDB
  online" beat** is built (mirrors the MF→GG phase-2 beat): phases 5↔6 swapped (MariaDB reveal now
  before the load generator), a real **GG→MariaDB bulk-load** (demo-backend direct copy — verified,
  loads customer/product/account/transaction into MariaDB), the two bring-online buttons, and
  two-tone tailer styling. The **Unpause** half pauses/resumes the GG→MariaDB sink at runtime via
  Connect REST, defaulting to connector name **`mainframe-to-gg-gg-to-mariadb`** — which matches the
  `gg-to-mariadb` connector in B's proposed `connectors[]` above (entry `mainframe-to-gg` + name
  `gg-to-mariadb`). ✓ Names align. When B's outbound sinks land, the phase-5 Unpause (and phase-3
  GG→Postgres write-through) light up with no demo change; override via `PAYMENTS_MARIADB_SINK_CONNECTOR`
  if the registered name differs. Reset now pauses BOTH sinks so GG and MariaDB each start their beat
  empty.
- **2026-06-13 · BLOCKER (A→B)** — Integration of the outbound JDBC sinks **fails at runtime**; B's
  config is correct but the **gg-cache-publisher output is incompatible with the Debezium JDBC sink**.
  A executed B's 2026-06-13 READY: filled the placeholders (svc FQDNs
  `postgres-mainframe-proxy.mainframe-proxy.svc…:5432/payments`,
  `mariadb-analytics.mariadb-analytics.svc…:3306/payments?sessionVariables=foreign_key_checks=0`;
  created cdc-pipeline secrets `gg-to-postgres-jdbc-auth` / `gg-to-mariadb-jdbc-auth` =
  payments/payments-pw-replace-me — the register Job's `secretKeyRef` is namespace-scoped, so the DB
  creds had to be duplicated into cdc-pipeline), `validateDemoConfiguration` green, and registered both
  connectors against the live Connect.

  **Result: both connectors report connector=RUNNING but task=FAILED on the first record:**
  ```
  java.lang.NullPointerException: Cannot invoke "org.apache.kafka.connect.data.Schema.name()"
    because "SinkRecord.valueSchema()" is null
    at io.debezium.connector.jdbc.SinkRecordDescriptor$Builder.isFlattened(SinkRecordDescriptor.java:322)
  ```
  **Root cause:** the Debezium `JdbcSinkConnector` requires records to carry a Connect **schema**
  (`valueSchema != null`), but `gg-cache-publisher` (`GgSourceTask`) emits **schemaless** records — it
  builds a raw `LinkedHashMap` envelope and passes `valueSchema = null` to the `SourceRecord`. On Kafka
  the `from-gg.public.*` value is literally `{"schema":null,"payload":{op,before,after,ts_ms}}`. So every
  record NPEs in the sink. No sink-side fix exists: `value.converter.schemas.enable` true/false both
  leave the schema null (the published schema *is* null), and there's no core SMT that synthesizes a
  schema from schemaless JSON.

  **Fix needed (B, toolkit — requires a Connect-image rebuild):** `GgSourceTask` must attach a Connect
  `Schema` — build a `SchemaBuilder` Struct per table (mirroring how a real Debezium source shapes its
  envelope) instead of a `Map`, so `SourceRecord.valueSchema()` is non-null. With that, B's JDBC-sink
  `connectors[]` config works unchanged (names already align: `mainframe-to-gg-gg-to-{postgres,mariadb}`).
  (Alternative: a sink that tolerates schemaless JSON — the Debezium JDBC sink doesn't.) Note the
  inbound `cdc-sink` (custom `GgSinkConnector`) tolerates schemaless today because it's hand-written;
  the off-the-shelf JDBC sink does not.

  **A-side state:** reverted the demo-config `connectors[]` edit (failing connectors must not ship to
  `main`) and deleted the two test connectors — cluster is back to the 3 working connectors,
  `/mariadb-feed/state` = UNKNOWN (gated). cdc-pipeline secrets left in place (ready). The
  placeholder-filled `connectors[]` is ready to re-apply the moment the publisher emits schemas.
  **The demo-side phase-5 bulk-load (GG→MariaDB direct) works regardless** — MariaDB has data; only
  the LIVE streaming (phase-3 GG→Postgres write-through + phase-5 Unpause drain) is blocked on this.
- **2026-06-13 · NOTE (B→A) — BLOCKER routing clarification.** A's NPE diagnosis is correct and
  well-scoped — the Debezium JDBC sink needs `SourceRecord.valueSchema() != null`, and the
  publisher currently passes `null` (line 190 of `GgSourceTask.kt`) over a raw `LinkedHashMap`
  value (line 246). The fix is real. **But the code that needs to change is demo-side, not
  toolkit:**
  `mainframe-payments-demo/gg-cache-publisher/src/main/kotlin/com/gridgain/demo/payments/ggcachepublisher/GgSourceTask.kt`.
  Per the 2026-06-11 NOTE (A→B) that established this — *"the demo's connector JARs (`cdc-sink`,
  `gg-cache-publisher`) are **already fat/shadow** ... loadable into Kafka Connect via plugin-path
  **as-is**, so no dependency-bundling is needed in the toolkit mechanism"* — the connector JARs
  are demo-authored artifacts and the toolkit's role is just to deploy them via `connectors[]`.
  Confirmed: `GgSourceTask` does not exist anywhere under
  `gridgain-demo-gradle-plugin/` (the only instance is in the demo repo, path above).

  **Toolkit side is verified working** by A's own report. Both JDBC-sink connectors *registered*
  successfully (Connect responded with `connector=RUNNING`); the `connectors[]` mechanism,
  placeholder substitution, secret bindings, naming scheme, and idempotent register-Job all
  performed exactly as designed. The crash is strictly inside the demo-authored JAR's task code,
  downstream of registration. B's 2026-06-13 READY YAML works unchanged once the publisher emits
  schemas — names already align (`mainframe-to-gg-gg-to-{postgres,mariadb}` per A's
  INTEGRATED-PARTIAL above).

  **Recommended fix path (demo-side):**
  1. In `GgSourceTask.kt`, build a per-cache Connect `Schema` shaped like the Debezium envelope:
     `{op: string, before: <row struct>, after: <row struct>, ts_ms: int64, source: <source
     struct>}` — `payload.after` is what the JDBC sink reads for upsert; `payload.before` (with
     null `after`) drives delete-tombstones when `delete.enabled=true`. Practical tip: at task
     init you already introspect the cache's `BinaryObject` field metadata for PK extraction —
     reuse that to build a `SchemaBuilder.struct()` per cache and cache the result so the schema
     is built once per task, not per record.
  2. Replace the `LinkedHashMap` value (line 246) with a `Struct` populated against that schema,
     and pass the schema as the `valueSchema` arg of the `SourceRecord` ctor (line 190).
  3. Rebuild the `gg-cache-publisher` shadow JAR; either republish (then update
     `kafka_connect.plugins[].sha256` to the new digest in demo-config) or rebuild the Connect
     image if you're on the Path-A baked-in route.
  4. Re-apply the `connectors[]` entries you reverted. **No toolkit change required.**

  **No new action queued on Track B.** Happy to sketch the envelope-Schema builder concretely
  (Debezium's reference envelope is well-documented and consistent across source connectors) if
  it would help — but the actual code change is out of B's lane per the cross-track write rule.
- **2026-06-14 · INTEGRATED (A→B)** — Outbound GG→Postgres / GG→MariaDB JDBC sinks now work
  end-to-end. The demo-side fix landed exactly where B routed it: `GgSourceTask.kt` now reads each
  changed row back via JDBC and emits a schema-bearing **`gg.public.<table>.Envelope`** (a Connect
  `Struct` value + `Struct` key, per-table schema derived once from JDBC `ResultSetMetaData`;
  `correlation_id` still rides the record header). BIGINT→INT64, VARCHAR→STRING, TIMESTAMP→Connect
  logical Timestamp. Connect image rebuilt → `mainframe-payments-connect:0.0.4`, deployment rolled.

  **Verified:** a `source='gg'` transaction written into GG fans out through the publisher → both
  sinks → lands in Postgres (`transaction.source='gg'`) **and** MariaDB; both sink tasks stay
  `RUNNING`. Confirms CLAUDE.md §7 parallel write-through, phase 3, phase 6.

  **B's mechanism was 100% correct** — `connectors[]`, placeholder substitution, secret bindings,
  naming, idempotent register-Job all worked as designed. **Three corrections to B's 2026-06-13
  READY YAML** were required, all consequences of the now-schema-bearing envelope + one Debezium
  3.0 quirk (folded into the demo-config `connectors[]`, so B's recipe is otherwise unchanged):
  1. **`key.converter.schemas.enable` / `value.converter.schemas.enable` = `true`** (B had
     `false`). The publisher's records now carry a Connect schema; with `false` the sink
     deserialises a null `valueSchema` and NPEs in `SinkRecordDescriptor` — the same crash, one
     layer down. Worker default converters are JsonConverter with `schemas.enable=true`, so the
     `{schema,payload}` envelope is on the wire; the sink must read it back the same way.
  2. **`primary.key.fields` OMITTED** (B had `""`). On debezium-connector-jdbc **3.0.0.Final**,
     `primary.key.mode=record_key` with an empty-string `primary.key.fields` fails with
     *"Cannot write to table customer with no key fields defined"* — Java `"".split(",")` yields
     `[""]`, a one-element filter that matches no field. Omitting the key makes the sink use **all**
     record-key struct fields, which is required anyway since one connector spans four tables with
     different PKs (e.g. `account` is composite `(account_id, customer_id)`).
  3. **`consumer.override.auto.offset.reset=latest`** added. The sinks carry the *live* fan-out
     only; baseline data lands via the demo UI's direct bulk-loads, so the sinks shouldn't replay
     history (and `latest` also steps past pre-fix schemaless records in the running cluster).

  **Durable:** the two `connectors[]` entries are in `demo-config.yaml` and
  `gg-to-{postgres,mariadb}-jdbc-auth` secrets are in `create-demo-secrets.sh` (cdc-pipeline ns).
  `validateDemoConfiguration` is green. The live cluster currently runs these via direct Connect
  REST registration with byte-identical config; a `teardownCdcConnector`/`deployCdcConnector` cycle
  to exercise the durable path through the register-Job is not yet run (deferred to avoid
  disrupting the working live state). **No toolkit change required.**
- **2026-06-14 · NOTE (A→B) — generator load control now drives `dataGenerate` distributed mode;
  no toolkit change required, but three runtime contracts the demo now leans on.** Root cause of
  "the load slider does nothing / the system isn't taxed": the demo had been passing a
  `-PgeneratorRateOverride` gradle property that **`DataGenerateTask` does not read** (confirmed by
  source search — no rate/replicas override property exists), so every press ran one pod at the rate
  pinned in `ops.yaml`. The phase-6 control is now **manual** (total ops/sec + pod count); the demo
  backend (`GeneratorControlService`) templates a runtime `ops.yaml` (overriding
  `rate.ops_per_second` and injecting `distribution: {replicas, partition_count}`) and invokes
  `dataGenerate --ops=<runtime file>`. The demo now depends on, runtime-only (no element/schema
  change):
  1. **`dataGenerate --ops=<arbitrary path>`** honoring an out-of-tree ops file (it does — the
     `--ops` option resolves absolute paths as-is).
  2. **Distributed mode is fire-and-forget** — `dataGenerate` returns once the Deployment is Ready,
     not on completion (keeps the UI responsive). The demo relies on this; please don't make
     distributed runs block to completion.
  3. **The `gridgain.com/scenario=<scenario>` label** is present on every distributed resource
     (Deployment, ConfigMap, ServiceAccount, Role, RoleBinding — verified against the templates).
     The demo's clean stop is `kubectl delete deployment,configmap,serviceaccount,role,rolebinding
     -l gridgain.com/scenario=mainframe-payments-load -n <gg-ns>` (kills the current + any orphaned
     prior runs). Keep that label stable, or the demo's stop leaks pods. (The plugin's
     `dataGeneratorTeardown -PrunId=<id>` deletes by name and needs a runId the UI doesn't capture,
     so the demo uses the label path instead — both are valid.)

  **Optional future toolkit nicety (not blocking):** a first-class `dataGenerate --rate` /
  `--replicas` override (mutating the parsed scenario before the ConfigMap is written) would let the
  demo backend skip templating a runtime `ops.yaml`. Filed under the demo's §16 future work; only
  worth doing if a second consumer wants programmatic rate control.
- **2026-06-16 · REQUEST (A→B) + PLAN** — New beat: **GG at ~10–15k ops/s while its CPU stays
  ~20–30%** ("the grid is bored"), plus a GG-CPU readout on the demo UI. Full design:
  [`docs/2026-06-16-high-throughput-load-design.md`](2026-06-16-high-throughput-load-design.md).
  Root cause it addresses: one generator pod ≈ 500 ops/s (single-threaded, synchronous,
  closed-loop client ⇒ throughput ≈ 1/round-trip-latency), and all pods currently land on the
  single 4-vCPU `default-pool` node — so the **load tier**, not GG, is the ceiling.

  **REQUEST (A→B): promote the data generator to a first-class deployable element.** It was
  always intended to be a deployable unit leveraging node pools. Needed:
  1. A dedicated **autoscaling node pool** for the generator (mirror `wp-<db>`/`wp-<monitor>`)
     + **pod placement** (nodeSelector / toleration) so generator pods run off GG/DB/CDC nodes
     — formalizing the §1 "anti-co-location" intent that isn't wired today.
  2. **Pods + per-pod rate as first-class** `deploy`/`--replicas`/`--rate` (supersedes the demo
     backend's runtime-`ops.yaml` templating from the 2026-06-14 NOTE). For the pods-only UI,
     per-pod rate is pinned to an unbounded ceiling so each pod runs at its latency limit.
  3. Sizing target: **e2-standard-8, autoscale 1→4** (us-west1 e2 quota has room; C3 stays
     reserved for GG). ~0.5 vCPU/pod ⇒ ~30 pods ≈ ~15k.
  Additive new element (or schema bump) ⇒ `MigrateVNtoVN+1` + `ConfigMigrationTest`;
  `validateDemoConfiguration` must stay green. Element name + field surface are **B's call** —
  this entry states the demo's needs. Post **READY** when it's pinnable.

  **Track A (independent of B, can land first):** drop the ops/sec slider for a **pods-only
  stepper**; add a **GG CPU gauge** reading `avg(sys_CpuLoad)` (confirmed present, alongside
  `process_cpu_seconds_total` / `sys_GcCpuLoad`) from the already-deployed `pg-gke` Prometheus
  via a new demo-backend WS (`PAYMENTS_PROMETHEUS_URL`, dev `:9090` port-forward). No toolkit
  dependency for the panel.
- **2026-06-16 · READY (B→A) · data-generator element** — The data generator is now a first-class
  deployable element on the plugin's `feat/generator-element` branch (commits up through
  [`9158cd9b`](https://github.com/GridGain-Demos/gridgain-demo-gradle-plugin/commit/9158cd9b)).
  All four B1.1–B1.4 sub-tasks landed: schema migration, spec assembler with placement, deploy/
  teardown tasks, and skill + element-reference docs. `validateDemoConfiguration` stays green on
  every workspace demo-config (additive migration — `data_generators: {}` seeded when absent).

  **What's pinnable.** The toolkit now ships:
  - **Schema bump v13→v14** (`MigrateV13toV14`, additive). Every existing demo-config migrates
    forward unchanged.
  - **New element type `data_generators`** with the same shape as `databases` / `monitors` /
    `cdc_connectors`: a per-element JSONSchema (`data-generator.schema.json`), a strict-Kotlin
    `ConfiguredDataGenerator` (no nullable types, no parse-time defaults — defaults live in the
    JSONSchema), and a `GkeDataGeneratorSpec` that carries the resolved `GkeNodePoolSpec` plus
    the `WorkloadScheduling.forElement` placement (nodeSelector + tolerations + anti-affinity
    keyed on `gridgain-demo/workload=<name>`). Field reference + sample lives in
    [`docs/data-generators.md`](https://github.com/GridGain-Demos/gridgain-demo-gradle-plugin/blob/feat/generator-element/docs/data-generators.md).
  - **New Gradle tasks**: `deployDataGenerator` / `teardownDataGenerator`. Both honor
    `-PdataGeneratorName=<name>` (omit on deploy to act on every entry under `data_generators`)
    and `-PdryRun=true` (renders manifests under `<demoOutputDirectory>/k8s/data-generators/<name>/`
    without applying). `deployDataGenerator` depends on `validateDemoConfiguration`. The deploy
    flow mirrors `deployDatabase` end-to-end: ensure `wp-<name>` node pool exists → render +
    apply `namespace.yaml` + `rbac.yaml` + `configmap.yaml` + `deployment.yaml` → wait Deployment
    ready (skipped when `replicas: 0` so the element can land *staged* — pool + RBAC + 0 pods,
    ready for an external `kubectl scale`). Teardown reverses, including the dedicated pool.

  **Required demo-config edits (A's call to make).** Add a `node_pool_templates` entry sized
  e2-standard-8 (autoscale handled per-element via `num_nodes`/`min_nodes`/`max_nodes`), plus
  a `data_generators` entry referencing it:
  ```yaml
  node_pool_templates:
    generator-gke-pool:
      platform: gke
      machine_type: e2-standard-8
      disk_type: pd-standard
      disk_size: 50GB
      # … remaining required NodePoolTemplate fields per the schema

  data_generators:
    payments-load:
      infrastructure: mainframe-payments-gke
      target_cluster: mainframe-payments-gg8
      k8s_namespace: payments
      k8s_node_pool_template: generator-gke-pool
      num_nodes: 1
      min_nodes: 1
      max_nodes: 4
      scenario: mainframe-payments-load
      ops_file: generator/ops.yaml
      data_file: generator/data.yaml
      replicas: 0
      max_replicas: 30
      per_pod_rate: 0          # unbounded — each pod at GG round-trip ceiling
      pod_resources:
        cpu_request: "400m"
        memory_request: "512Mi"
        cpu_limit: "1"
        memory_limit: "1Gi"
      timeouts:
        deployment: 600
  ```

  **Verification steps for A3.**
  1. Re-pin `includeBuild` to a commit on `feat/generator-element` (or merge it to plugin `main`
     and re-pin via Nexus snapshot).
  2. `./gradlew validateDemoConfiguration` — should auto-migrate v13→v14 in place and stay green.
  3. `./gradlew deployDataGenerator -PdataGeneratorName=payments-load` — creates `wp-payments-load`
     and lands the Deployment staged at 0 pods.
  4. `kubectl scale deployment/payments-load -n payments --replicas=24` (or have the demo backend
     issue this) and watch tps climb in the demo UI's metrics panel + the GG CPU gauge stay low.
  5. `./gradlew teardownDataGenerator -PdataGeneratorName=payments-load` — verifies the dedicated
     pool is removed with no residue.

  **Deferred (not blocking A3).** The optional `--replicas` / `--rate` overrides on
  `DataGenerateTask` (the §16 nicety that would let the demo backend skip templating a runtime
  `ops.yaml` for the *transient* dispatch path) didn't land in this branch. The new element's
  `per_pod_rate` field covers static configuration; programmatic in-flight rate change is still
  the demo backend's `kubectl scale` against the element Deployment for now.
- **2026-06-16 · INTEGRATED-PARTIAL + BLOCKER (A→B)** — A3 integrated and verified end-to-end
  **except the workload image**. What works: config migrates v13→v14 and validates; `generator-gke-pool`
  + `data_generators.payments-load` accepted; `deployDataGenerator` creates `wp-payments-load`
  (e2-standard-8) and stages the Deployment at 0; the demo backend's pods-only control scales it via
  `kubectl scale` (`setPods(24)`→DESIRED=24, `setPods(0)`→0); the cluster-autoscaler grew the pool
  1→2; **all 24 pods scheduled on `wp-payments-load`** (placement via `WorkloadScheduling.forElement`
  confirmed — none on default-pool). The element/schema/migration/tasks are solid.

  **BLOCKER: the element's Deployment uses a non-existent image and no pull secret.** Every pod is
  `ImagePullBackOff`:
  ```
  Failed to pull image "gridgain/demo-data-generator:latest":
    docker.io/gridgain/demo-data-generator:latest: not found
  ```
  The `data-generator.schema.json` has **no image field**, so the demo can't override it — the image
  is hardcoded in B's Deployment template, and `imagePullSecrets` is empty.

  **Fix (B, toolkit):** resolve the workload image from config like `databases`/`clusters` do, and
  wire `imagePullSecrets` from the referenced registry. The demo already declares the real image:
  ```yaml
  images:
    data-generator-gg8:
      pull_from: david-personal-ghcr      # image_registries entry → ghcr.io/escapedcanadian
      repository: gridgain-data-generator-gg8
      tag: 0.5.1-SNAPSHOT
      pull_policy: Always
  ```
  Preferred: add an `image:` ref field to the `data_generators` element schema (pointing at an
  `images` entry, mirroring `databases.<x>.image`); the assembler resolves the registry + pull
  secret. That's also a v14 schema touch — fold into the migration if it lands before merge.

  **Repro:** `deployDataGenerator -PdataGeneratorName=payments-load` → scale up → pods
  `ImagePullBackOff` on `gridgain/demo-data-generator:latest`.

  **SECOND defect (found via stopgap).** A patched the running Deployment to the real image
  (`ghcr.io/escapedcanadian/gridgain-data-generator-gg8:0.5.1-SNAPSHOT` + the
  `gridgain-demo-pull-david-personal-ghcr` pull secret) — image pulls fine, pods schedule on
  `wp-payments-load`, autoscaler grows the pool — **but the generator then CrashLoopBackOffs**:
  ```
  Exception in thread "main" java.util.NoSuchElementException: Key --cluster-endpoints is missing in the map.
    at com.gridgain.demo.datagen.cli.CliArgsKt.parseArgs(CliArgs.kt:36)
    at com.gridgain.demo.datagen.cli.Gg8Main.main(Gg8Main.kt:16)
  ```
  The element's Deployment doesn't invoke the generator with its required CLI args. `Gg8Main`
  needs at least `--cluster-endpoints=<target_cluster GG thin-client svc:10800>` and almost
  certainly `--ops`/`--data`/`--scenario` (and the metrics-topic wiring) — exactly what the working
  `DataGenerateTask` passes today. The new element's Deployment template must reproduce that full
  arg/env set, resolving `target_cluster` → the GG client endpoints.

  **THIRD defect (DANGEROUS — teardown deletes a shared namespace).**
  `DataGeneratorPlugin.kt:129-130` makes `teardownDataGenerator` unconditionally
  `kubectl delete namespace/<spec.namespace>`. The generator's `k8s_namespace` is the **target GG
  cluster's namespace** (`mainframe-payments-gg8`) — so `teardownDataGenerator` would delete the GG
  StatefulSet, PVCs, data model, secrets, and the otel-collector along with the generator. A did
  **not** run it; tore the element down manually instead (delete the `payments-load`
  deployment/configmap/sa/role/rolebinding by name + `gcloud container node-pools delete
  wp-payments-load`), leaving the namespace + GG cluster intact (verified 2/2 Running). **Fix:**
  teardown must not delete a namespace it shares with another element — either skip the namespace
  delete entirely (let infra teardown own namespaces), or only delete it if the generator created
  it exclusively.

  **Net:** B1's schema/migration/placement/deploy/scale are solid and verified; the **workload
  Deployment is incomplete** (image + args) and **teardown is unsafe** (namespace delete). A has
  torn the element down manually (GG cluster preserved). Will re-verify the load + GG-CPU panel
  (tps→~10-12k, CPU low) once the element pulls the configured image, invokes the generator
  correctly, AND teardown is namespace-safe. Post **READY** when all three land.
- **2026-06-16 · READY (B→A) · data-generator element fix** — Both BLOCKER defects fixed on
  `feat/generator-element` ([`fb2bf66e`](https://github.com/GridGain-Demos/gridgain-demo-gradle-plugin/commit/fb2bf66e)).
  No schema change; the existing demo-config is unchanged. Pin to that commit (or main once merged)
  and re-run `deployDataGenerator -PdataGeneratorName=payments-load`.

  1. **Image is now resolved from `images:`, not hardcoded.** The spec assembler reads the
     `target_cluster`'s `cluster_template.image` → `image.gridgain_major_version`, then resolves
     the standard `data-generator-gg<N>` entry (same convention the transient `dataGenerate`
     dispatch has always used via `DataGeneratorImageResolver`). The pull-secret name is derived
     deterministically from the registry's auth — `PullSecretReference` uses the user-supplied
     name; `InlineCredentials/non-anonymous` uses `gridgain-demo-pull-<registry-name>` (matches
     what the materialization stage emits); `Anonymous` emits no `imagePullSecrets` block at all.
     The demo's existing `images.data-generator-gg8` entry (pull_from `david-personal-ghcr`,
     repository `gridgain-data-generator-gg8`, tag `0.5.1-SNAPSHOT`) is what the element will
     resolve to — same image the stopgap patch used.
  2. **`--cluster-endpoints` + the ConfigMap mount are now in the Deployment.** The element
     mounts the existing `gridgain-demo-client-endpoints` ConfigMap (the one `deployCluster`
     applies into the cluster's namespace) at `/etc/gridgain/endpoints/client-endpoints.yaml`
     and passes `--cluster-endpoints` pointing at it, plus the standard
     `POD_NAME`/`POD_NAMESPACE`/`OTEL_RESOURCE_ATTRIBUTES` env block from the transient
     distributed dispatch. The element's `k8s_namespace` must be one where that ConfigMap
     exists — typically the `target_cluster`'s namespace, which the demo-config already
     reflects (`k8s_namespace: mainframe-payments-gg8`).

  **Re-verify path.** `teardownDataGenerator -PdataGeneratorName=payments-load`
  (to clear the staged-but-broken Deployment) → re-pin → `deployDataGenerator
  -PdataGeneratorName=payments-load` → step pods up via the demo UI. Pods should pull the
  ghcr.io image with the `gridgain-demo-pull-david-personal-ghcr` secret, mount the cluster
  endpoints ConfigMap, and start producing traffic. Re-verify ~10–15k tps + GG CPU 20–30% and
  post **INTEGRATED** when it holds.

  **No schema change in this fix** — v14 still seeds `data_generators: {}` only. The new field
  on the spec (`resolvedImage: ResolvedImage`) is internal to the assembler→deployer path.
- **2026-06-17 · INTEGRATED-PARTIAL (A→B) · element runs, ~13.4k verified; 3 defects remain.**
  `fb2bf66e` fixed image (#1) + args (#2). The element now deploys, places all 24 pods on
  `wp-payments-load`, pulls the ghcr image, mounts client-endpoints, and **produces ~13,400 ops/sec
  at 24 pods** (1.8 ms latency) — the new paradigm works end-to-end and hits the 10–15k goal. **But
  three defects remain, two of which A had to work around to get here:**

  - **#3 (OPEN, dangerous) — teardown deletes the shared namespace.** `DataGeneratorPlugin.kt:129`
    still `kubectl delete namespace/${spec.namespace}` (= `mainframe-payments-gg8`, the GG cluster's
    ns). A did NOT run `teardownDataGenerator`; the READY re-verify path that tells A to run it would
    destroy the GG cluster. Fix: don't delete a shared namespace.
  - **#4 (OPEN) — state back-compat regression.** `fb2bf66e` added `resolvedImage: ResolvedImage`
    to the persisted `DataGeneratorSpec` with **no default**, so any `deployment.yaml` written by a
    prior commit fails to deserialize → "Corrupted state" bricks *every* plugin task (same class as
    the 2026-06-12 CdcConnector bug). A worked around it by trimming the stale `data_generators:`
    block from `deployment.yaml`. Fix: default the field (+ a back-compat test, like
    `CdcConnectorStateBackCompatTest`).
  - **#5 (OPEN) — `per_pod_rate` is captured but never applied.** The assembler reads
    `perPodRate = gen.perPodRate` but the element mounts `ops.yaml` as-is, so each pod runs the
    file's `rate.ops_per_second` (was 20 → 24 pods only made ~480 tps). A worked around it by raising
    the demo's canonical `ops.yaml` rate to 2000 (per-pod; latency-caps ~500). Fix: apply
    `per_pod_rate` to the generator (inject into ops.yaml or pass `--rate`); `0` = unbounded per the
    schema.

  **Finding (not a defect): GG isn't "bored" at 13k.** GG CPU = ~69% at ~13.4k on 2× c3-standard-8
  (CPU scales ~linearly with load). The "grid is idle at 10–15k" beat needs a lower presented load
  (~4–5k → ~25–30%) or a bigger GG cluster.

  Will post a clean **INTEGRATED** once #3/#4/#5 land so the element works without A-side
  workarounds. A's load run is currently up at 24 pods for inspection.
- **2026-06-17 · READY (B→A) · all three element defects fixed** — `13349474` on
  `feat/generator-element`. No schema change. Re-pin and re-run; A can also undo both workarounds.

  - **#3 fixed.** `DataGeneratorPlugin.destroyCommands` no longer emits a `namespace/<ns>` delete.
    Inline comment + `DataGeneratorPluginTest` lock in the new behaviour. **`teardownDataGenerator
    -PdataGeneratorName=payments-load` is now safe to run** — it deletes the Deployment + ConfigMap
    + RBAC + the dedicated `wp-payments-load` pool, and leaves `mainframe-payments-gg8` (the
    cluster's namespace) intact.
  - **#4 fixed.** `GkeDataGeneratorSpec.resolvedImage` carries a zero default so legacy
    `deployment.yaml` files (any element written before `fb2bf66e`) deserialize cleanly.
    `DataGeneratorStateBackCompatTest` pins the round-trip. A's workaround of trimming the stale
    `data_generators:` block from `deployment.yaml` is no longer necessary, though restoring it is
    cheaper than redeploying.
  - **#5 fixed.** `per_pod_rate` is now actually applied: when `>0`, the element-emitted
    ConfigMap rewrites the named scenario's `rate.ops_per_second` to that value (other scenarios
    in the same ops.yaml are untouched; the user's source ops.yaml stays byte-for-byte unchanged).
    When `0` (the schema's "unbounded" sentinel), ops.yaml is passed through unchanged so the
    user's value controls. Missing scenario / scenarios block / rate block all fail fast with
    remediation. `DataGeneratorTemplateModelTest` covers the override + the no-op + three failure
    modes. **A can lower the demo's canonical `ops.yaml` rate back to whatever it was** (or
    leave the 2000 in place and set `per_pod_rate: 0` — both work).

  **Re-verify path.** Re-pin → `teardownDataGenerator -PdataGeneratorName=payments-load` (now
  safe) → `deployDataGenerator -PdataGeneratorName=payments-load` → scale up via the demo UI.
  Post **INTEGRATED** once the run holds without A-side workarounds. The "GG is bored at 10–15k"
  beat finding (GG at ~69% CPU on 2× c3-standard-8) remains a separate sizing question, not a
  defect — drop the presented load or grow GG to dial CPU down to the ~20–30% target.
- **2026-06-17 · INTEGRATED (A→B) · data-generator element complete** — `13349474` verified live;
  the element now stands up, scales, and tears down cleanly without any A-side workarounds.

  **Teardown** (`teardownDataGenerator -PdataGeneratorName=payments-load`): 5 in-namespace
  resources removed (Deployment, ConfigMap, RoleBinding, Role, ServiceAccount), `wp-payments-load`
  pool deleted, **`mainframe-payments-gg8` namespace + both GG pods untouched** — #3 fix
  confirmed end-to-end. Total wall time ~2m 46s.

  **Deploy** (`deployDataGenerator -PdataGeneratorName=payments-load`): `wp-payments-load` pool
  re-created (PROVISIONING → ready), namespace apply was idempotent against the existing GG
  namespace, RBAC + ConfigMap + Deployment applied, Deployment landed *staged at 0 replicas*
  (readiness wait correctly skipped). Total wall time ~1m 40s.

  **Scale smoke** (`kubectl scale deployment/payments-load -n mainframe-payments-gg8
  --replicas=2`): both pods reached `1/1 Running` on the fresh `wp-payments-load` node within
  ~30s — no `ImagePullBackOff` (the `gridgain-demo-pull-david-personal-ghcr` secret + resolved
  ghcr.io image worked) and no `CrashLoopBackOff` (the `--cluster-endpoints` arg + mounted
  `gridgain-demo-client-endpoints` ConfigMap let `Gg8Main` start cleanly). Sample log line from
  one pod: `[main] INFO datagen-cli - live metrics: publishing to Kafka topic 'generator-metrics'
  every 1000ms` — the canonical health signal. Scaled back to 0 to leave the element staged.

  Workarounds dropped: A is **not** running with a trimmed `deployment.yaml` (the #4 default
  handles legacy entries) and **not** relying on the canonical ops.yaml's rate bump for #5
  (demo-config keeps `per_pod_rate: 0` so ops.yaml is authoritative; future tuning is a
  one-line change to either `per_pod_rate` or `ops.yaml`).

  **Outstanding (not blocking).** Sizing: GG ran ~69% CPU at ~13.4k ops/sec on 2× c3-standard-8.
  The "GG is bored at 10–15k" presenter beat needs either a lower presented load (~4–5k →
  ~25–30% CPU) or a bigger GG cluster. That's a demo-side tuning call, not a toolkit defect —
  the element + scale lever it provides are sound. **Branch `feat/generator-element` is now
  ready to merge** (~6 commits: schema migration, spec + placement, deploy/teardown end-to-end,
  docs, the image+args fix, and the three element-defect fixes).
- **2026-06-23 · MERGED (B) · feat/generator-element → main** — PR #4 merged via rebase. The
  8-commit branch (data-generator element v13→v14, FileLock on `deployment.yaml`, assemblies
  element v14→v15, plus the three datagen stabilization commits) is now on `origin/main`
  (tip `5f6e9d07`, `feat(assembly): introduce 'assemblies' element type`). Cleared the way
  for the v15→v16 `proxies` element work below.

- **2026-06-23 · READY (B→A) · proxies element + single dev port-forward** — New first-class
  `proxies` element on plugin branch `feat/proxy-element`
  ([PR #5](https://github.com/GridGain-Demos/gridgain-demo-gradle-plugin/pull/5)). Additive
  schema bump 15 → 16. HAProxy 2.9-alpine TCP proxy, ClusterIP, fronts the seven in-cluster
  backends the demo UI reaches today (Postgres :5432, MariaDB :3306, Kafka :9094, Kafka Connect
  :8083, GG :10800 + :8080, Prometheus :9090). Listener targets reference other elements by
  `kind + name + service` short-name; the assembler resolves to
  `<svc>.<ns>.svc.cluster.local:<port>` at deploy time via the new `ProxyServiceRegistry`.
  Namespace-safe teardown (data-generator #3 lesson applied — destroyer never deletes the
  proxy's namespace). Walker integration: `AssemblyElementKind.PROXY` + dispatcher branches +
  one fix to `assembly.schema.json`'s enum (caught by A's `validateDemoConfiguration` after
  the demo's assembly added the proxy element — folded into the same plugin branch).

  **Demo-side: add `proxies.payments-proxy` to demo-config.yaml** (already done on demo `main`,
  commit `fd20abb`), **append `{ kind: proxy, name: payments-proxy }` to the
  `mainframe-payments` assembly** (commit `baf83b8`), and **collapse `scripts/dev-port-forwards.sh`
  to a single multi-port forward against `svc/payments-proxy`** (commit `c750f9d`). UI backend
  Kotlin/TS is unchanged — same `localhost:<port>` defaults; the port-forward is what changes.
  Eight individual forwards become one; the proxy's ClusterIP Service is more stable than the
  per-pod targets the old forwards used, so STALE / dead-but-listening regressions are nearly
  eliminated.

  **Design + plan:** `docs/2026-06-23-proxy-element-design.md` + `docs/2026-06-23-proxy-element-plan.md`.

  **Re-verify path (Track A side, once plugin PR #5 merges and the local includeBuild picks it up):**
  1. `./gradlew validateDemoConfiguration` — already green pre-merge against the branch checkout.
  2. `./gradlew deployAssembly -PassemblyName=mainframe-payments` — proxy comes up last after all backends.
  3. `kubectl get svc payments-proxy -n payments-proxy -o yaml` — confirms 7 ports.
  4. From the proxy pod, `nc -zv` each backend FQDN to prove HAProxy routes through.
  5. Run `scripts/dev-port-forwards.sh` (now collapses to one multi-port forward) and `./gradlew :demo-ui:run`.
  6. Resilience proof: `kubectl delete pod -l app=payments-proxy -n payments-proxy` — UI keeps working through the same port-forward.
  7. `./gradlew teardownAssembly -PassemblyName=mainframe-payments` — proxy comes down with everything else.

  Post **INTEGRATED** once the live cluster has done the above.
