# `proxies` Toolkit Element + Single Port-Forward Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ggcoder:subagent-driven-development` (recommended) or `ggcoder:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the eight individual `kubectl port-forward`s the demo UI backend relies on with a single HAProxy element in front of all in-cluster backends. New first-class toolkit element type; ClusterIP Service; one `kubectl port-forward` multiplexes all seven local ports.

**Architecture:** New `proxies` element type in `gridgain-demo-gradle-plugin` (mirrors the structure of `databases` / `data_generators` / `cdc_connectors` end-to-end). HAProxy 2.9 in TCP mode, ConfigMap-supplied `haproxy.cfg`. Listeners reference other toolkit elements by `kind + name + service` short-name; a service-name registry resolves them to `<svc>.<ns>.svc.cluster.local:<port>` at deploy time. Demo-side change is config + a script collapse.

**Tech Stack:** Kotlin 1.9 + Java 17 + Gradle 8 (plugin); Mustache templates; HAProxy 2.9-alpine; JUnit 5 + AssertJ + MockK (tests). All workspace rules from `/Users/davidbrown/Code/DemoGradleProject/CLAUDE.md` apply: no nullable types without approval, no parse-time defaults, no Gradle in core, rich error messages, additive schema migration only.

**Source spec:** `/Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo/docs/2026-06-23-proxy-element-design.md` (read this first if you have not already).

---

## File Structure

### Plugin repo — `gridgain-demo-gradle-plugin/` (work on branch `feat/proxy-element`)

**Create:**

| Path | Responsibility |
|---|---|
| `src/main/resources/schema/proxy.schema.json` | JSONSchema for one `proxies.<name>` entry. |
| `src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredProxy.kt` | Strict DTO: `ConfiguredProxy`, `ConfiguredProxyListener`, `ConfiguredProxyTarget`, `ConfiguredProxyPodResources`, `ConfiguredProxyTimeouts`. No nullables, no parse-time defaults. |
| `src/main/kotlin/com/gridgain/demo/core/configuration/MigrateV15toV16.kt` | Additive migration — seeds `proxies: {}`. |
| `src/main/kotlin/com/gridgain/demo/core/configuration/ProxyServiceRegistry.kt` | Maps `<element-kind, short-name>` → `(serviceNameSuffix, namespaceSource)`. Pure data, no IO. Lives next to `*SpecAssembler.kt` per convention — no `core/assembly/` package exists. |
| `src/main/kotlin/com/gridgain/demo/core/configuration/ProxyValidator.kt` | Cross-ref validation: target element exists, short-name advertised, no duplicate listener ports. Lives next to `ImageRegistryValidator.kt` per convention — no `core/validation/` package exists. |
| `src/main/kotlin/com/gridgain/demo/core/configuration/ProxySpecAssembler.kt` | Resolves listener targets to `<svc>.<ns>:port`. Lives next to `DataGeneratorSpecAssembler.kt`. |
| `src/main/kotlin/com/gridgain/demo/core/specs/ProxySpec.kt` | Contains `GkeProxySpec` data class with `ResolvedListener` list. File-named-by-element / class-prefixed-by-platform matches `DataGeneratorSpec.kt` containing `GkeDataGeneratorSpec`. Package is `core/specs/` (plural). |
| `src/main/kotlin/com/gridgain/demo/core/recording/ProxyTemplateModel.kt` | Mustache template model — renders `haproxy.cfg`, Deployment manifest model, Service manifest model. |
| `src/main/kotlin/com/gridgain/demo/core/deployment/ProxyDeployer.kt` | Applies namespace, ConfigMap, Deployment, Service; waits for Deployment ready. |
| `src/main/kotlin/com/gridgain/demo/core/deployment/ProxyDestroyer.kt` | Namespace-safe teardown (no `kubectl delete namespace/...`). |
| `src/main/kotlin/com/gridgain/demo/core/actions/DeployProxyAction.kt` | Walker action. |
| `src/main/kotlin/com/gridgain/demo/core/actions/TeardownProxyAction.kt` | Walker action. |
| `src/main/kotlin/com/gridgain/demo/plugin/tasks/DeployProxyTask.kt` | Gradle task entry point. |
| `src/main/kotlin/com/gridgain/demo/plugin/tasks/TeardownProxyTask.kt` | Gradle task entry point. |
| `src/main/resources/templates/k8s/proxy/namespace.yaml` | Mustache template. |
| `src/main/resources/templates/k8s/proxy/configmap.yaml` | Mustache template (carries `haproxy.cfg`). |
| `src/main/resources/templates/k8s/proxy/deployment.yaml` | Mustache template. |
| `src/main/resources/templates/k8s/proxy/service.yaml` | Mustache template (multi-port ClusterIP). |
| `docs/proxies.md` | Element-type reference doc. |
| `src/test/kotlin/com/gridgain/demo/core/configuration/ProxySchemaTest.kt` | Schema acceptance/rejection. |
| `src/test/kotlin/com/gridgain/demo/core/configuration/ProxyValidatorTest.kt` | Validator catches every failure mode. |
| `src/test/kotlin/com/gridgain/demo/core/configuration/ProxyServiceRegistryTest.kt` | Service-name registry round-trips. |
| `src/test/kotlin/com/gridgain/demo/core/configuration/ProxySpecAssemblerTest.kt` | FQDN resolution per element kind. |
| `src/test/kotlin/com/gridgain/demo/core/state/ProxyStateBackCompatTest.kt` | `deployment.yaml` written by v15 plugin deserializes on v16. Co-located with `DataGeneratorStateBackCompatTest.kt` + `CdcConnectorStateBackCompatTest.kt`. |
| `src/test/kotlin/com/gridgain/demo/core/recording/ProxyTemplateModelTest.kt` | Fixture-pinned `haproxy.cfg` rendering. |
| `src/test/kotlin/com/gridgain/demo/core/deployment/ProxyDestroyerTest.kt` | Namespace-safe teardown verified. |
| `src/test/resources/fixtures/haproxy.cfg.expected` | Golden file for the template-model test. |

**Modify:**

| Path | What changes |
|---|---|
| `src/main/resources/schema/demo.schema.json` | Add `proxies` under `properties`, referencing `proxy.schema.json`. |
| `src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredState.kt` | `CURRENT_SCHEMA_VERSION = 15` → `16`. |
| `src/main/kotlin/com/gridgain/demo/core/configuration/ConfigMigration.kt` | Register `MigrateV15toV16()` in `migrations` list (line ~40). |
| `src/main/kotlin/com/gridgain/demo/core/configuration/ConfigurationQueries.kt` | Add the full triple for every element kind: `listProxyNames()`, `hasProxy(name)`, `buildDeployableProxySpec(name, onWarning)`. Mirror `buildDeployableDataGeneratorSpec` line ~67 verbatim — without `buildDeployableProxySpec`, downstream actions/dispatchers (which call through `buildDeployable*Spec` to get a resolved spec) have no entry point. |
| `src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredAssembly.kt:15-25` | Add `PROXY("proxy")` to `AssemblyElementKind` enum. Add to the kind-uses-name branch of `describe()`. |
| `src/main/kotlin/com/gridgain/demo/core/actions/AssemblyDispatcher.kt:21,49-91,138-179` | Add `PROXY` to `describe()`'s name-branch, to `AssemblyValidator.validateElements`, to `buildDeployActionFor`, to `buildTeardownActionFor`. |
| `src/main/kotlin/com/gridgain/demo/plugin/GridGainDemoPlugin.kt` | Register `DeployProxyTask` + `TeardownProxyTask` (mirror existing task registrations). |
| `src/test/kotlin/com/gridgain/demo/core/configuration/ConfigMigrationTest.kt` | Add `v15ToV16` case asserting `proxies: {}` is seeded; existing fields untouched. |
| `src/test/kotlin/com/gridgain/demo/core/actions/AssemblyValidatorTest.kt` | Add cases for the new `proxy` kind. There is no separate `AssemblyDispatcherTest.kt` today; new dispatcher-case tests go in the existing `AssemblyValidatorTest.kt`, OR a new `AssemblyDispatcherTest.kt` is created if the cases don't fit there cleanly. |

### Demo repo — `mainframe-payments-demo/` (commit to `main`)

**Modify:**

| Path | What changes |
|---|---|
| `src/main/resources/demo-config.yaml` | `schema_version: 15` → `16`. Add full `proxies.payments-proxy` entry (see §2 of design). Append `{ kind: proxy, name: payments-proxy }` to `assemblies.mainframe-payments.elements`. |
| `scripts/dev-port-forwards.sh` | Collapse `FORWARDS=(...)` array from 8 entries to 1 (see §4 of design). |
| `demo-ui/src/main/kotlin/.../config/UiConfig.kt` | Replace per-service "port-forward this" comments with a single rationale note. |
| `demo-ui/src/main/kotlin/.../services/{GridGainService,MainframeProxyService,MariaDbService,ConnectorControlService}.kt`, `tailer/KafkaTailerTap.kt`, `metrics/{GeneratorMetricsService,PrometheusCpuService}.kt` | Error-message text changes only (point operators at the new single port-forward). No behavior change. |
| `demo-ui/frontend/src/components/GridGainPanel.tsx:110` | Operator-help copy refresh. |

**Create:**

| Path | Responsibility |
|---|---|
| `/Users/davidbrown/.claude/projects/-Users-davidbrown-Code-DemoGradleProject-mainframe-payments-demo/memory/single-port-forward-via-proxy.md` | New auto-memory entry. |
| `docs/track-coordination.md` (append) | `READY (B→A)` and `INTEGRATED (A→B)` entries marking the handoff. |

---

## Task 0: Setup — plugin feature branch

**Why:** Workspace memory `git-branch-policy.md` requires feature branches in the toolkit repo. The demo can commit directly to `main`.

- [ ] **Step 1: Create the feature branch from main**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin fetch origin
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin checkout main
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin pull --ff-only origin main
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin checkout -b feat/proxy-element
```

- [ ] **Step 2: Verify the tree is clean and tests pass before any change**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin status
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin && ./gradlew test
```

Expected: clean working tree, ~752 tests pass. If any fail on a clean checkout, **stop and surface the failure** — do not start work on top of a broken baseline.

---

## Task 1: JSONSchema for `proxies.<name>`

**Files:**
- Create: `gridgain-demo-gradle-plugin/src/main/resources/schema/proxy.schema.json`
- Modify: `gridgain-demo-gradle-plugin/src/main/resources/schema/demo.schema.json` (add `proxies` property)
- Test: `gridgain-demo-gradle-plugin/src/test/kotlin/com/gridgain/demo/core/configuration/ProxySchemaTest.kt`

**Reference patterns:** read `data-generator.schema.json` and the test `CdcConnectorSchemaTest.kt` (`DataGeneratorSchemaTest.kt` does not exist; `CdcConnectorSchemaTest` is the closest comparable schema-test harness). Read `postgres.schema.json` for the `image` field convention (flat string, not the `images` table).

- [ ] **Step 1: Write the failing test**

Create `ProxySchemaTest.kt` with:
- `acceptsMinimalCompleteEntry()` — a minimum-valid `proxies.<name>` map (one listener of each supported target kind) validates green.
- `rejectsMissingListeners()` — `listeners: []` fails with a clear message.
- `rejectsDuplicateListenerPorts()` — two listeners on the same `port:` fails.
- `rejectsUnknownTargetKind()` — `target.kind: nonsense` fails.
- `rejectsListenerPortOutOfRange()` — `port: 0` and `port: 70000` both fail.
- `rejectsMissingImage()` — omitted `image` fails.

Use the existing schema-test harness (`SchemaTestSupport` / equivalent — grep `CdcConnectorSchemaTest.kt` for the loader API). Each case provides an inline YAML/JSON snippet and asserts on the violation message.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin
./gradlew test --tests "com.gridgain.demo.core.configuration.ProxySchemaTest"
```

Expected: FAIL with `schema not found: proxy.schema.json` (or equivalent).

- [ ] **Step 3: Write the schema**

Create `proxy.schema.json`. Required fields: `infrastructure`, `k8s_namespace`, `k8s_node_pool_template`, `image`, `pod_resources`, `listeners`, `timeouts`. `disabled` optional default `false`.

`listeners[]` is an array (minItems: 1) of objects with required `{ port, target }`. `port` is integer in `[1, 65535]`. `target` is an object with required `{ kind, name, service, service_port }`. `kind` enum: `["database", "cluster", "cdc_connector", "monitor"]`. `name` and `service` are strings. `service_port` is `[1, 65535]`. (Validation that *which* short-names are valid per kind is enforced by `ProxyValidator`, not the schema — the schema cannot express the cross-kind dependency without a heavyweight `oneOf` discriminator.)

`pod_resources` has `cpu_request`, `memory_request`, `cpu_limit`, `memory_limit` (strings, matches `database.schema.json`'s shape). `timeouts` has `deployment` (integer ≥ 0).

Add a `uniqueItems`-ish constraint via JSON Schema 2020-12 if available, or document the "no duplicate ports" rule as validator-enforced (it has to be validator-enforced anyway because JSON Schema can't express "unique-by-field"; the failing-test case for duplicate ports lands in `ProxyValidatorTest`, not `ProxySchemaTest` — adjust the schema test to remove `rejectsDuplicateListenerPorts` and move that case to Task 5).

- [ ] **Step 4: Wire the schema into `demo.schema.json`**

Add a `proxies` property at the same level as `databases`. Schema: `{ "type": "object", "additionalProperties": { "$ref": "proxy.schema.json" } }`. Match the surrounding pattern verbatim.

- [ ] **Step 5: Run tests, expect green**

```bash
./gradlew test --tests "com.gridgain.demo.core.configuration.ProxySchemaTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/resources/schema/proxy.schema.json \
  src/main/resources/schema/demo.schema.json \
  src/test/kotlin/com/gridgain/demo/core/configuration/ProxySchemaTest.kt
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(schema): proxies.<name> element type"
```

---

## Task 2: `ConfiguredProxy` DTO

**Files:**
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredProxy.kt`

**Reference:** `ConfiguredDataGenerator.kt` (most recent comparable shape).

- [ ] **Step 1: Write the failing test as part of ProxySchemaTest's existing `acceptsMinimalCompleteEntry`**

The schema test already exercises parsing (it deserializes through the existing `ConfigurationParser`). Extend `acceptsMinimalCompleteEntry()` to also assert that the parsed object's fields match the YAML (e.g., `parsed.listeners[0].port == 5432`). It fails right now because `ConfiguredProxy` doesn't exist — that's the gate.

- [ ] **Step 2: Run, expect fail**

```bash
./gradlew test --tests "com.gridgain.demo.core.configuration.ProxySchemaTest"
```

Expected: FAIL — `ConfiguredProxy` class missing.

- [ ] **Step 3: Write the DTO**

```kotlin
package com.gridgain.demo.core.configuration

import com.fasterxml.jackson.annotation.JsonProperty

data class ConfiguredProxyPodResources(
    val cpuRequest: String,
    val memoryRequest: String,
    val cpuLimit: String,
    val memoryLimit: String,
)

data class ConfiguredProxyTimeouts(
    val deployment: Int,
)

data class ConfiguredProxyTarget(
    val kind: String,        // "database" | "cluster" | "cdc_connector" | "monitor" — validated in ProxyValidator
    val name: String,
    val service: String,     // short-name; validated in ProxyValidator
    val servicePort: Int,
)

data class ConfiguredProxyListener(
    val port: Int,
    val target: ConfiguredProxyTarget,
)

data class ConfiguredProxy(
    val disabled: Boolean,
    val infrastructure: String,
    @JsonProperty("k8s_namespace")          val namespace: String,
    @JsonProperty("k8s_node_pool_template") val nodePoolTemplate: String,
    val image: String,
    val podResources: ConfiguredProxyPodResources,
    val listeners: List<ConfiguredProxyListener>,
    val timeouts: ConfiguredProxyTimeouts,
)
```

No nullables. No `= defaultValue` constructor params. `disabled` is NOT optional — JSONSchema's `default: false` pre-fills the UI; the YAML must supply it (workspace rule from project CLAUDE.md).

- [ ] **Step 4: Wire `ConfiguredProxy` into `ConfiguredState`**

Find the `ConfiguredState` data class. Add `val proxies: Map<String, ConfiguredProxy> = emptyMap()`. The default `emptyMap()` is acceptable on the *state* class because it represents "post-migration state for old configs" — back-compat, not parse-time. Match how `dataGenerators` is wired.

- [ ] **Step 5: Run tests, expect green**

```bash
./gradlew test --tests "com.gridgain.demo.core.configuration.ProxySchemaTest"
```

- [ ] **Step 6: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredProxy.kt \
  src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredState.kt \
  src/test/kotlin/com/gridgain/demo/core/configuration/ProxySchemaTest.kt
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(config): ConfiguredProxy DTO + ConfiguredState wiring"
```

---

## Task 3: `MigrateV15toV16` + schema-version bump

**Files:**
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/MigrateV15toV16.kt`
- Modify: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredState.kt` (CURRENT_SCHEMA_VERSION 15→16)
- Modify: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/ConfigMigration.kt` (register migration)
- Test: `gridgain-demo-gradle-plugin/src/test/kotlin/com/gridgain/demo/core/configuration/ConfigMigrationTest.kt` (add `v15ToV16` case)

**Reference:** `MigrateV14toV15.kt` (verbatim shape).

- [ ] **Step 1: Write the failing test (extension to ConfigMigrationTest)**

Add a `@Test fun v15ToV16AddsEmptyProxiesMap()` case to `ConfigMigrationTest`. It loads a minimal v15 YAML (no `proxies:` block), runs the migration runner, asserts:
- `schema_version` = 16
- `proxies` key exists and is an empty map
- No other top-level keys mutated
- Idempotent: re-running the v15→v16 step on a YAML that already has `proxies: {<one-entry>}` leaves it intact.

- [ ] **Step 2: Run, expect fail**

```bash
./gradlew test --tests "com.gridgain.demo.core.configuration.ConfigMigrationTest.v15ToV16AddsEmptyProxiesMap"
```

Expected: FAIL — `MigrateV15toV16` not in registry / not on classpath.

- [ ] **Step 3: Write the migration**

```kotlin
package com.gridgain.demo.core.configuration

/**
 * Introduces the `proxies` top-level key — first-class HAProxy/TCP proxy element type
 * fronting in-cluster backends with a single Service. Initializes an empty `proxies:`
 * map when absent; otherwise passes through unchanged. Idempotent.
 *
 * Bumps `schema_version` 15 -> 16.
 */
class MigrateV15toV16 : ConfigMigration {
    override val fromVersion = 15
    override val toVersion = 16
    override val description = "Add 'proxies' top-level key (HAProxy TCP-proxy element type)"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
        if (yaml["proxies"] !is MutableMap<*, *>) {
            yaml["proxies"] = mutableMapOf<String, Any>()
        }
        yaml["schema_version"] = toVersion
        return yaml
    }
}
```

- [ ] **Step 4: Bump CURRENT_SCHEMA_VERSION + register migration**

In `ConfiguredState.kt`: `const val CURRENT_SCHEMA_VERSION = 16` (was 15).

In `ConfigMigration.kt` `migrations` list, append `MigrateV15toV16()` after `MigrateV14toV15()`.

- [ ] **Step 5: Run all migration tests, expect green**

```bash
./gradlew test --tests "com.gridgain.demo.core.configuration.ConfigMigrationTest"
```

Existing tests (V3→V15) must still pass — the additive migration touches only the trailing slot.

- [ ] **Step 6: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/kotlin/com/gridgain/demo/core/configuration/MigrateV15toV16.kt \
  src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredState.kt \
  src/main/kotlin/com/gridgain/demo/core/configuration/ConfigMigration.kt \
  src/test/kotlin/com/gridgain/demo/core/configuration/ConfigMigrationTest.kt
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(schema): MigrateV15toV16 seeds proxies: {}; CURRENT_SCHEMA_VERSION 15→16"
```

---

## Task 4: State back-compat test

**Why:** Both the `cdc_connectors` and `data_generators` rollouts initially regressed state because a new field on a persisted spec had no default. We lock that in *before* writing any deployer code.

**Files:**
- Test: `gridgain-demo-gradle-plugin/src/test/kotlin/com/gridgain/demo/core/state/ProxyStateBackCompatTest.kt`

**Reference:** `core/state/CdcConnectorStateBackCompatTest.kt` and `core/state/DataGeneratorStateBackCompatTest.kt`. Copy the structure. **Note the package is `core.state`, not `core.configuration`.**

- [ ] **Step 1: Write the failing test**

The test loads a `deployment.yaml` fixture that's missing any `proxies:` block (representing state written by the previous schema version). It deserializes via the same path the runtime uses (`ConfigurationParser` / `DeploymentManager` — match `CdcConnectorStateBackCompatTest`). Asserts: parses without exception; `state.proxies` is the empty map.

- [ ] **Step 2: Run, expect fail**

If `ConfiguredState.proxies` was added with `= emptyMap()` default in Task 2, this may already pass. If so, **the test is the regression gate** — make sure it covers the persisted-spec path too (not just the configured-spec). For comparison, `CdcConnectorStateBackCompatTest` tests `DeployedCdcConnector` deserialization specifically.

If no `DeployedProxy` exists yet, this step is satisfied by the configured-side default + the next task's deployer-side default. Move on; the test re-runs in Task 9 when `DeployedProxy` lands.

- [ ] **Step 3: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/test/kotlin/com/gridgain/demo/core/state/ProxyStateBackCompatTest.kt
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "test(state): back-compat for ConfiguredState.proxies"
```

---

## Task 5: `ProxyServiceRegistry`

**Why:** Listener targets reference other elements by `kind + name + service` short-name (e.g., `cdc_connector + mainframe-to-gg + kafka`). The registry maps those to the actual k8s Service name + namespace conventions the toolkit already uses. Pulling it into its own object keeps the validator and assembler symmetrical.

**Files:**
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/ProxyServiceRegistry.kt`
- Test: `gridgain-demo-gradle-plugin/src/test/kotlin/com/gridgain/demo/core/configuration/ProxyServiceRegistryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gridgain.demo.core.configuration

class ProxyServiceRegistryTest {
    @Test fun `database primary resolves to <name>`() {
        val r = ProxyServiceRegistry.resolve("database", "primary")
        assertThat(r).isNotNull
        assertThat(r!!.serviceNameOf("postgres-mainframe-proxy")).isEqualTo("postgres-mainframe-proxy")
    }

    @Test fun `cdc_connector kafka resolves to <name>-kafka`() {
        val r = ProxyServiceRegistry.resolve("cdc_connector", "kafka")
        assertThat(r!!.serviceNameOf("mainframe-to-gg")).isEqualTo("mainframe-to-gg-kafka")
    }

    @Test fun `cdc_connector kafka_connect resolves to <name>-connect`() {
        val r = ProxyServiceRegistry.resolve("cdc_connector", "kafka_connect")
        assertThat(r!!.serviceNameOf("mainframe-to-gg")).isEqualTo("mainframe-to-gg-connect")
    }

    @Test fun `cluster thin_client and rest both resolve to <name>`() {
        val tc = ProxyServiceRegistry.resolve("cluster", "thin_client")
        val rest = ProxyServiceRegistry.resolve("cluster", "rest")
        assertThat(tc!!.serviceNameOf("mainframe-payments-gg8")).isEqualTo("mainframe-payments-gg8")
        assertThat(rest!!.serviceNameOf("mainframe-payments-gg8")).isEqualTo("mainframe-payments-gg8")
    }

    @Test fun `monitor prometheus resolves to literal prometheus`() {
        val r = ProxyServiceRegistry.resolve("monitor", "prometheus")
        assertThat(r!!.serviceNameOf("pg-gke")).isEqualTo("prometheus")
    }

    @Test fun `unknown short-name returns null`() {
        assertThat(ProxyServiceRegistry.resolve("database", "secondary")).isNull()
        assertThat(ProxyServiceRegistry.resolve("infrastructure", "primary")).isNull()
    }

    @Test fun `advertised short-names per kind`() {
        assertThat(ProxyServiceRegistry.shortNamesFor("database"))
            .containsExactlyInAnyOrder("primary")
        assertThat(ProxyServiceRegistry.shortNamesFor("cluster"))
            .containsExactlyInAnyOrder("thin_client", "rest")
        assertThat(ProxyServiceRegistry.shortNamesFor("cdc_connector"))
            .containsExactlyInAnyOrder("kafka", "kafka_connect")
        assertThat(ProxyServiceRegistry.shortNamesFor("monitor"))
            .containsExactlyInAnyOrder("prometheus", "grafana")
    }
}
```

- [ ] **Step 2: Run, expect fail**

```bash
./gradlew test --tests "com.gridgain.demo.core.configuration.ProxyServiceRegistryTest"
```

- [ ] **Step 3: Implement**

```kotlin
package com.gridgain.demo.core.configuration

/**
 * Resolves a (target.kind, target.service) short-name pair to the actual k8s Service
 * name suffix the toolkit deploys. The proxy spec assembler uses this to build
 * `<svc>.<ns>.svc.cluster.local:<port>` strings for each listener. Pure data; no IO.
 *
 * Control Center monitors are intentionally excluded from v1 (the mainframe-payments
 * demo does not deploy them; see demo CLAUDE.md §15). Add them here when a future demo
 * brings CC back.
 */
object ProxyServiceRegistry {
    data class Entry(val serviceNameSuffix: String) {
        /** `""` suffix means "the element's own name is the service name". */
        fun serviceNameOf(elementName: String): String =
            if (serviceNameSuffix.isEmpty()) elementName else "$elementName$serviceNameSuffix"
    }

    private val table: Map<String, Map<String, Entry>> = mapOf(
        "database"      to mapOf("primary" to Entry("")),
        "cluster"       to mapOf("thin_client" to Entry(""), "rest" to Entry("")),
        "cdc_connector" to mapOf("kafka" to Entry("-kafka"), "kafka_connect" to Entry("-connect")),
        // Monitor short-names are LITERAL service names, not suffixes — the Prometheus-Grafana
        // monitor type names its services 'prometheus' and 'grafana' regardless of element name.
        "monitor"       to mapOf("prometheus" to LiteralEntry("prometheus"), "grafana" to LiteralEntry("grafana")),
    )

    private class LiteralEntry(val literal: String) : Entry("") {
        override fun serviceNameOf(elementName: String): String = literal
    }
    // (Or model this without subclassing — pass a flag through the Entry constructor.
    //  Whichever reads cleanest. Tests pin the behavior, not the representation.)

    fun resolve(kind: String, shortName: String): Entry? = table[kind]?.get(shortName)

    fun shortNamesFor(kind: String): Set<String> = table[kind]?.keys ?: emptySet()
}
```

**Implementation note:** the `LiteralEntry` subclass above is one way to encode the "Prometheus service is literally `prometheus`, not `<name>prometheus`" distinction. An alternative is a flat `Entry(val literal: String?, val suffix: String?)` with conditional logic in `serviceNameOf`. Either is fine; pick whichever passes the tests cleanly.

- [ ] **Step 4: Run tests, expect green**

```bash
./gradlew test --tests "com.gridgain.demo.core.configuration.ProxyServiceRegistryTest"
```

- [ ] **Step 5: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/kotlin/com/gridgain/demo/core/configuration/ProxyServiceRegistry.kt \
  src/test/kotlin/com/gridgain/demo/core/configuration/ProxyServiceRegistryTest.kt
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(proxy): ProxyServiceRegistry — short-name → k8s Service mapping"
```

---

## Task 6: `ProxyValidator`

**Files:**
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/ProxyValidator.kt`
- Modify: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/ConfigurationQueries.kt` (add `listProxyNames`, `hasProxy`, `buildDeployableProxySpec`)
- Test: `gridgain-demo-gradle-plugin/src/test/kotlin/com/gridgain/demo/core/configuration/ProxyValidatorTest.kt`

**Reference:** existing per-element validators are typically called from `ValidateConfigurationAction` or `*Action.prepare`. Grep `ValidateConfigurationAction.kt` for the call sites of `DatabaseValidator` / `CdcConnectorValidator` (or wherever cross-ref checks live) and match that pattern.

- [ ] **Step 1: Add the full triple to `ConfigurationQueries`**

Interface + impl. Mirror the data-generator triple verbatim (line ~67-69 in interface, search for the corresponding impl block):

```kotlin
fun listProxyNames(): Set<String>
fun hasProxy(proxyName: String): Boolean
fun buildDeployableProxySpec(proxyName: String, onWarning: (String) -> Unit = {}): com.gridgain.demo.core.specs.GkeProxySpec
```

The interface convention is: every element kind has all three. The `buildDeployable*Spec` method is what downstream actions call to get a resolved deployable spec — without it, `DeployProxyAction` has no entry point. The implementation delegates to `ProxySpecAssembler` (Task 7).

Until Task 7 lands `ProxySpecAssembler` and `GkeProxySpec`, `buildDeployableProxySpec` can `TODO()`. Wire it up properly in Task 7's commit; flag this as a known intra-PR forward-reference in the commit message.

- [ ] **Step 2: Write the failing test**

Cases:
- `acceptsValidProxy` — happy path; all listeners reference existing elements and valid short-names.
- `rejectsMissingTargetElement` — listener targets `database=nonexistent`; error names the offending listener (e.g. `proxies.payments-proxy.listeners[0]`) and the missing element. **Remediation: "no such entry exists under 'databases'"**.
- `rejectsUnknownShortNameForKind` — `target.kind: database, target.service: secondary`; error lists the kind's advertised short-names.
- `rejectsUnsupportedTargetKind` — `target.kind: data_generator` (or `infrastructure`); error explains which kinds advertise services.
- `rejectsDuplicateListenerPorts` — two listeners with `port: 5432`; error names both.
- `rejectsServicePortOutOfRange` — `service_port: 0` and `70000` fail (defense in depth on top of the schema).

All error messages are rich (workspace rule): name the offending YAML path, the actual value, and the fix.

- [ ] **Step 3: Run, expect fail**

```bash
./gradlew test --tests "com.gridgain.demo.core.configuration.ProxyValidatorTest"
```

- [ ] **Step 4: Implement**

```kotlin
package com.gridgain.demo.core.configuration

import com.gridgain.demo.core.configuration.ConfigurationQueries
import com.gridgain.demo.core.configuration.ConfiguredProxy
import com.gridgain.demo.core.configuration.ProxyServiceRegistry
import com.gridgain.demo.core.configuration.ConfigurationQueries
import com.gridgain.demo.core.configuration.ConfiguredProxy
import com.gridgain.demo.core.exceptions.MisconfigurationException

object ProxyValidator {
    fun validate(cm: ConfigurationQueries, name: String, proxy: ConfiguredProxy) {
        val where = "proxies.$name"

        // 1. No duplicate listener ports.
        val ports = proxy.listeners.map { it.port }
        ports.groupingBy { it }.eachCount().filter { it.value > 1 }.forEach { (port, _) ->
            throw MisconfigurationException(
                "$where has multiple listeners on port $port. Each listener must use a unique port; " +
                    "renumber the duplicates."
            )
        }

        // 2. Per-listener cross-ref.
        proxy.listeners.forEachIndexed { idx, listener ->
            val w = "$where.listeners[$idx]"
            val t = listener.target

            // 2a. target.kind must advertise some services (rejects data_generator, infrastructure, etc.)
            val advertised = ProxyServiceRegistry.shortNamesFor(t.kind)
            if (advertised.isEmpty()) {
                throw MisconfigurationException(
                    "$w.target.kind='${t.kind}' is not a kind that can be a proxy backend. " +
                        "Supported kinds: database, cluster, cdc_connector, monitor."
                )
            }

            // 2b. target.service must be one of those short-names
            if (t.service !in advertised) {
                throw MisconfigurationException(
                    "$w.target.service='${t.service}' is not advertised by kind '${t.kind}'. " +
                        "Valid short-names for this kind: ${advertised.sorted().joinToString(", ")}."
                )
            }

            // 2c. target.name must point at an existing element of that kind
            val exists = when (t.kind) {
                "database"      -> cm.hasDatabase(t.name)
                "cluster"       -> cm.hasCluster(t.name)
                "cdc_connector" -> cm.hasCdcConnector(t.name)
                "monitor"       -> cm.hasMonitor(t.name)
                else            -> false  // unreachable; 2a guards
            }
            if (!exists) {
                throw MisconfigurationException(
                    "$w.target references ${t.kind} '${t.name}', but no such entry exists under '${t.kind}s' " +
                        "in demo-config.yaml."
                )
            }
        }
    }
}
```

- [ ] **Step 5: Wire validator into the config-validation pass**

Find where `DatabaseValidator.validate` / `DataGeneratorValidator.validate` (or their equivalents) are called — most likely `ValidateConfigurationAction.kt`. Add a parallel call: iterate `state.proxies` and call `ProxyValidator.validate(cm, name, proxy)`. Match the surrounding style.

- [ ] **Step 6: Run tests, expect green**

```bash
./gradlew test --tests "com.gridgain.demo.core.configuration.ProxyValidatorTest"
./gradlew test --tests "*ValidateConfiguration*"
```

- [ ] **Step 7: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/kotlin/com/gridgain/demo/core/configuration/ProxyValidator.kt \
  src/main/kotlin/com/gridgain/demo/core/configuration/ConfigurationQueries.kt \
  src/main/kotlin/com/gridgain/demo/core/actions/ValidateConfigurationAction.kt \
  src/test/kotlin/com/gridgain/demo/core/configuration/ProxyValidatorTest.kt
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(proxy): ProxyValidator + ConfigurationQueries triple (list/has/buildDeployable)"
```

---

## Task 7: `GkeProxySpec` + `ProxySpecAssembler`

**Files:**
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/specs/ProxySpec.kt` (file holds the `GkeProxySpec` data class; matches the `DataGeneratorSpec.kt` → `GkeDataGeneratorSpec` naming)
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/ProxySpecAssembler.kt`
- Test: `gridgain-demo-gradle-plugin/src/test/kotlin/com/gridgain/demo/core/configuration/ProxySpecAssemblerTest.kt`

**Reference:** `core/specs/DataGeneratorSpec.kt` + `core/configuration/DataGeneratorSpecAssembler.kt`.

- [ ] **Step 1: Write the failing test**

Cases:
- `resolvesDatabaseListener` — `target: {kind: database, name: postgres-mainframe-proxy, service: primary, service_port: 5432}` becomes `ResolvedListener(port=5432, backendHost="postgres-mainframe-proxy.mainframe-proxy.svc.cluster.local", backendPort=5432)`. The `<ns>` part is read off the referenced `ConfiguredDatabase.namespace`.
- `resolvesCdcConnectorKafkaListener` — `service: kafka` → `<entry-name>-kafka.<cdc-ns>.svc.cluster.local`.
- `resolvesCdcConnectorKafkaConnectListener` — `service: kafka_connect` → `<entry-name>-connect.<cdc-ns>.svc.cluster.local`.
- `resolvesClusterListener` — `service: thin_client` and `rest` both → `<cluster-name>.<cluster-ns>.svc.cluster.local`.
- `resolvesMonitorPrometheusListener` — `service: prometheus` → `prometheus.<monitor-ns>.svc.cluster.local`.
- `usesProxyNodePoolPlacement` — the assembled spec carries `WorkloadScheduling.forElement` keyed on the proxy name, matching the data-generator's anti-co-location pattern.

Use stub `ConfigurationQueries` / `ConfiguredState` fixtures.

- [ ] **Step 2: Run, expect fail**

```bash
./gradlew test --tests "com.gridgain.demo.core.configuration.ProxySpecAssemblerTest"
```

- [ ] **Step 3: Implement `GkeProxySpec` in `core/specs/ProxySpec.kt`**

```kotlin
package com.gridgain.demo.core.specs

import com.gridgain.demo.core.k8s.WorkloadScheduling

data class ResolvedListener(
    val port: Int,
    val backendHost: String,
    val backendPort: Int,
    /** Display label for the HAProxy frontend/backend block. e.g. "database-postgres-primary". */
    val label: String,
)

data class GkeProxyPodResources(
    val cpuRequest: String, val memoryRequest: String,
    val cpuLimit: String,   val memoryLimit: String,
)

data class GkeProxySpec(
    val name: String,
    val namespace: String,
    val nodePool: GkeNodePoolSpec,
    val scheduling: WorkloadScheduling,
    val image: String,
    val podResources: GkeProxyPodResources,
    val listeners: List<ResolvedListener>,
    val deploymentTimeoutSec: Int,
)
```

- [ ] **Step 4: Implement `ProxySpecAssembler`**

Walks `ConfiguredProxy.listeners`, calls `ProxyServiceRegistry.resolve` for each, looks up the referenced element's namespace via `ConfigurationQueries`, and emits `ResolvedListener` with `backendHost = "<svc>.<ns>.svc.cluster.local"`. Resolves the proxy's own node pool template via the existing helper used by `DataGeneratorSpecAssembler`. Carries `WorkloadScheduling.forElement(name, "proxy")` (or equivalent — match what data-generator uses).

The `label` field on `ResolvedListener` is for HAProxy's `frontend`/`backend` block names — make it `"${kind}-${name}-${service}"` with any dots/dashes left alone (HAProxy accepts those).

If `ProxyServiceRegistry.resolve` returns `null`, throw a `MisconfigurationException` with a clear message — but in practice this is unreachable because `ProxyValidator` runs first and catches it. The duplicate-but-defense-in-depth check is fine.

- [ ] **Step 5: Run tests, expect green**

- [ ] **Step 6: Wire `buildDeployableProxySpec` in `ConfigurationQueriesImpl`**

The interface method added in Task 6 had a placeholder `TODO()`. Now that `ProxySpecAssembler` exists, replace `TODO()` with a delegation: `ProxySpecAssembler.assemble(this, proxyName, onWarning)` (matching how `buildDeployableDataGeneratorSpec` delegates to `DataGeneratorSpecAssembler`). Run the full suite again; everything that was green stays green and `ConfigurationQueriesTest` (if it exists) now exercises the new path.

- [ ] **Step 7: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/kotlin/com/gridgain/demo/core/specs/ProxySpec.kt \
  src/main/kotlin/com/gridgain/demo/core/configuration/ProxySpecAssembler.kt \
  src/main/kotlin/com/gridgain/demo/core/configuration/ConfigurationQueries.kt \
  src/test/kotlin/com/gridgain/demo/core/configuration/ProxySpecAssemblerTest.kt
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(proxy): GkeProxySpec + ProxySpecAssembler + buildDeployableProxySpec wiring"
```

---

## Task 8: K8s manifest templates

**Files (create):**
- `gridgain-demo-gradle-plugin/src/main/resources/templates/k8s/proxy/namespace.yaml`
- `gridgain-demo-gradle-plugin/src/main/resources/templates/k8s/proxy/configmap.yaml`
- `gridgain-demo-gradle-plugin/src/main/resources/templates/k8s/proxy/deployment.yaml`
- `gridgain-demo-gradle-plugin/src/main/resources/templates/k8s/proxy/service.yaml`

**Reference:** copy `templates/k8s/database/*.yaml` shapes and adapt. The data-generator templates show the most modern conventions (anti-co-location, ConfigMap mounting).

- [ ] **Step 1: Write the four templates**

`namespace.yaml`:
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: {{proxy.namespace}}
  labels:
    {{> include/ownership-labels.yaml}}
```

`configmap.yaml`:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{proxy.name}}-haproxy-cfg
  namespace: {{proxy.namespace}}
  labels:
    {{> include/ownership-labels.yaml}}
data:
  haproxy.cfg: |
    global
      log stdout format raw local0
      daemon
    defaults
      mode tcp
      timeout connect 5s
      timeout client 1h
      timeout server 1h
      log global
      option tcplog
    {{#proxy.listeners}}
    frontend f_{{label}}
      bind *:{{port}}
      default_backend b_{{label}}

    backend b_{{label}}
      server {{label}}_target {{backendHost}}:{{backendPort}} check inter 5s
    {{/proxy.listeners}}
```

`deployment.yaml`: standard Deployment with one container running `image: {{proxy.image}}`, mounting the ConfigMap at `/usr/local/etc/haproxy/haproxy.cfg`, exposing `containerPort: {{port}}` for each listener. Resource requests/limits from `proxy.podResources`. NodeSelector + tolerations from `proxy.scheduling`.

`service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{proxy.name}}
  namespace: {{proxy.namespace}}
  labels:
    {{> include/ownership-labels.yaml}}
spec:
  type: ClusterIP
  selector:
    app: {{proxy.name}}
  ports:
    {{#proxy.listeners}}
    - name: {{label}}
      port: {{port}}
      targetPort: {{port}}
      protocol: TCP
    {{/proxy.listeners}}
```

- [ ] **Step 2: No test in this task** — the templates are gated by `ProxyTemplateModelTest` (Task 9).

- [ ] **Step 3: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/resources/templates/k8s/proxy/
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(proxy): k8s manifest templates (HAProxy 2.9 TCP)"
```

---

## Task 9: `ProxyTemplateModel` + golden fixture

**Files:**
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/recording/ProxyTemplateModel.kt`
- Create: `gridgain-demo-gradle-plugin/src/test/resources/fixtures/haproxy.cfg.expected`
- Test: `gridgain-demo-gradle-plugin/src/test/kotlin/com/gridgain/demo/core/recording/ProxyTemplateModelTest.kt`

**Reference:** `DataGeneratorTemplateModel.kt` + `DataGeneratorTemplateModelTest.kt`.

- [ ] **Step 1: Author the golden fixture**

Hand-write `haproxy.cfg.expected` as the exact text the demo's 7-listener config should produce when rendered. Use the listener list from `demo-config.yaml` §2 of the design doc. (You can run a one-off `haproxy -c -f <(echo "...")` sanity check locally if you have HAProxy installed; not required.)

- [ ] **Step 2: Write the failing test**

```kotlin
class ProxyTemplateModelTest {
    @Test fun `rendered haproxy_cfg matches golden fixture for the demo's 7-listener config`() {
        val spec = GkeProxySpec(/* the 7 listeners from the spec doc */)
        val rendered = ProxyTemplateModel(spec).renderHaproxyConfig()
        val expected = javaClass.getResource("/fixtures/haproxy.cfg.expected")!!.readText()
        assertThat(rendered).isEqualTo(expected)
    }
}
```

- [ ] **Step 3: Run, expect fail**

- [ ] **Step 4: Implement `ProxyTemplateModel`**

Mustache integration mirrors `DataGeneratorTemplateModel`. Expose three render methods: `renderConfigMap()`, `renderDeployment()`, `renderService()`. The configmap renderer is the one the test pins; the deployment/service renderers are exercised end-to-end by the deployer (Task 10).

- [ ] **Step 5: Run, expect green**

- [ ] **Step 6: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/kotlin/com/gridgain/demo/core/recording/ProxyTemplateModel.kt \
  src/test/kotlin/com/gridgain/demo/core/recording/ProxyTemplateModelTest.kt \
  src/test/resources/fixtures/haproxy.cfg.expected
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(proxy): ProxyTemplateModel + haproxy.cfg golden fixture"
```

---

## Task 10: `ProxyDeployer` + `ProxyDestroyer`

**Files:**
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/deployment/ProxyDeployer.kt`
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/deployment/ProxyDestroyer.kt`
- Test: `gridgain-demo-gradle-plugin/src/test/kotlin/com/gridgain/demo/core/deployment/ProxyDestroyerTest.kt` (specifically pin the namespace-safe behavior)

**Reference:** `DataGeneratorDeployer.kt` + `DataGeneratorDestroyer.kt` end-to-end. The "skip namespace delete when shared" behavior is the post-`13349474` shape — read that commit for context.

- [ ] **Step 1: Write the failing test for ProxyDestroyer**

Two cases:
- `teardownDoesNotDeleteNamespace_whenNamespaceIsExclusive` — even though `payments-proxy` namespace is exclusive to the proxy, the destroyer should NOT issue `kubectl delete namespace/...`. It deletes only the elements it created (Deployment, Service, ConfigMap). Mirrors `DataGeneratorPluginTest` post-fix.
- `teardownIsSafeToReRun` — running teardown when nothing is deployed succeeds (no exceptions; idempotent).

- [ ] **Step 2: Run, expect fail**

- [ ] **Step 3: Implement Deployer**

`ProxyDeployer` emits an ordered command list (matching `DatabaseDeployer`'s style):
1. `kubectl apply -f namespace.yaml`
2. `kubectl apply -f configmap.yaml`
3. `kubectl apply -f service.yaml`
4. `kubectl apply -f deployment.yaml`
5. Wait for Deployment ready (`kubectl rollout status deployment/<name>` with the spec's `deploymentTimeoutSec`).

Also resolves the node pool: if `wp-<proxy-name>` exists, reuse it; otherwise create it from the referenced node pool template. (Or: the proxy can share the `default-gke-pool` — check what data-generator does and match. If the proxy's `k8s_node_pool_template` points at an existing demo pool, no new pool is created.)

- [ ] **Step 4: Implement Destroyer**

```kotlin
override val destroyCommands: List<List<String>>
    get() = listOf(
        listOf("kubectl", "delete", "--ignore-not-found=true", "-n", spec.namespace,
               "deployment/${spec.name}"),
        listOf("kubectl", "delete", "--ignore-not-found=true", "-n", spec.namespace,
               "service/${spec.name}"),
        listOf("kubectl", "delete", "--ignore-not-found=true", "-n", spec.namespace,
               "configmap/${spec.name}-haproxy-cfg"),
        // NO `kubectl delete namespace/...` — per the data-generator #3 lesson.
        // If the namespace becomes empty after teardown, k8s GC can clean it,
        // or the infrastructure teardown removes it.
    )
```

- [ ] **Step 5: Run tests, expect green**

- [ ] **Step 6: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/kotlin/com/gridgain/demo/core/deployment/ProxyDeployer.kt \
  src/main/kotlin/com/gridgain/demo/core/deployment/ProxyDestroyer.kt \
  src/test/kotlin/com/gridgain/demo/core/deployment/ProxyDestroyerTest.kt
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(proxy): ProxyDeployer + ProxyDestroyer (namespace-safe teardown)"
```

---

## Task 11: `DeployProxyAction` + `TeardownProxyAction`

**Files:**
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/actions/DeployProxyAction.kt`
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/actions/TeardownProxyAction.kt`

**Reference:** `DeployDataGeneratorAction.kt` + `TeardownDataGeneratorAction.kt` end-to-end.

- [ ] **Step 1: Implement DeployProxyAction**

`prepare(ctx)`: validate config (calls `ValidateConfigurationAction` flow or the inline equivalent), assemble spec via `ProxySpecAssembler`, store on `state`.

`execute(ctx)`: invoke `ProxyDeployer`. Update deployed state. Handle `dryRun` by rendering manifests under `<demoOutputDirectory>/k8s/proxies/<name>/` and skipping `kubectl apply`.

- [ ] **Step 2: Implement TeardownProxyAction**

Mirror image. Delegates to `ProxyDestroyer`. Updates deployed state to mark torn-down.

- [ ] **Step 3: Tests are integration-shaped**

No new unit tests in this task — the action's behavior is exercised by the assembly dispatcher tests (Task 12) and by the end-to-end verification at the close of the plan. If you find a clean unit-test seam (e.g. mock the deployer, assert the action calls it), add it; otherwise let the next task's tests cover.

- [ ] **Step 4: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/kotlin/com/gridgain/demo/core/actions/DeployProxyAction.kt \
  src/main/kotlin/com/gridgain/demo/core/actions/TeardownProxyAction.kt
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(proxy): DeployProxyAction + TeardownProxyAction"
```

---

## Task 12: Assembly walker integration

**Files:**
- Modify: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredAssembly.kt:15-25` — add `PROXY("proxy")` to enum.
- Modify: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/core/actions/AssemblyDispatcher.kt:21,49-91,138-179` — add `PROXY` to `describe()`, `AssemblyValidator.validateElements`, `buildDeployActionFor`, `buildTeardownActionFor`.
- Test: extend `AssemblyValidatorTest.kt` (this is the only assembly-side test in `core/actions/` today). If the dispatcher-side cases are awkward to fold in, create a new `AssemblyDispatcherTest.kt` alongside it.

- [ ] **Step 1: Write the failing test**

In `AssemblyValidatorTest.kt` (or a new `AssemblyDispatcherTest.kt` if cleaner), add:
- `proxy ref dispatches to DeployProxyAction` — `ConfiguredAssemblyElementRef(kind=PROXY, name="payments-proxy")` produces a `DeployProxyAction("payments-proxy")` (or whatever constructor the action takes).
- Same for teardown.
- `validator catches missing proxy element` — assembly references `kind: proxy, name: nonexistent`; `AssemblyValidator.validateElements` throws with the standard message.

- [ ] **Step 2: Run, expect fail**

- [ ] **Step 3: Add the enum entry**

```kotlin
enum class AssemblyElementKind(@get:JsonValue val yamlKey: String) {
    // ...existing entries...
    DATA_GENERATOR("data_generator"),
    DATA_MODEL("data_model"),
    CLUSTER_DCR("cluster_dcr"),
    PROXY("proxy"),
    ;
    // ...
}
```

- [ ] **Step 4: Add the dispatcher branches**

In `AssemblyElementDispatcher.buildDeployActionFor`:
```kotlin
AssemblyElementKind.PROXY -> DeployProxyAction(ref.name!!)
```

In `AssemblyElementDispatcher.buildTeardownActionFor`:
```kotlin
AssemblyElementKind.PROXY -> TeardownProxyAction(ref.name!!)
```

In `AssemblyValidator.validateElements`:
```kotlin
AssemblyElementKind.PROXY -> {
    val n = requireName(where, ref)
    requireExists(where, "proxy", n) { cm.hasProxy(n) }
}
```

In the top-level `describe()` extension function, add `PROXY` to the same `when` arm as the other name-uses-name kinds (`INFRASTRUCTURE`, `CLUSTER`, `MONITOR`, `DATABASE`, `CDC_CONNECTOR`, `DATA_GENERATOR`).

- [ ] **Step 5: Run tests, expect green**

```bash
./gradlew test --tests "*Assembly*"
```

- [ ] **Step 6: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/kotlin/com/gridgain/demo/core/configuration/ConfiguredAssembly.kt \
  src/main/kotlin/com/gridgain/demo/core/actions/AssemblyDispatcher.kt \
  src/test/kotlin/com/gridgain/demo/core/actions/AssemblyValidatorTest.kt
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(assembly): PROXY element kind in walker"
```

---

## Task 13: Gradle tasks + plugin registration

**Files:**
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/plugin/tasks/DeployProxyTask.kt`
- Create: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/plugin/tasks/TeardownProxyTask.kt`
- Modify: `gridgain-demo-gradle-plugin/src/main/kotlin/com/gridgain/demo/plugin/GridGainDemoPlugin.kt` (register tasks)

**Reference:** `DeployDataGeneratorTask.kt` + the data-generator registration block in `GridGainDemoPlugin.kt`.

- [ ] **Step 1: Implement the tasks**

Each task is a thin shell that:
- Accepts `-PproxyName=<name>` (omit → operate on all entries under `proxies`).
- Constructs the corresponding action and invokes it via the standard task pipeline.
- For `DeployProxyTask`: validation runs via the same convention the other `Deploy*Task`s use (explicit Gradle `dependsOn` vs. action-level `prepare`); pick whichever the data-generator task uses and match verbatim.

- [ ] **Step 2: Register in `GridGainDemoPlugin.kt`**

In whichever block registers `DeployDataGeneratorTask`/`TeardownDataGeneratorTask`, register `DeployProxyTask("deployProxy")` and `TeardownProxyTask("teardownProxy")`. Match the surrounding style.

- [ ] **Step 3: Run the full test suite — expect green**

```bash
./gradlew test
```

This is the first time we run the FULL suite end-to-end. All ~752 existing tests stay green; new tests (schema, migration, validator, assembler, registry, template-model, destroyer, dispatcher) add ~30-40 more.

- [ ] **Step 4: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add \
  src/main/kotlin/com/gridgain/demo/plugin/tasks/DeployProxyTask.kt \
  src/main/kotlin/com/gridgain/demo/plugin/tasks/TeardownProxyTask.kt \
  src/main/kotlin/com/gridgain/demo/plugin/GridGainDemoPlugin.kt
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "feat(proxy): deployProxy + teardownProxy Gradle tasks"
```

---

## Task 14: Plugin docs

**Files:**
- Create: `gridgain-demo-gradle-plugin/docs/proxies.md`
- Modify: `gridgain-demo-gradle-plugin/docs/README.md` or root `README.md` index, if there's an element-types table to extend

**Reference:** `gridgain-demo-gradle-plugin/docs/data-generators.md` + `cdc-connectors.md`.

- [ ] **Step 1: Write the doc**

Sections to cover:
- *Element shape* — full schema with annotated YAML example matching the demo's listener list.
- *Service-name registry* — the table from §2 of the spec.
- *Gradle tasks* — `deployProxy` / `teardownProxy` (+ `-PproxyName`, `-PdryRun`).
- *Validation rules* — listed plainly.
- *Trade-offs* — single listener per GG cluster loses partition awareness; ClusterIP requires one port-forward from the laptop; defer-able `pod_ordinals: [...]` for future per-pod listeners.

- [ ] **Step 2: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin add docs/proxies.md
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin commit -m "docs(proxy): element-type reference"
```

---

## Task 15: Push branch + open PR

- [ ] **Step 1: Run the full suite one more time, lint-clean**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin && ./gradlew test
```

- [ ] **Step 2: Push the branch**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin push -u origin feat/proxy-element
```

- [ ] **Step 3: Open a PR**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/gridgain-demo-gradle-plugin
gh pr create --title "feat: 'proxies' element type (HAProxy TCP, ClusterIP) + schema_version 15→16" --body "$(cat <<'EOF'
## Summary
- New first-class `proxies` element type fronting in-cluster backends with a single HAProxy/TCP proxy.
- Schema bump 15 → 16, additive `MigrateV15toV16` seeds `proxies: {}`.
- Walker integration so assemblies can include `{ kind: proxy, name: <x> }`.
- Listener targets reference other toolkit elements by `kind + name + service` short-name; assembler resolves to `<svc>.<ns>:port` at deploy time.
- Namespace-safe teardown (the data-generator #3 lesson).

## Demo consumption
The `mainframe-payments-demo` repo consumes this on commit `<sha>` to collapse its eight kubectl port-forwards into one (single forward against the proxy Service). Spec: https://github.com/GridGain-Demos/mainframe-payments-demo/blob/main/docs/2026-06-23-proxy-element-design.md

## Test plan
- [x] `./gradlew test` — all existing tests green + new tests for schema, migration, validator, assembler, registry, template-model, destroyer, walker integration.
- [ ] End-to-end smoke from the demo repo (verification steps in §5 of the spec).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: Note the PR URL for the track-coordination READY entry (Task 21).**

---

## Task 16: Demo — schema bump + `proxies` entry

> All remaining tasks are in `mainframe-payments-demo/`. Per workspace memory `git-branch-policy.md`, commit to `main`. Per memory `pushing-pre-authorized.md`, push freely.

**Files:**
- Modify: `mainframe-payments-demo/src/main/resources/demo-config.yaml`

- [ ] **Step 1: Bump schema_version**

Line 1: `schema_version: 16` (was 15).

- [ ] **Step 2: Add the `proxies.payments-proxy` entry**

After the `data_generators:` block (around line 492) and before `assemblies:`, insert the full entry from §2 of the design doc.

- [ ] **Step 3: Run `validateDemoConfiguration` against the local plugin worktree**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo && ./gradlew validateDemoConfiguration
```

Expected: green (the local `includeBuild` of the plugin now points at the `feat/proxy-element` branch; you may need `git -C ../gridgain-demo-gradle-plugin checkout feat/proxy-element` first).

- [ ] **Step 4: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo add src/main/resources/demo-config.yaml
git -C /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo commit -m "feat(config): schema 15→16, add proxies.payments-proxy entry"
```

---

## Task 17: Demo — assembly entry update

**Files:**
- Modify: `mainframe-payments-demo/src/main/resources/demo-config.yaml` (assemblies block)

- [ ] **Step 1: Append `{ kind: proxy, name: payments-proxy }` to `assemblies.mainframe-payments.elements`**

After the existing `- kind: data_generator / name: payments-load` entry (around line 538), add:

```yaml
      - kind: proxy
        name: payments-proxy
```

- [ ] **Step 2: Validate**

```bash
./gradlew validateDemoConfiguration
```

- [ ] **Step 3: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo add src/main/resources/demo-config.yaml
git -C /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo commit -m "feat(assembly): include payments-proxy in mainframe-payments assembly"
```

---

## Task 18: Demo — `dev-port-forwards.sh` collapse

**Files:**
- Modify: `mainframe-payments-demo/scripts/dev-port-forwards.sh`

- [ ] **Step 1: Replace the FORWARDS array**

Lines 32–41 of `scripts/dev-port-forwards.sh` (the `FORWARDS=(...)` block). Replace with:

```bash
FORWARDS=(
  "payments-proxy|svc/payments-proxy|5432:5432,3306:3306,9094:9094,8083:8083,10800:10800,8080:8080,9090:9090|All demo backends (HAProxy multi-port)"
)
```

- [ ] **Step 2: Update the header comment**

Update the top-of-file comment block to explain the new shape: one forward → multi-port HAProxy. Mention that the 8 individual forwards from before are replaced by a single proxy listener mapping.

- [ ] **Step 3: Verify the script still parses**

```bash
bash -n /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo/scripts/dev-port-forwards.sh
```

Expected: no syntax errors.

- [ ] **Step 4: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo add scripts/dev-port-forwards.sh
git -C /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo commit -m "feat(scripts): collapse 8 dev port-forwards to a single multi-port forward via payments-proxy"
```

---

## Task 19: Demo — error-message cleanup

**Files (modify, text-only):**
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/config/UiConfig.kt`
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/services/GridGainService.kt`
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/services/MainframeProxyService.kt`
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/services/MariaDbService.kt`
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/services/ConnectorControlService.kt`
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/tailer/KafkaTailerTap.kt`
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/GeneratorMetricsService.kt`
- `demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/PrometheusCpuService.kt`
- `demo-ui/frontend/src/components/GridGainPanel.tsx:110`

- [ ] **Step 1: Apply text changes**

Each file has error messages or comments referencing `kubectl port-forward` against individual services. Replace with the single-forward narrative:
- *Old:* "Start a port-forward to the GG client port and retry."
- *New:* "Start the payments-proxy port-forward (`scripts/dev-port-forwards.sh`) and retry."

In `UiConfig.kt`, the per-service "Dev default targets a port-forward of..." comments collapse to one block-level comment near the top of the env-var section explaining that all `localhost:*` defaults assume the `payments-proxy` is being port-forwarded.

In `GridGainPanel.tsx:110`, update the `kubectl port-forward` mention in the operator help blurb similarly.

- [ ] **Step 2: Verify the demo UI still compiles**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo
./gradlew :demo-ui:compileKotlin
( cd demo-ui/frontend && npx tsc --noEmit )
```

Expected: green for both.

- [ ] **Step 3: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo add \
  demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/config/UiConfig.kt \
  demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/services/ \
  demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/tailer/ \
  demo-ui/src/main/kotlin/com/gridgain/demo/payments/ui/metrics/ \
  demo-ui/frontend/src/components/GridGainPanel.tsx
git -C /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo commit -m "docs(ui): single-forward narrative in error messages and operator help"
```

---

## Task 20: Demo — auto-memory entry

**Files:**
- Modify: `/Users/davidbrown/.claude/projects/-Users-davidbrown-Code-DemoGradleProject-mainframe-payments-demo/memory/MEMORY.md` (add a line)
- Create: `/Users/davidbrown/.claude/projects/-Users-davidbrown-Code-DemoGradleProject-mainframe-payments-demo/memory/single-port-forward-via-proxy.md`

- [ ] **Step 1: Write the memory file**

```markdown
---
name: single-port-forward-via-proxy
description: All laptop→cluster traffic goes through `kubectl port-forward svc/payments-proxy` (multi-port). The HAProxy `proxies.payments-proxy` element multiplexes 7 backend services on one ClusterIP Service.
metadata:
  type: project
---

The demo's `scripts/dev-port-forwards.sh` runs **one** `kubectl port-forward svc/payments-proxy 5432 3306 9094 8083 10800 8080 9090`, not eight individual forwards as before. All UI backend `localhost:<port>` defaults exit through that single tunnel, and the HAProxy pod routes each port to the right in-cluster Service (Postgres, MariaDB, Kafka EXTERNAL, Kafka Connect REST, GG thin-client + REST, Prometheus).

**Why:** Before this change, eight independent kubectl port-forward processes could silently fail one at a time, causing intermittent "tailer empty / Connect REST timeout / GG hangs" debug rabbit-holes. One process to one stable in-cluster Service eliminates that whole category.

**How to apply:**
- If a UI backend connection times out, check the *one* port-forward process — `scripts/dev-port-forwards.sh status` shows up/down/STALE for the multi-port forward.
- The proxy is a toolkit element (`proxies.payments-proxy` in demo-config.yaml). To bring it up: `./gradlew deployProxy -PproxyName=payments-proxy`. It's also part of the `mainframe-payments` assembly.
- GG thin-client partition awareness is intentionally off — the single listener routes through the headless Service and kube-proxy load-balances across GG pods. Adds <1ms per call; fine for demo correctness.
```

- [ ] **Step 2: Add the index line to MEMORY.md**

Append (or insert in alphabetical position):

```markdown
- [Single port-forward via proxy](single-port-forward-via-proxy.md) — `kubectl port-forward svc/payments-proxy` carries all 7 demo backends; one tunnel, one HAProxy pod.
```

- [ ] **Step 3: Commit (memory-only; no git in the memory directory — these files persist across sessions)**

No commit step — the memory directory isn't a git repo.

---

## Task 21: Demo — track-coordination READY + INTEGRATED entry

**Files:**
- Modify: `mainframe-payments-demo/docs/track-coordination.md` (append)

- [ ] **Step 1: Append the two entries**

(Use today's date, 2026-06-23, and the plugin PR URL from Task 15.)

```markdown
- **2026-06-23 · READY (B→A) · proxies element + single dev port-forward** — New first-class
  `proxies` element on plugin branch `feat/proxy-element` (PR: <URL from Task 15>). Additive schema
  bump 15 → 16. HAProxy 2.9-alpine TCP proxy, ClusterIP, fronts the seven in-cluster backends the
  demo UI reaches today (Postgres :5432, MariaDB :3306, Kafka :9094, Kafka Connect :8083, GG :10800
  + :8080, Prometheus :9090). Listener targets reference other elements by `kind + name + service`
  short-name; the assembler resolves to `<svc>.<ns>.svc.cluster.local:<port>` at deploy time. Namespace-
  safe teardown (data-generator #3 lesson applied). Demo-side: add `proxies.payments-proxy` to
  demo-config.yaml, append `{ kind: proxy, name: payments-proxy }` to the `mainframe-payments`
  assembly, and collapse `scripts/dev-port-forwards.sh` to a single multi-port forward against
  `svc/payments-proxy`. UI backend Kotlin/TS is unchanged — same `localhost:<port>` defaults; the
  port-forward is what changes.

- **2026-06-23 · INTEGRATED (A) · proxies element live** — Demo consumes plugin `feat/proxy-element`
  at commit `<sha>`. `deployAssembly -PassemblyName=mainframe-payments` brings the proxy up after
  all backends; `kubectl port-forward svc/payments-proxy <ports>` carries the laptop UI backend;
  proxy pod restart is transparent (Service stable). 8 forwards → 1.
```

- [ ] **Step 2: Commit**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo add docs/track-coordination.md
git -C /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo commit -m "docs(track-coordination): proxies element READY + INTEGRATED"
```

- [ ] **Step 3: Push the demo branch**

```bash
git -C /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo push origin main
```

---

## End-to-end verification

This is the final gate before declaring the work complete. Run it on the live cluster only after the plugin PR (Task 15) is reviewed and the local `includeBuild` is pointing at the merged branch (or `feat/proxy-element` directly).

- [ ] **Step 1: Validate**

```bash
cd /Users/davidbrown/Code/DemoGradleProject/mainframe-payments-demo && ./gradlew validateDemoConfiguration
```

- [ ] **Step 2: Deploy the assembly**

```bash
./gradlew deployAssembly -PassemblyName=mainframe-payments
```

Expected: proxy comes up last in the walk. `kubectl get pods -n payments-proxy` shows the HAProxy pod `Running 1/1`. `kubectl get svc payments-proxy -n payments-proxy -o yaml` lists 7 ports.

- [ ] **Step 3: Functional smoke from the proxy pod**

```bash
NS=payments-proxy
POD=$(kubectl -n $NS get pod -l app=payments-proxy -o jsonpath='{.items[0].metadata.name}')
for target in \
  "postgres-mainframe-proxy.mainframe-proxy:5432" \
  "mariadb-analytics.mariadb-analytics:3306" \
  "mainframe-to-gg-kafka.cdc-pipeline:9094" \
  "mainframe-to-gg-connect.cdc-pipeline:8083" \
  "mainframe-payments-gg8.mainframe-payments-gg8:10800" \
  "mainframe-payments-gg8.mainframe-payments-gg8:8080" \
  "prometheus.gg-prometheus-grafana:9090"; do
  kubectl exec -n $NS $POD -- sh -c "nc -zv $target 2>&1" || echo "FAILED: $target"
done
```

Expected: all seven targets connect.

- [ ] **Step 4: Start the laptop side**

```bash
./scripts/dev-port-forwards.sh stop || true
./scripts/dev-port-forwards.sh
./scripts/dev-port-forwards.sh status
```

Expected: one process listed; `UP`.

```bash
for p in 5432 3306 9094 8083 10800 8080 9090; do
  lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null && echo "LISTEN :$p" || echo "MISSING :$p"
done
```

Expected: all `LISTEN`.

- [ ] **Step 5: Run the UI**

```bash
./gradlew :demo-ui:run
```

Open http://localhost:8090 (or whatever the demo UI port is). Phase 0 reads the mainframe panel from Postgres. Walk phases 1–5; tailers populate; Kafka Connect pause/resume works; bulk dump+load works. Anything that breaks here is either a port-forward problem (see Step 4) or a backend problem unrelated to this change.

- [ ] **Step 6: Resilience proof**

```bash
kubectl delete pod -l app=payments-proxy -n payments-proxy
```

Expected: the proxy pod recycles in ~10s. The laptop's `kubectl port-forward` keeps running (the Service is stable). UI backend reconnects per existing retry logic. **This is the resilience win — old behavior would have required restarting eight separate forwards.**

- [ ] **Step 7: Teardown**

```bash
./gradlew teardownAssembly -PassemblyName=mainframe-payments
```

Expected: proxy comes down with everything else. No residual `payments-proxy` resources (`kubectl get all -n payments-proxy` empty / namespace gone via infrastructure teardown). No leftover GCP LoadBalancer (we didn't allocate one).

If all seven steps pass, the work is complete.

---

## Notes for the implementer

- **Tests use the existing harness conventions.** Don't introduce new test infrastructure. If you find yourself wanting to add MockK setup that the rest of the toolkit doesn't use, stop and copy the pattern from `DataGenerator*Test.kt` instead.
- **Don't add nullable types.** If a field "might be missing" — make the schema require it and let the migration seed it for older configs (the additive-migration pattern).
- **Rich error messages everywhere.** A `MisconfigurationException` that just says "invalid config" is a regression. Name the YAML path, the value, and the fix.
- **Frequent commits.** Each step's commit is part of the design — it gives the reviewer (and you) a small unit to roll back if something goes sideways. Don't squash until the PR review asks for it.
- **One question to surface early if it blocks you:** the workspace memory `parallel-toolkit-deploys-race.md` warns that `deployment.yaml` has no locking; *but* commit `db6e6daa` added an exclusive `FileLock`. The proxy element shouldn't need to worry about this, but if you see a test that exercises concurrent deploys, check whether the lock is being used as expected.

