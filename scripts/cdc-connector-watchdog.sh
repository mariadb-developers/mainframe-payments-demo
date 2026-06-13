#!/usr/bin/env bash
#
# Watchdog for the CDC Kafka Connect pipeline. Kafka Connect does NOT auto-restart
# a task that has FAILED — e.g. the Debezium source task dying on a transient
# Postgres "connection refused" stops all mainframe->GG capture until someone
# restarts it by hand. That silently empties the connector tailers and freezes
# cross-store sync. This polls Connect and restarts anything FAILED.
#
# Restarts a FAILED task in place; restarts a FAILED connector (with its failed
# tasks). A RUNNING connector with a FAILED task (the case we hit) -> task restart.
#
# Usage:
#   scripts/cdc-connector-watchdog.sh status   # show every connector + task state
#   scripts/cdc-connector-watchdog.sh once      # one heal pass
#   scripts/cdc-connector-watchdog.sh watch     # heal on a loop (leave running)
#
# Connect REST URL: $PAYMENTS_KAFKA_CONNECT_URL (default http://localhost:8083),
# so it matches the demo backend's config and scripts/dev-port-forwards.sh.
set -uo pipefail

CONNECT_URL="${PAYMENTS_KAFKA_CONNECT_URL:-http://localhost:8083}"
WATCH_INTERVAL="${WATCH_INTERVAL:-15}"

connectors() {
  curl -fsS --max-time 8 "$CONNECT_URL/connectors" 2>/dev/null \
    | python3 -c "import sys,json; [print(c) for c in json.load(sys.stdin)]" 2>/dev/null || true
}

status_json() { curl -fsS --max-time 8 "$CONNECT_URL/connectors/$1/status" 2>/dev/null; }

print_status() {
  echo "Connect: $CONNECT_URL"
  local list; list="$(connectors)"
  if [ -z "$list" ]; then echo "  (no connectors / Connect unreachable)"; return; fi
  for c in $list; do
    status_json "$c" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('  %-34s connector=%s' % ('$c', d.get('connector',{}).get('state','?')))
for t in d.get('tasks',[]): print('      task %s = %s' % (t['id'], t.get('state','?')))
" 2>/dev/null || echo "  $c: (status unavailable)"
  done
}

heal_once() {
  local list; list="$(connectors)"
  if [ -z "$list" ]; then echo "  Connect unreachable at $CONNECT_URL"; return; fi
  local any=0
  for c in $list; do
    local actions
    actions="$(status_json "$c" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception: sys.exit(0)
if d.get('connector',{}).get('state')=='FAILED': print('CONNECTOR')
for t in d.get('tasks',[]):
    if t.get('state')=='FAILED': print('TASK %s' % t['id'])
" 2>/dev/null)"
    [ -z "$actions" ] && continue
    any=1
    if printf '%s\n' "$actions" | grep -q '^CONNECTOR$'; then
      echo "  $c: connector FAILED -> restart (incl. failed tasks)"
      curl -fsS --max-time 10 -X POST "$CONNECT_URL/connectors/$c/restart?includeTasks=true&onlyFailed=true" >/dev/null 2>&1 \
        && echo "    ok" || echo "    restart request failed"
    else
      while read -r kind tid; do
        [ "$kind" = "TASK" ] || continue
        echo "  $c: task $tid FAILED -> restart"
        curl -fsS --max-time 10 -X POST "$CONNECT_URL/connectors/$c/tasks/$tid/restart" >/dev/null 2>&1 \
          && echo "    ok" || echo "    restart request failed"
      done <<<"$actions"
    fi
  done
  [ "$any" = 0 ] && echo "  all connectors/tasks healthy"
}

case "${1:-status}" in
  status) print_status ;;
  once)   heal_once ;;
  watch)
    echo "watching CDC connectors at $CONNECT_URL every ${WATCH_INTERVAL}s (Ctrl-C to stop)"
    while true; do heal_once; sleep "$WATCH_INTERVAL"; done
    ;;
  *) echo "usage: $0 [status|once|watch]" >&2; exit 2 ;;
esac
