# Mainframe Payments Demo

A GridGain Demo Toolkit demo illustrating incremental migration of high-throughput, low-latency payment workloads off a mainframe onto a modern stack: a Postgres mainframe-proxy in front of a **GridGain 8** cluster, with parallel write-through to a **MariaDB** operational target and one-way CDC (Debezium + Kafka) flowing the other direction. A three-panel React UI walks an audience through the modernization story in 5–10 minutes.

## Status

**Specification / pre-implementation.** The architecture, deployment topology, UI layout, data flow, plugin extensions, and verification plan are designed and captured in [CLAUDE.md](CLAUDE.md). The Gradle build harness is wired up and passes `validateDemoConfiguration` against a copy of the toolkit-dev config.

No demo-specific source code, UI, or plugin extensions exist yet. The first implementation task is slimming the placeholder config to the single-GKE / GG8 / Postgres + MariaDB shape described in [CLAUDE.md §11](CLAUDE.md).

## What's here today

```
mainframe-payments-demo/
├── CLAUDE.md                         # full design spec — start here
├── README.md                         # this file
├── build.gradle.kts                  # composite build, includes plugin + UI from siblings
├── settings.gradle.kts
├── gradle.properties                 # points at src/main/resources/demo-config.yaml
├── gradlew, gradlew.bat, gradle/     # standard Gradle wrapper
└── src/main/resources/
    └── demo-config.yaml              # toolkit-dev config copy; slimmed during implementation
```

## Workspace context

This project lives as a sibling under the `DemoGradleProject` workspace alongside the toolkit projects it consumes:

| Sibling                                                            | Role                                                                                          |
|--------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| [gridgain-demo-gradle-plugin](../gridgain-demo-gradle-plugin)      | Gradle tasks this demo invokes (`deployCluster`, `dataGenerate`, etc.) and the new `databases` / `cdc_connectors` element types added to support this demo. |
| [gridgain-demo-ui](../gridgain-demo-ui)                            | Operator-facing config + ops UI. Separate from this demo's audience-facing UI (see [CLAUDE.md §5](CLAUDE.md)). |
| [gridgain-demo-data-generator](../gridgain-demo-data-generator)    | Synthetic transaction generator used for the phase-5 load injection.                          |
| [gridgain-demo-client-utils](../gridgain-demo-client-utils)        | Connection plumbing reused by this demo's Ktor backend.                                       |
| [gridgain-demo-toolkit-dev](../gridgain-demo-toolkit-dev)          | Active dev workspace; the source of this demo's bootstrap.                                    |

[settings.gradle.kts](settings.gradle.kts) uses `includeBuild("../gridgain-demo-gradle-plugin")` and `includeBuild("../gridgain-demo-ui")` for live development against the sibling builds. SNAPSHOT artifacts from the sibling builds substitute for any versioned references in [build.gradle.kts](build.gradle.kts).

## Prerequisites

- **Java 17** — the Gradle toolchain downloads it if missing.
- All sibling subprojects present and buildable at `../gridgain-demo-*`.
- **Node.js 20+** via Homebrew (`brew install node`) — needed once the React UI work begins.
- **GKE access + `gcloud`, `kubectl`** — needed once deployment work begins. See [gridgain-demo-template/README.md](../gridgain-demo-template/README.md) for the cloud-CLI setup walkthrough.

## Verifying the bootstrapped build

From this directory:

```
./gradlew tasks                      # confirm plugin loads and exposes the standard demo tasks
./gradlew validateDemoConfiguration  # confirm the placeholder config schema-validates
```

Both commands should complete successfully against the current scaffolding.

## Live-dev gotchas

### `ZipFile invalid LOC header (bad signature)` from the UI

**Symptom.** The toolkit UI (launched via `./gradlew launchPluginUi`) renders an error like:

> Failed to load schema 'demo': ZipFile invalid LOC header (bad signature)

and the server log shows a `java.util.zip.ZipException: ZipFile invalid LOC header (bad signature)` pointing at the plugin JAR.

**Cause.** The UI server JVM keeps `ZipFile` handles open against `gridgain-demo-gradle-plugin/build/libs/*.jar` and `gridgain-demo-ui/build/libs/*.jar`. The composite-build setup ([settings.gradle.kts](settings.gradle.kts)) rebuilds those JARs as a side effect of **any** other `./gradlew` invocation against this project or its included builds. When the JAR file is rewritten under the open handle, the JVM's cached central-directory offsets stop matching the new file layout, and the next entry read throws this error. The JAR on disk is still valid — only the running JVM's view of it is stale.

**Fix.**

```bash
# in the terminal running launchPluginUi: Ctrl+C
./gradlew --stop          # release any lingering daemon's open file handles
./gradlew launchPluginUi  # fresh start
```

No `./gradlew clean` is needed; the on-disk JAR is fine.

**Avoidance.** Do not run other `./gradlew <task>` commands (in this project, or in any sibling pulled in via `includeBuild`) while `launchPluginUi` is running. If you do need to rebuild a sibling during a working session, restart the UI afterward.

## Implementation roadmap

The work captured in [CLAUDE.md](CLAUDE.md) lands as roughly five streams:

1. **Slim [demo-config.yaml](src/main/resources/demo-config.yaml)** to the single-GKE / single-GG8 / Postgres + MariaDB shape ([CLAUDE.md §11](CLAUDE.md)).
2. **Add new plugin element types** `databases` and `cdc_connectors` in `gridgain-demo-gradle-plugin` ([CLAUDE.md §8](CLAUDE.md)).
3. **Build the Ktor backend** at `ui-backend/` — three data planes plus WebSocket tailer channels ([CLAUDE.md §5](CLAUDE.md)).
4. **Build the React UI** at `ui/` — three audience panels, connector tailers, phase controls ([CLAUDE.md §3](CLAUDE.md), [§5](CLAUDE.md)).
5. **Author the payments-shaped `data.yaml` / `ops.yaml`** for the data generator ([CLAUDE.md §10](CLAUDE.md)).

End-to-end verification for the v1 demo is enumerated in [CLAUDE.md §18](CLAUDE.md).

## Documentation

- [CLAUDE.md](CLAUDE.md) — full demo specification (architecture, demo flow, UI, data model, verification).
- [Workspace CLAUDE.md](../CLAUDE.md) — workspace-wide conventions and architecture rules.
- [gridgain-demo-data-generator/CLAUDE.md](../gridgain-demo-data-generator/CLAUDE.md) — generator capabilities consumed by this demo.

## Support

Questions and feedback: **#proj-gridgain-gradle-plugin** on Slack — the existing toolkit channel; no separate channel for this demo.
