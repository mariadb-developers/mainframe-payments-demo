#!/usr/bin/env bash
# Creates the k8s Secrets that the database and cdc_connector deploys reference
# via secretKeyRef. Must run AFTER deployInfrastructure (which configures the
# kubectl context) and BEFORE deployDatabase / deployCdcConnector.
#
# The secrets are not managed by the plugin — they intentionally live outside
# demo-config.yaml so production credentials never sit in source control.
# This script's values are placeholders suitable only for the local smoke
# deploy; rotate before any real customer demo.
set -euo pipefail

# Each kubectl create namespace + create secret is idempotent under
# --dry-run=client | kubectl apply -f -. Re-running this script after a
# partial deploy is safe.

apply() {
  echo "$1" | kubectl apply -f -
}

# --- Postgres mainframe-proxy ---------------------------------------------------
kubectl create namespace mainframe-proxy --dry-run=client -o yaml | kubectl apply -f -
apply "$(kubectl create secret generic postgres-mainframe-proxy-auth \
  -n mainframe-proxy \
  --from-literal=username=payments \
  --from-literal=password=payments-pw-replace-me \
  --dry-run=client -o yaml)"

# --- MariaDB analytics ----------------------------------------------------------
kubectl create namespace mariadb-analytics --dry-run=client -o yaml | kubectl apply -f -
apply "$(kubectl create secret generic mariadb-analytics-auth \
  -n mariadb-analytics \
  --from-literal=username=payments \
  --from-literal=password=payments-pw-replace-me \
  --from-literal=root_password=root-pw-replace-me \
  --dry-run=client -o yaml)"

# --- CDC pipeline (Debezium replication user) -----------------------------------
kubectl create namespace cdc-pipeline --dry-run=client -o yaml | kubectl apply -f -
# Username/password here must match the role created by the Postgres init script
# (10-replication-user.sql creates the `debezium` role with that password).
apply "$(kubectl create secret generic mainframe-to-gg-debezium-auth \
  -n cdc-pipeline \
  --from-literal=username=debezium \
  --from-literal=password=debezium-password-replace-me \
  --dry-run=client -o yaml)"

# --- Outbound GG write-through JDBC sinks (gg-to-postgres / gg-to-mariadb) ----------
# The two debezium-connector-jdbc sinks run inside the Connect pod (cdc-pipeline) and
# connect to the Postgres mainframe-proxy / MariaDB analytics DBs. The connectors[]
# entries reference these via secret_ref, so the secrets must live in cdc-pipeline
# (not the DB namespaces). Credentials match the DB auth secrets above.
apply "$(kubectl create secret generic gg-to-postgres-jdbc-auth \
  -n cdc-pipeline \
  --from-literal=username=payments \
  --from-literal=password=payments-pw-replace-me \
  --dry-run=client -o yaml)"
apply "$(kubectl create secret generic gg-to-mariadb-jdbc-auth \
  -n cdc-pipeline \
  --from-literal=username=payments \
  --from-literal=password=payments-pw-replace-me \
  --dry-run=client -o yaml)"

echo "✅ Demo secrets created (or updated) in all three namespaces."
