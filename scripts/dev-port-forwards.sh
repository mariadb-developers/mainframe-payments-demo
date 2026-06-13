#!/usr/bin/env bash
#
# Dev-only helper: bring up the kubectl port-forwards the demo-ui backend needs
# when you run it on a laptop (./gradlew :demo-ui:run). In an in-cluster
# deployment these are unnecessary — the backend reaches each service by its
# in-cluster DNS name, so this whole concern is local-dev only.
#
# Idempotent: a forward whose local port is already listening is left alone, so
# re-running only fills the gaps (e.g. adding the :8083 Kafka Connect forward to
# an already-running set).
#
# Usage:
#   scripts/dev-port-forwards.sh            # start any that aren't up (default)
#   scripts/dev-port-forwards.sh status     # show up/down for each
#   scripts/dev-port-forwards.sh stop       # stop the forwards listed here
#
# Service/namespace names track demo-config.yaml. The :8083 Kafka Connect forward
# is REQUIRED by the phase-2 "bring GridGain online" beat (CLAUDE.md §2) — the
# demo backend pauses/resumes the cdc-sink connector via the Connect REST API.
set -euo pipefail

LOG_DIR="${TMPDIR:-/tmp}/mainframe-payments-port-forward"
mkdir -p "$LOG_DIR"

# namespace | target | localPort:remotePort | description
FORWARDS=(
  "mainframe-proxy|svc/postgres-mainframe-proxy|5432:5432|Postgres (mainframe proxy)"
  "mariadb-analytics|svc/mariadb-analytics|3306:3306|MariaDB (analytics)"
  "cdc-pipeline|svc/mainframe-to-gg-kafka|9092:9092|Kafka broker"
  "cdc-pipeline|svc/mainframe-to-gg-connect|8083:8083|Kafka Connect REST (phase-2 beat)"
  "mainframe-payments-gg8|pod/mainframe-payments-gg8-0|10800:10800|GridGain node-0 thin client"
  "mainframe-payments-gg8|pod/mainframe-payments-gg8-1|10801:10800|GridGain node-1 thin client"
  "mainframe-payments-gg8|svc/mainframe-payments-gg8|8080:8080|GridGain REST"
)

port_listening() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

cmd="${1:-start}"

case "$cmd" in
  status)
    echo "kube context: $(kubectl config current-context)"
    for f in "${FORWARDS[@]}"; do
      IFS='|' read -r ns target ports desc <<<"$f"
      if port_listening "${ports%%:*}"; then state="UP  "; else state="down"; fi
      printf "  [%s] %-6s %-42s %s\n" "$state" "${ports%%:*}" "$ns/$target" "$desc"
    done
    ;;
  stop)
    # Match either argument order (kubectl -n NS port-forward TARGET PORTS, or
    # kubectl port-forward TARGET -n NS PORTS) by target + ports.
    for f in "${FORWARDS[@]}"; do
      IFS='|' read -r ns target ports desc <<<"$f"
      if pkill -f "kubectl.*port-forward.*${target}.*${ports}" 2>/dev/null; then
        echo "  stopped  ${ports%%:*}  ${ns}/${target}"
      fi
    done
    echo "done."
    ;;
  start)
    echo "kube context: $(kubectl config current-context)"
    for f in "${FORWARDS[@]}"; do
      IFS='|' read -r ns target ports desc <<<"$f"
      local_port="${ports%%:*}"
      if port_listening "$local_port"; then
        echo "  skip   ${local_port}  already listening — ${desc}"
        continue
      fi
      log="$LOG_DIR/${ns}_${local_port}.log"
      nohup kubectl -n "$ns" port-forward "$target" "$ports" >"$log" 2>&1 &
      echo "  start  ${local_port}  ${ns}/${target}  (pid $!, log $log) — ${desc}"
    done
    echo
    echo "Re-run with 'status' to confirm, or 'stop' to tear down."
    ;;
  *)
    echo "usage: $0 [start|status|stop]" >&2
    exit 2
    ;;
esac
