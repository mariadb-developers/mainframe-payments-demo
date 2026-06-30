import type {
  AccountBalance,
  AnalyticQueryDefinition,
  AnalyticQueryResult,
  BulkLoadResult,
  CdcFeedStateResponse,
  ConnectorHealth,
  CustomerSummary,
  CuratedTransaction,
  GeneratorState,
  PhaseState,
  PoolStatus,
  ProductSummary,
  ResetSummary,
  TransactionResult,
} from '@/types/api'

async function getJson<T>(path: string): Promise<T> {
  const r = await fetch(path)
  if (!r.ok) throw new Error(`${r.status} ${r.statusText} for GET ${path}`)
  return r.json()
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const r = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!r.ok) {
    const text = await r.text().catch(() => '')
    throw new Error(`${r.status} ${r.statusText} for POST ${path}: ${text}`)
  }
  return r.json()
}

export const mainframeApi = {
  curated: () => getJson<CuratedTransaction[]>('/api/mainframe/transactions'),
  balances: () => getJson<AccountBalance[]>('/api/mainframe/balances'),
  execute: (curatedTransactionId: string) =>
    postJson<TransactionResult>('/api/mainframe/execute', { curated_transaction_id: curatedTransactionId }),
}

export const gridGainApi = {
  customers: () => getJson<CustomerSummary[]>('/api/gridgain/customers'),
  products: () => getJson<ProductSummary[]>('/api/gridgain/products'),
  balances: () => getJson<AccountBalance[]>('/api/gridgain/balances'),
  status: () => getJson<{ connected: boolean }>('/api/gridgain/status'),
  purchase: (customerId: string, accountId: string, productId: string) =>
    postJson<TransactionResult>('/api/gridgain/execute', {
      customer_id: customerId,
      account_id: accountId,
      product_id: productId,
    }),
}

export const mariaDbApi = {
  queries: () => getJson<AnalyticQueryDefinition[]>('/api/mariadb/queries'),
  run: (id: string) =>
    fetch(`/api/mariadb/queries/${encodeURIComponent(id)}/run`, { method: 'POST' }).then((r) => {
      if (!r.ok) throw new Error(`${r.status} ${r.statusText}`)
      return r.json() as Promise<AnalyticQueryResult>
    }),
  // Phase-5 "bring MariaDB online" beat (CLAUDE.md §2): pause/resume the GG→MariaDB
  // sink and bulk-load a GG snapshot directly into MariaDB. (Pause/resume is gated
  // on the toolkit deploying that sink; bulk-load works now.)
  feedState: () => getJson<CdcFeedStateResponse>('/api/mariadb-feed/state'),
  pause: () => postJson<CdcFeedStateResponse>('/api/mariadb-feed/pause', {}),
  resume: () => postJson<CdcFeedStateResponse>('/api/mariadb-feed/resume', {}),
  // Phase-5 beat split into two steps: dump captures + holds the GG snapshot, load applies it.
  bulkDump: () => postJson<BulkLoadResult>('/api/mariadb-feed/bulk-dump', {}),
  bulkLoad: () => postJson<BulkLoadResult>('/api/mariadb-feed/bulk-load', {}),
}

export const phaseApi = {
  current: () => getJson<PhaseState>('/api/phase'),
  advance: (phase: number) => postJson<PhaseState>('/api/phase', { phase }),
}

export const generatorApi = {
  state: () => getJson<GeneratorState>('/api/generator'),
  // Pod count (0 = off). Each pod runs at its ~500 ops/sec latency ceiling, so total ≈ pods × 500;
  // adding pods is the lever for saturating GG. The backend (re)launches the distributed run,
  // stopping any prior run first.
  setPods: (pods: number) => postJson<GeneratorState>('/api/generator/pods', { pods }),
  // Generator GKE pool warmup. poolStatus() is the poll for the UI's status pill;
  // warmupPool() POSTs an async gcloud resize so the call returns immediately and
  // subsequent poolStatus() calls reflect the climbing node count.
  poolStatus: () => getJson<PoolStatus>('/api/generator/pool/status'),
  warmupPool: () => postJson<PoolStatus>('/api/generator/pool/warmup', {}),
}

export const demoApi = {
  reset: () => postJson<ResetSummary>('/api/demo/reset', {}),
}

export const connectorsApi = {
  health: () => getJson<ConnectorHealth>('/api/connectors/health'),
}

// Phase-2 "bring GridGain online" beat (CLAUDE.md §2): pause/resume the cdc-sink
// and bulk-load the mainframe snapshot directly into GG.
export const cdcApi = {
  state: () => getJson<CdcFeedStateResponse>('/api/cdc/state'),
  pause: () => postJson<CdcFeedStateResponse>('/api/cdc/pause', {}),
  resume: () => postJson<CdcFeedStateResponse>('/api/cdc/resume', {}),
  // Phase-2 beat split into two steps: dump captures + holds the mainframe snapshot, load applies it.
  bulkDump: () => postJson<BulkLoadResult>('/api/cdc/bulk-dump', {}),
  bulkLoad: () => postJson<BulkLoadResult>('/api/cdc/bulk-load', {}),
}
