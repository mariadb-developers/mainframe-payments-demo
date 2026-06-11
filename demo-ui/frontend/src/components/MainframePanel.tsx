import { useEffect, useRef, useState } from 'react'
import { mainframeApi } from '@/api/client'
import type { AccountBalance, CuratedTransaction, TransactionResult } from '@/types/api'

interface Props {
  onExecuted: (result: TransactionResult) => void
  reloadKey: number
  /** Whether the panel is visible/active — gates the global ↑/↓/Enter handler. */
  enabled: boolean
}

export function MainframePanel({ onExecuted, reloadKey, enabled }: Props) {
  const [transactions, setTransactions] = useState<CuratedTransaction[]>([])
  const [balances, setBalances] = useState<AccountBalance[]>([])
  const [selected, setSelected] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)
  const selectedRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    // The curated menu comes from the shipped catalog (no database) and must
    // always render so the presenter can navigate it. Balances come from
    // Postgres, which may be transiently unavailable — fetch the two
    // independently so a balances failure can't blank the menu.
    mainframeApi
      .curated()
      .then((tx) => {
        setTransactions(tx)
        setError(null)
      })
      .catch((e) => setError(String(e.message ?? e)))
    mainframeApi
      .balances()
      .then((bal) => setBalances(bal))
      .catch(() => undefined) // keep last-known balances; panel shows "unavailable" until present
  }, [reloadKey])

  // Keep the selection in range if the curated list changes size.
  useEffect(() => {
    setSelected((s) => Math.min(s, Math.max(0, transactions.length - 1)))
  }, [transactions.length])

  const execute = async () => {
    if (inFlight.current) return
    const tx = transactions[selected]
    if (!tx) return
    inFlight.current = true
    setBusy(true)
    setError(null)
    try {
      const result = await mainframeApi.execute(tx.id)
      onExecuted(result)
      const fresh = await mainframeApi.balances()
      setBalances(fresh)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
      inFlight.current = false
    }
  }

  // Latest execute closure, so the keydown listener always posts the current selection.
  const executeRef = useRef(execute)
  executeRef.current = execute

  // ↑/↓ move the selection, Enter posts — global while the panel is visible, but
  // ignored when focus is in a form control so the GridGain dropdowns' native
  // arrow behavior is untouched. Every other panel stays mouse-driven.
  useEffect(() => {
    if (!enabled) return
    const handler = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null
      if (
        el &&
        (el.tagName === 'INPUT' ||
          el.tagName === 'SELECT' ||
          el.tagName === 'TEXTAREA' ||
          el.tagName === 'BUTTON' ||
          el.isContentEditable)
      ) {
        return
      }
      if (transactions.length === 0) return
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        setSelected((i) => Math.min(i + 1, transactions.length - 1))
      } else if (e.key === 'ArrowUp') {
        e.preventDefault()
        setSelected((i) => Math.max(i - 1, 0))
      } else if (e.key === 'Enter' && !e.repeat) {
        e.preventDefault()
        void executeRef.current()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [enabled, transactions.length])

  // Keep the highlighted row scrolled into view during keyboard navigation.
  useEffect(() => {
    selectedRef.current?.scrollIntoView({ block: 'nearest' })
  }, [selected])

  return (
    <div className="bg-vt-bg text-vt-fg font-vt text-lg p-4 rounded border border-vt-dim/60 h-full flex flex-col">
      <div className="text-vt-accent text-xl mb-3 uppercase tracking-wider">▓ Mainframe ▓</div>
      {error && <div className="text-yellow-300 text-sm mb-2 font-mono">! {error}</div>}
      <div className="flex-1 grid grid-cols-[2fr_1fr] gap-4 min-h-0">
        <div className="flex flex-col min-h-0">
          <div className="text-vt-accent mb-1">Pending transactions</div>
          <div className="flex-1 overflow-auto border border-vt-dim/40 p-1">
            {transactions.length === 0 && <div className="text-vt-dim">[ no curated tx loaded ]</div>}
            {transactions.map((tx, i) => (
              <div
                key={tx.id}
                ref={i === selected ? selectedRef : null}
                onClick={() => setSelected(i)}
                className={['cursor-pointer px-1', i === selected ? 'bg-vt-fg/25 text-vt-accent' : ''].join(' ')}
              >
                {i === selected ? '▶ ' : '  '}
                {tx.description}
              </div>
            ))}
          </div>
          {/* Selection is keyboard-driven: ↑/↓ to move, Enter to post (no button). */}
          <div className="mt-3 flex items-center justify-between border-t border-vt-dim/40 pt-2 text-base uppercase tracking-widest">
            <span className="text-vt-dim">↑/↓ select</span>
            <span className={busy ? 'text-vt-accent animate-pulse' : 'text-vt-fg'}>
              {busy ? 'Posting…' : '[ Enter ] post'}
            </span>
          </div>
        </div>
        <div className="flex flex-col min-h-0">
          <div className="text-vt-accent mb-1">Account balances</div>
          <div className="flex-1 overflow-auto border border-vt-dim/40 p-1 text-base">
            {balances.length === 0 && <div className="text-vt-dim">[ balances unavailable ]</div>}
            {balances.map((b) => (
              <div key={b.account_id} className="flex justify-between">
                <span>
                  {b.customer_name} · #{b.account_id}
                </span>
                <span className="text-vt-accent">${b.balance}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
