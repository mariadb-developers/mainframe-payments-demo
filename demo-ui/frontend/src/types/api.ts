// Mirrors the backend DTOs in com.gridgain.demo.payments.ui.model.Models.kt.
// Snake_case property names match Ktor's Jackson config.

export interface CustomerSummary {
  customer_id: string
  name: string
}

export interface AccountBalance {
  account_id: string
  customer_id: string
  customer_name: string
  balance: string
}

export interface ProductSummary {
  product_id: string
  name: string
  price: string
}

export interface CuratedTransaction {
  id: string
  description: string
  customer_id: string
  account_id: string
  product_id: string | null
  amount: string
  kind: string
}

export interface TransactionResult {
  transaction_id: string
  correlation_id: string
  account_balance_after: string
}

export interface AnalyticQueryDefinition {
  id: string
  label: string
  description: string
}

export interface AnalyticQueryResult {
  query_id: string
  columns: string[]
  rows: string[][]
}

export interface PhaseState {
  phase: number
}

export interface TailerEvent {
  timestamp: string
  source: string
  operation: string
  table: string
  key: string
  correlation_id: string | null
  payload: Record<string, unknown>
}

// Manual load control (CLAUDE.md §3/§10): total target ops/sec across all pods
// (0 = off) and the pod count. Mirrors com.gridgain.demo.payments.ui.model.GeneratorState.
export interface GeneratorState {
  target_ops_per_second: number
  replicas: number
  running: boolean
}

// Live throughput + GridGain execution latency from the data generator, streamed over
// /api/metrics ~1s. Mirrors com.gridgain.demo.payments.ui.metrics.MetricsSnapshot (snake_case).
export interface MetricsSnapshot {
  updated_at_ms: number
  observed_tps: number
  avg_latency_ms: number
  total_ops: number
  error_count: number
  target_tps: number
  run_id: string
  active: boolean
  // Workload descriptor ("<reads>:<writes>" percentages) for the phase-6 dashboard latency
  // subtitle; null if the backend couldn't resolve the ratio at startup.
  r_w_ratio: string | null
}

export interface ResetStep {
  name: string
  result: string
}

// Phase-2 "bring GridGain online" beat (CLAUDE.md §2).
export type CdcFeedStateName = 'PAUSED' | 'LIVE' | 'UNKNOWN'

export interface CdcFeedStateResponse {
  state: CdcFeedStateName
}

export interface BulkLoadResult {
  tables_loaded: Record<string, number>
}

export interface ResetSummary {
  steps: ResetStep[]
}

// Kafka Connect health — a FAILED task means the connector is RUNNING but applying nothing
// (e.g. a sink whose DB connection went stale). Polled by the header health pill.
export interface ConnectorHealth {
  failed_tasks: { connector: string; task: number }[]
}
