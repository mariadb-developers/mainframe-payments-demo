import { useEffect, useState } from 'react'
import { gridGainApi } from '@/api/client'
import type { AccountBalance, CustomerSummary, ProductSummary, TransactionResult } from '@/types/api'

interface Props {
  onExecuted: (result: TransactionResult) => void
  reloadKey: number
}

/** Down-chevron for the app-style selects (native arrow is hidden via appearance-none). */
function Chevron() {
  return (
    <svg
      className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400"
      viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.75"
    >
      <path d="M6 8l4 4 4-4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

export function GridGainPanel({ onExecuted, reloadKey }: Props) {
  const [customers, setCustomers] = useState<CustomerSummary[]>([])
  const [products, setProducts] = useState<ProductSummary[]>([])
  const [balances, setBalances] = useState<AccountBalance[]>([])
  const [customerId, setCustomerId] = useState('')
  const [productId, setProductId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reachable, setReachable] = useState(true)

  useEffect(() => {
    // Connection status is separate from the data fetch: the data endpoints return
    // [] for both "unreachable" and "connected but empty", so we ask explicitly to
    // tell those two apart in the empty-state message below.
    gridGainApi
      .status()
      .then((s) => setReachable(s.connected))
      .catch(() => setReachable(false))
    Promise.all([gridGainApi.customers(), gridGainApi.products(), gridGainApi.balances()])
      .then(([c, p, b]) => {
        setCustomers(c)
        setProducts(p)
        setBalances(b)
        if (c.length > 0) setCustomerId((cur) => cur || c[0].customer_id)
        if (p.length > 0) setProductId((cur) => cur || p[0].product_id)
        setError(null)
      })
      .catch((e) => setError(String(e.message ?? e)))
  }, [reloadKey])

  const selectedAccount = balances.find((b) => b.customer_id === customerId)
  const selectedProduct = products.find((p) => p.product_id === productId)

  const execute = async () => {
    if (!selectedAccount || !productId) return
    setBusy(true)
    setError(null)
    try {
      const result = await gridGainApi.purchase(customerId, selectedAccount.account_id, productId)
      onExecuted(result)
      const fresh = await gridGainApi.balances()
      setBalances(fresh)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const empty = customers.length === 0 && products.length === 0 && balances.length === 0

  const selectClass =
    'w-full appearance-none rounded-2xl bg-slate-50 border border-slate-200 pl-3.5 pr-9 py-2.5 ' +
    'text-sm text-slate-800 transition focus:outline-none focus:border-sky-400 focus:ring-4 focus:ring-sky-100'

  return (
    <div className="relative h-full flex flex-col overflow-hidden rounded-3xl bg-gradient-to-b from-white to-sky-50/60 p-6 shadow-2xl shadow-black/40 ring-1 ring-black/5 animate-fade-in">
      {/* soft atmospheric glow */}
      <div className="pointer-events-none absolute -top-20 -right-16 h-52 w-52 rounded-full bg-sky-200/50 blur-3xl" />

      {/* header */}
      <div className="relative flex items-center justify-between mb-5">
        <div className="flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-2xl bg-gradient-to-br from-sky-500 to-blue-600 text-white font-display text-lg font-bold shadow-lg shadow-sky-500/30">
            G
          </span>
          <div>
            <div className="font-display text-xl font-semibold leading-none text-slate-800">GridGain</div>
            <div className="mt-1 text-[11px] uppercase tracking-[0.18em] text-slate-400">In-memory grid</div>
          </div>
        </div>
        <span className="rounded-full bg-sky-100/80 px-3 py-1 text-[11px] font-medium text-sky-700">
          Cache-backed payments
        </span>
      </div>

      {error && (
        <div className="relative mb-3 rounded-2xl bg-amber-50 px-3.5 py-2 text-sm text-amber-700 ring-1 ring-amber-200">
          {error}
        </div>
      )}
      {empty && !error && (
        <div className="relative mb-3 rounded-2xl bg-slate-50 px-3.5 py-2 text-sm text-slate-500 ring-1 ring-slate-200">
          {reachable ? (
            'Connected to GridGain — no cached data yet.'
          ) : (
            <>
              GridGain not reachable from this host. Use{' '}
              <code className="rounded bg-slate-200/70 px-1 font-mono text-slate-600">kubectl port-forward</code> to
              expose the GG service, then refresh.
            </>
          )}
        </div>
      )}

      <div className="relative grid flex-1 grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)] gap-5 min-h-0">
        {/* ── smartphone-app checkout ── */}
        <div className="flex flex-col rounded-[1.75rem] bg-white p-4 shadow-lg shadow-slate-300/40 ring-1 ring-slate-200/80">
          <div className="mb-3 text-[11px] uppercase tracking-[0.18em] text-slate-400">New purchase</div>

          <label className="mb-1 text-xs font-medium text-slate-500">Customer</label>
          <div className="relative mb-3">
            <select value={customerId} onChange={(e) => setCustomerId(e.target.value)} className={selectClass}>
              {customers.map((c) => (
                <option key={c.customer_id} value={c.customer_id}>
                  {c.name} · #{c.customer_id}
                </option>
              ))}
            </select>
            <Chevron />
          </div>

          <label className="mb-1 text-xs font-medium text-slate-500">Product</label>
          <div className="relative mb-4">
            <select value={productId} onChange={(e) => setProductId(e.target.value)} className={selectClass}>
              {products.map((p) => (
                <option key={p.product_id} value={p.product_id}>
                  {p.name}
                </option>
              ))}
            </select>
            <Chevron />
          </div>

          {/* total */}
          <div className="mb-4 flex items-baseline justify-between rounded-2xl bg-sky-50/70 px-4 py-3 ring-1 ring-sky-100">
            <span className="text-xs font-medium uppercase tracking-wider text-slate-400">Total</span>
            <span className="font-display text-2xl font-semibold tabular-nums text-slate-800">
              ${selectedProduct ? selectedProduct.price : '—'}
            </span>
          </div>

          <button
            onClick={execute}
            disabled={busy || !selectedAccount || !productId}
            className="mt-auto w-full rounded-2xl bg-gradient-to-r from-sky-500 to-blue-600 px-4 py-3 font-display font-semibold text-white shadow-lg shadow-sky-500/30 transition active:scale-[0.98] hover:shadow-xl hover:shadow-sky-500/40 disabled:cursor-not-allowed disabled:opacity-40 disabled:shadow-none"
          >
            {busy ? 'Posting…' : `Purchase${selectedProduct ? ` · $${selectedProduct.price}` : ''}`}
          </button>
        </div>

        {/* ── balances ── */}
        <div className="flex min-h-0 flex-col">
          <div className="mb-3 text-[11px] uppercase tracking-[0.18em] text-slate-400">Balances · GG cache</div>
          <div className="flex-1 space-y-1.5 overflow-auto pr-1">
            {balances.length === 0 && (
              <div className="rounded-2xl bg-slate-50 px-3.5 py-3 text-sm text-slate-400 ring-1 ring-slate-200">
                no balances loaded
              </div>
            )}
            {balances.map((b) => {
              const active = b.customer_id === customerId
              return (
                <div
                  key={b.account_id}
                  className={[
                    'flex items-center justify-between rounded-2xl px-3.5 py-2.5 transition',
                    active ? 'bg-sky-50 ring-1 ring-sky-200' : 'bg-slate-50/70 hover:bg-slate-100',
                  ].join(' ')}
                >
                  <div className="flex items-center gap-2.5">
                    <span className="grid h-8 w-8 place-items-center rounded-full bg-white text-xs font-semibold text-slate-500 ring-1 ring-slate-200">
                      {b.customer_name?.charAt(0) ?? '?'}
                    </span>
                    <div className="leading-tight">
                      <div className="text-sm font-medium text-slate-700">{b.customer_name}</div>
                      <div className="text-[11px] text-slate-400">#{b.account_id}</div>
                    </div>
                  </div>
                  <div className="font-display text-sm font-semibold tabular-nums text-slate-800">${b.balance}</div>
                </div>
              )
            })}
          </div>
        </div>
      </div>
    </div>
  )
}
