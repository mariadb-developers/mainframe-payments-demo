import type {
  AccountBalance,
  AnalyticQueryDefinition,
  AnalyticQueryResult,
  BulkLoadResult,
  CdcFeedStateResponse,
  CustomerSummary,
  CuratedTransaction,
  GeneratorRate,
  GeneratorState,
  PhaseState,
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
}

export const phaseApi = {
  current: () => getJson<PhaseState>('/api/phase'),
  advance: (phase: number) => postJson<PhaseState>('/api/phase', { phase }),
}

export const generatorApi = {
  state: () => getJson<GeneratorState>('/api/generator'),
  setRate: (rate: GeneratorRate) => postJson<GeneratorState>('/api/generator/rate', { rate }),
}

export const demoApi = {
  reset: () => postJson<ResetSummary>('/api/demo/reset', {}),
}

// Phase-2 "bring GridGain online" beat (CLAUDE.md §2): pause/resume the cdc-sink
// and bulk-load the mainframe snapshot directly into GG.
export const cdcApi = {
  state: () => getJson<CdcFeedStateResponse>('/api/cdc/state'),
  pause: () => postJson<CdcFeedStateResponse>('/api/cdc/pause', {}),
  resume: () => postJson<CdcFeedStateResponse>('/api/cdc/resume', {}),
  bulkLoad: () => postJson<BulkLoadResult>('/api/cdc/bulk-load', {}),
}
