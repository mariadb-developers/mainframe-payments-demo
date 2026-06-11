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

export type GeneratorRate = 'OFF' | 'SLOW' | 'MEDIUM' | 'FAST'

export interface GeneratorState {
  rate: GeneratorRate
  run_id: string | null
}

export interface ResetStep {
  name: string
  result: string
}

export interface ResetSummary {
  steps: ResetStep[]
}
