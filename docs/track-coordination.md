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
