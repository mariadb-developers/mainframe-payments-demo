# Design — Closing the secrets gap in the assembly

*Status: design open, no implementation yet. 2026-06-30.*

## Goal

`./gradlew deployAssembly -PassemblyName=mainframe-payments` should be a one-command
deploy. Today it isn't: the operator must interrupt the walk between
`deployInfrastructure` (step 1) and the database deploys (step 6) to run
`scripts/create-demo-secrets.sh`, otherwise the Postgres / MariaDB / Debezium pods sit
in `CreateContainerConfigError` with `Error: secret "<name>-auth" not found` until the
walker hits its 5-min StatefulSet readiness timeout and fails.

The pre-req is documented in the script's header comment and in
[secrets-prereq-before-databases](../../.claude/projects/-Users-davidbrown-Code-DemoGradleProject-mainframe-payments-demo/memory/secrets-prereq-before-databases.md),
but it costs an operator-side hand-off every single time, and it cost us two failed
smoke-test runs on 2026-06-29 alone. The toolkit's assembly walker should know how to
satisfy this dependency on its own.

## Why the secrets aren't in `demo-config.yaml` today

Deliberate. `scripts/create-demo-secrets.sh`'s header:

> The secrets are not managed by the plugin — they intentionally live outside
> demo-config.yaml so production credentials never sit in source control.

The `databases` / `cdc_connectors` element manifests reference Kubernetes Secrets via
`auth_secret_ref`. The manifests are produced by the toolkit; the Secrets themselves
are not. That separation should survive whatever fix we apply — the source of secret
*values* must remain outside `demo-config.yaml`, but the *intent to create them* can
move into the assembly.

## Three options

### Option A — `shell_step` element kind in the assembly walker

Smallest fix. The walker gains one new element type:

```yaml
assemblies:
  mainframe-payments:
    elements:
      - kind: infrastructure
        name: mainframe-payments-gke
      - kind: shell_step
        name: bootstrap-secrets
        run: scripts/create-demo-secrets.sh
        when: after_kubectl_context
      - kind: database
        name: postgres-mainframe-proxy
      …
```

The walker shells out to the named script at the configured point in the walk. The
script still lives in the demo repo; only the *invocation* moves into the assembly.

**Pros.**
- Smallest scope. Six lines in the assembly DTO + a `ShellStepDispatcher` branch.
- Secret values still live outside `demo-config.yaml`.
- Works for any other "between deploys, run this thing" need (e.g. license-file
  upload, a one-shot SQL bootstrap).

**Cons.**
- Untyped escape hatch — any script can run, with whatever it touches. Surface area
  to keep clean grows with usage.
- The script's working directory, env, and timeout become walker contract.
- Doesn't model secrets themselves; the next demo that wants different secrets
  re-implements the script.

### Option B — `secrets` element kind, modelled and materialized

A first-class element type alongside `databases` / `monitors` / `proxies`:

```yaml
secrets:
  postgres-mainframe-proxy-auth:
    namespace: mainframe-proxy
    data:
      username:
        source: literal
        value: payments
      password:
        source: env
        env_var: PAYMENTS_PG_PASSWORD
        fallback: payments-pw-replace-me   # dev only
      # Future: source: gcp_secret_manager, secret: projects/x/secrets/y
```

The walker has a `deploySecret` action that materializes the Secret into the right
namespace, with the value resolved via a pluggable source. The assembly lists
`{kind: secrets, name: postgres-mainframe-proxy-auth}` before the elements that
reference it.

**Pros.**
- Models the real concept. The `databases.<n>.auth_secret_ref` and
  `cdc_connectors.<n>.replicationUserSecretRef` fields already exist — they cross-ref
  this new map naturally.
- Sources are typed: `literal` (dev only), `env` (twelve-factor), `gcp_secret_manager`
  / `aws_secrets_manager` (prod-shaped). The toolkit can validate availability at
  config-load time.
- Tests + drift detection get the same treatment as every other element (the
  `config_hash` story works for free — though we should be careful not to hash secret
  *values*, only the resolved source + reference shape).

**Cons.**
- Biggest lift. New schema, new validator, new action/deployer, new docs, migration.
- Operators have to learn yet another element type for what feels (to them) like an
  operational hand-off.
- Risk of accidentally putting secret literals into source control via `source:
  literal` — gentle linting needed in code review.

### Option C — Auto-pre-hook on `databases` / `cdc_connectors`

The action stays the same shape; the *element type* takes responsibility for
ensuring its own auth secret exists before applying its manifests.

Each `databases` / `cdc_connectors` entry declares a tiny `auth_secret` block:

```yaml
databases:
  postgres-mainframe-proxy:
    auth_secret_ref: postgres-mainframe-proxy-auth
    auth_secret:
      username_env: PAYMENTS_PG_USER
      password_env: PAYMENTS_PG_PASSWORD
      defaults:
        username: payments
        password: payments-pw-replace-me   # dev only
```

`DeployDatabaseAction` checks that the referenced Secret exists in the target
namespace; if not, it materializes one from the `auth_secret` block. If the field is
absent, behavior is unchanged (operator is on the hook, like today).

**Pros.**
- Less invasive than Option B — no new element type, no new walker branch.
- Stays adjacent to the field that needs it; no separate cross-ref to mis-wire.
- Backward-compatible: existing configs that don't set `auth_secret` behave as
  today.

**Cons.**
- Couples secret management to the elements that consume them, instead of
  modelling it cleanly. Multiple elements sharing a secret means duplicate
  declarations.
- The pattern doesn't extend to the *Connect-side* sinks
  (`gg-to-postgres-jdbc-auth`, `gg-to-mariadb-jdbc-auth`,
  `mainframe-to-gg-debezium-auth`) — those live in the `cdc-pipeline` namespace and
  belong to no single element. The current bootstrap script handles them; a per-
  element auto-hook can't.
- Each element type that needs a secret has to learn the materialization protocol;
  every new element type asks "do I need this too?"

## Recommendation

Lean toward **Option A (`shell_step`)** as the immediate fix, with **Option B
(`secrets` element)** as the right end state. The argument:

- A `shell_step` ships in a day, closes the one-command-deploy gap, and lets the
  existing `create-demo-secrets.sh` keep doing exactly what it does — including the
  three CDC-pipeline secrets that don't belong to any single element (Option C's
  failure mode).
- The next demo that wants secrets gets the same `shell_step` for ~free.
- When secret management actually becomes prod-shaped (Vault / Secret Manager / KMS),
  the `secrets` element type is the right home — but designing it now without that
  use case is speculative.

**Don't go with C** unless we accept the limitation that connector / cdc-pipeline
secrets stay on the operator's plate.

## Open questions

- Should `shell_step` constrain its `run:` path to scripts inside the demo repo (no
  arbitrary host-shell access)? Probably yes.
- Working directory and env semantics for `shell_step`: project root,
  inherit-current-shell-env? Document in the toolkit skill.
- For Option B's `source: env`, what's the precedence between env, fallback, and a
  literal? Need an explicit ladder.
- Drift detection on `secrets` should *never* hash the value — only the source/key
  shape. If the value changes (rotation), the toolkit should not flag config drift.
  This is a design constraint, not a TODO.

## Out of scope

- Production secret rotation, KMS integration, audit logging. These would belong in
  Option B's `gcp_secret_manager` / equivalent source, but are not required for the
  demo-shaped flow.
- Replacing `scripts/create-demo-secrets.sh` with something fancier in the demo repo
  — once Option A or B lands, the script either disappears or gets called by the
  walker. Either way: deferred until the toolkit side ships.
