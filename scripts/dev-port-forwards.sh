#!/usr/bin/env bash
#
# Dev-only helper: bring up (and keep up) the kubectl port-forwards the demo-ui
# backend needs when run on a laptop (./gradlew :demo-ui:run). In an in-cluster
# deployment these are unnecessary — the backend reaches each service by its
# in-cluster DNS name, so this whole concern is local-dev only.
#
# kubectl port-forward tunnels die on laptop sleep / idle / network blips. The
# annoying failure mode is "stale-but-listening": the local port still accepts
# connections but the tunnel behind it is dead, so calls hang or return empty
# (e.g. the cdc-sink resume failing with "header parser received no bytes", or
# the tailers silently going empty). `check`/`watch` detect that — not just a
# dead process — by also scanning each forward's kubectl log for tunnel errors.
#
# Usage:
#   scripts/dev-port-forwards.sh            # start any that aren't up
#   scripts/dev-port-forwards.sh status     # show up/down for each
#   scripts/dev-port-forwards.sh stop       # stop all forwards listed here
#   scripts/dev-port-forwards.sh restart    # stop all, then start fresh
#   scripts/dev-port-forwards.sh check      # one self-heal pass (down OR stale -> restart)
#   scripts/dev-port-forwards.sh watch      # check on a loop (leave running in a terminal)
#
# Service/namespace names track demo-config.yaml. :8083 (Kafka Connect REST) and
# :9094 (Kafka EXTERNAL listener) are REQUIRED by the phase-2 "bring GridGain
# online" beat and the connector tailers (CLAUDE.md §2/§5).
set -euo pipefail

LOG_DIR="${TMPDIR:-/tmp}/mainframe-payments-port-forward"
WATCH_INTERVAL="${WATCH_INTERVAL:-5}"
mkdir -p "$LOG_DIR"

# namespace | target | localPort:remotePort | description
FORWARDS=(
  "mainframe-proxy|svc/postgres-mainframe-proxy|5432:5432|Postgres (mainframe proxy)"
  "mariadb-analytics|svc/mariadb-analytics|3306:3306|MariaDB (analytics)"
  "cdc-pipeline|pod/mainframe-to-gg-kafka-0|9094:9094|Kafka EXTERNAL listener (tailer consumer)"
  "cdc-pipeline|svc/mainframe-to-gg-connect|8083:8083|Kafka Connect REST (phase-2 beat)"
  "mainframe-payments-gg8|pod/mainframe-payments-gg8-0|10800:10800|GridGain node-0 thin client"
  "mainframe-payments-gg8|pod/mainframe-payments-gg8-1|10801:10800|GridGain node-1 thin client"
  "mainframe-payments-gg8|svc/mainframe-payments-gg8|8080:8080|GridGain REST"
)

# kubectl prints one of these to its log when a tunnel breaks but keeps the
# local listener open — our signal for a stale forward.
ERR_RE='lost connection to pod|error forwarding port|an error occurred forwarding|error creating forwarding|failed to|connection refused'

port_listening() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
log_path()       { echo "$LOG_DIR/${1}_${2}.log"; }   # ns, localPort

stale() { # log file -> 0 if it shows a recent tunnel error
  local log="$1"
  [ -f "$log" ] && tail -n 20 "$log" 2>/dev/null | grep -Eiq "$ERR_RE"
}

start_one() {
  local ns="$1" target="$2" ports="$3" desc="$4"
  local lp="${ports%%:*}" log; log="$(log_path "$ns" "${ports%%:*}")"
  : >"$log"
  nohup kubectl -n "$ns" port-forward "$target" "$ports" >"$log" 2>&1 &
  echo "  start  ${lp}  ${ns}/${target}  (pid $!) — ${desc}"
}

stop_one() { # match either kubectl arg order, by target + ports
  local target="$1" ports="$2"
  pkill -f "kubectl.*port-forward.*${target}.*${ports}" 2>/dev/null || true
}

cmd="${1:-start}"

case "$cmd" in
  status)
    echo "kube context: $(kubectl config current-context)"
    for f in "${FORWARDS[@]}"; do
      IFS='|' read -r ns target ports desc <<<"$f"
      lp="${ports%%:*}"
      if ! port_listening "$lp"; then state="down "
      elif stale "$(log_path "$ns" "$lp")"; then state="STALE"
      else state="UP   "; fi
      printf "  [%s] %-6s %-42s %s\n" "$state" "$lp" "$ns/$target" "$desc"
    done
    ;;
  stop)
    for f in "${FORWARDS[@]}"; do
      IFS='|' read -r ns target ports desc <<<"$f"
      stop_one "$target" "$ports" && echo "  stopped  ${ports%%:*}  ${ns}/${target}" || true
    done
    echo "done."
    ;;
  start)
    echo "kube context: $(kubectl config current-context)"
    for f in "${FORWARDS[@]}"; do
      IFS='|' read -r ns target ports desc <<<"$f"
      if port_listening "${ports%%:*}"; then
        echo "  skip   ${ports%%:*}  already listening — ${desc}"
      else
        start_one "$ns" "$target" "$ports" "$desc"
      fi
    done
    echo; echo "Re-run with 'status' to confirm, 'watch' to self-heal, or 'stop' to tear down."
    ;;
  restart)
    "$0" stop || true
    "$0" start
    ;;
  check)
    # One self-heal pass: (re)start anything that's down or stale.
    for f in "${FORWARDS[@]}"; do
      IFS='|' read -r ns target ports desc <<<"$f"
      lp="${ports%%:*}"; log="$(log_path "$ns" "$lp")"
      if ! port_listening "$lp"; then
        echo "  down  -> starting ${lp} (${desc})"; start_one "$ns" "$target" "$ports" "$desc"
      elif stale "$log"; then
        echo "  stale -> restarting ${lp} (${desc})"; stop_one "$target" "$ports"; start_one "$ns" "$target" "$ports" "$desc"
      fi
    done
    ;;
  watch)
    echo "watching ${#FORWARDS[@]} forwards every ${WATCH_INTERVAL}s (Ctrl-C to stop)"
    "$0" start
    while true; do
      sleep "$WATCH_INTERVAL"
      "$0" check
    done
    ;;
  *)
    echo "usage: $0 [start|status|stop|restart|check|watch]" >&2
    exit 2
    ;;
esac
