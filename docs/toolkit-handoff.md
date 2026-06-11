# Toolkit Element-Types Hardening — Handoff Charter

**Status:** DRAFT — pin details filled at freeze time (see *Frozen baseline* below).
**Authored:** 2026-06-11, by the payments-demo Claude process, for a separate "toolkit-hardening" process.

---

## 1. Why this exists

The **mainframe-payments demo** needs non-GridGain elements the toolkit
(`gridgain-demo-gradle-plugin`) didn't previously support: **Postgres, MariaDB,
Kafka, and Debezium**. These were implemented *expediently, to get the demo
working* on plugin branch **`feat/mainframe-payments-elements`** (currently a dirty,
in-progress working tree).

This charter scopes a **separate effort (Track B)** to clean up, generalize, and
**harden those element types into first-class, reusable, tested toolkit features** —
**without impacting the payments demo (Track A)**, whose public presentation is
**fixed and < 2 weeks out from 2026-06-11** (confirm exact date).

> The prime directive: **Track B must not be able to derail Track A.**

---

## 1a. ⚠️ Top-priority task (the demo's actual blocker)

**Deploy + register the demo's custom Kafka Connect connectors as part of the
`cdc_connectors` element.** This is what currently stops the full data flow:

- The toolkit's `deployCdcConnector` brings up Kafka + Kafka Connect (stock
  `debezium/connect:3.0.0.Final`) and registers only the **Debezium source**
  (`mainframe-to-gg-source`, Postgres→Kafka).
- The demo's two **custom** connectors are built but **never loaded into Connect
  or registered**, so Kafka→GG and GG→Kafka never happen → GG caches and the
  MariaDB fan-out stay empty:
  - `cdc-sink` — `com.gridgain.demo.payments.cdcsink.GgSinkConnector` (Kafka→GG).
    Built at `cdc-sink/build/libs/cdc-sink-0.0.1-SNAPSHOT.jar`.
  - `gg-cache-publisher` — `com.gridgain.demo.payments.ggcachepublisher.GgSourceConnector`
    (GG→Kafka). Built at `gg-cache-publisher/build/libs/gg-cache-publisher-0.0.1-SNAPSHOT.jar`.
- There is **no committed registration config, no plugin-path/image mechanism,
  and no deploy script** for these (verified). Connector configs must be derived
  from `GgSinkConfig.kt` / `GgSourceConfig.kt`. The JARs also look thin (deps not
  bundled), so a fat/shadow jar or a custom Connect image is likely needed.

**What the `cdc_connectors` element should grow:** a way to declare custom
connector plugins (image baked or plugin-path mounted **with dependencies**) and
register their connector configs via the Connect REST API — generalized, not
demo-hardcoded. Until this lands, the demo runs Mainframe-panel-only.

---

## 2. The two tracks and how they're isolated

| | Track A — Payments demo (protect) | Track B — Toolkit hardening (this charter) |
|---|---|---|
| Repo | `mainframe-payments-demo` | `gridgain-demo-gradle-plugin` |
| Plugin dependency | **Pinned** to a frozen mavenLocal artifact `<PINNED_VERSION>` | works on the live source |
| Working dir | `../gridgain-demo-gradle-plugin` (untouched, frozen baseline) | **separate git worktree** `../gridgain-demo-gradle-plugin-toolkit` |
| Branch | `feat/mainframe-payments-elements` @ frozen commit `a4a1e245` | `feat/toolkit-db-cdc-hardening` (branched from `a4a1e245`) |

**Why a worktree and not just a branch:** the demo consumes the plugin via
`includeBuild("../gridgain-demo-gradle-plugin")`, which points at a *directory*.
Checking out a different branch in that directory changes what the demo builds
against. The freeze (below) switches the demo to a pinned `mavenLocal()` artifact,
fully decoupling it from the plugin's working dir — after which Track B can do
anything on its own worktree/branch.

---

## 3. Frozen baseline & isolation approach

**Chosen for now (lower risk, since the demo is live and the presentation is
imminent): worktree isolation — the demo's build is NOT re-wired.**

- The demo keeps `includeBuild("../gridgain-demo-gradle-plugin")` pointed at
  `feat/mainframe-payments-elements` @ the frozen baseline commit `a4a1e245`
  — ✅ committed; the Track-B worktree already exists at
  `../gridgain-demo-gradle-plugin-toolkit` on branch `feat/toolkit-db-cdc-hardening`.
- Track B works in a **separate git worktree** so the demo's plugin directory is
  never touched:
  `git worktree add ../gridgain-demo-gradle-plugin-toolkit -b feat/toolkit-db-cdc-hardening a4a1e245`
- **Discipline that keeps the demo frozen:** no new commits land on
  `feat/mainframe-payments-elements` in the demo's worktree during the demo window;
  all Track-B work happens on `feat/toolkit-db-cdc-hardening` in its own worktree.

**Optional stronger isolation (defer until there's time to test the build
change):** `publishToMavenLocal` an immutable non-SNAPSHOT version and switch the
demo's `settings.gradle.kts` from `includeBuild` → that pinned `mavenLocal()`
dependency — fully decoupling the demo from the plugin directory.

Track B branches **from `a4a1e245`** either way.

---

## 4. What exists today (surface area to clean up)

Implemented on `feat/mainframe-payments-elements` — treat as *demo-working, not yet
hardened/generalized*:

- **Element types:** `databases` (variants `postgres`, `mariadb`), `cdc_connectors`
  (Kafka + Kafka Connect + Debezium Postgres source).
- **Tasks:** `deployDatabase`/`teardownDatabase` (`-PdatabaseName`),
  `deployCdcConnector`/`teardownCdcConnector` (`-PconnectorName`).
- **Core classes (per the dirty tree):** `DatabasePlugin`, `CdcConnectorPlugin`,
  `ClusterSpecAssembler`, `ConfiguredDemo`, `DataModelDeployer`,
  `DeployDataModelAction`, `ClusterConnectionManager`, `DeployedDemo`.
- **Schemas:** `schema/cdc-connector.schema.json`, `schema/demo.schema.json`
  (+ database schema). Config `schema_version` is **v13**.
- **k8s templates:** `templates/k8s/cdc-connector/{kafka-statefulset,register-connector-job}.yaml`,
  `templates/k8s/database/postgres-statefulset.yaml` (+ MariaDB).
- **Tooling:** `tooling/gke_tool_requirements.yaml` — **bounds were widened** to accept
  newer local gcloud (572) / kubectl (1.35); Track B should decide the *proper*
  supported ranges.
- **Tests:** `DataModelDeployerTest.kt` (started).

---

## 5. The contract Track B MUST NOT break

The demo depends on all of the following. Refactor internals freely, but preserve
these (or supply a clean forward migration):

- **Config schema** for `databases` (image, port, `k8s_namespace`,
  `k8s_node_pool_template`, `init_ddl_location`, `auth_secret_ref`, `database_name`,
  resources, storage, timeouts) and `cdc_connectors` (`source` db, `sink` cluster,
  `mapping_rules`, `kafka{...}`, `kafka_connect{...}`) — exactly as used in the demo's
  `src/main/resources/demo-config.yaml`.
- **Task names + `-P` params** (`deployDatabase -PdatabaseName`,
  `deployCdcConnector -PconnectorName`, `deployDataModel -PclusterName`, …).
- **`schema_version` v13 stays valid**, OR add `MigrateV13toV14` (+ register +
  `ConfigMigrationTest`) so the demo's config migrates forward with no manual edits.
- **Deploy behavior:** init DDL applied from `init_ddl_location`; secrets resolved via
  `auth_secret_ref`; Debezium publication/topics consumed by the demo's `cdc-sink`
  and the demo UI's connector tailers.
- The demo's three instances keep validating + deploying:
  `databases.postgres-mainframe-proxy`, `databases.mariadb-analytics`,
  `cdc_connectors.mainframe-to-gg`.

A fast regression gate for Track B: `validateDemoConfiguration` against the demo's
`demo-config.yaml` must stay green.

---

## 6. Goals for Track B

- Generalize beyond demo-specific assumptions (naming, ports, namespaces, mapping).
- Full validation: JSONSchema + bespoke cross-element checks per the toolkit's
  validation framework; sensible JSONSchema `default`s for the UI/form.
- **Tests (TDD):** spec-assembler tests, manifest-generation tests, migration tests,
  finish `DataModelDeployerTest`.
- Element-type documentation following toolkit conventions.
- Honor workspace rules ([`../CLAUDE.md`](../CLAUDE.md)): no nullable types without
  approval; no fallbacks/parse-time defaults; DRY/polymorphism; rich error messages;
  no `org.gradle.*` in `core/`; SnakeYAML pinned at 1.33; schema versioning +
  migration on every breaking change.
- Decide the correct `gke_tool_requirements.yaml` supported ranges (the widening was
  a demo expedient).
- Housekeeping: gitignore `graphify-out/` (don't commit it).

---

## 7. Merge / integration plan

- Track B merges to the plugin integration branch **after** the payments-demo
  presentation. The demo upgrades to the new plugin **only post-demo**, with a full
  redeploy + the [§18 verification](../CLAUDE.md) checklist.
- **Demo-critical** plugin fixes during the freeze window land on the **frozen
  baseline branch** and Track A **re-pins** (publishes a new fixed mavenLocal
  version) — they are *not* cherry-picked out of Track B.

---

## 8. First steps for Track B

1. `git worktree add ../gridgain-demo-gradle-plugin-toolkit -b feat/toolkit-db-cdc-hardening a4a1e245`
2. Read the existing element-type implementation (§4) and the demo's
   `demo-config.yaml` — that config **is** the contract (§5).
3. Inventory the demo-expedient shortcuts; write a cleanup/hardening plan.
4. Add tests first, then refactor; keep `validateDemoConfiguration` green throughout.
