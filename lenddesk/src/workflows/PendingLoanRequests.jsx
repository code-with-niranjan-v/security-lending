import { useState } from 'react'
import { LoanBadge } from '../components/loans/LoanCards'
import { money, num } from '../utils/loanFormat'

const filters = [['all', 'All'], ['returns', 'Returns'], ['collateral', 'Collateral']]
const shares = (quantity) => quantity == null ? '—' : `${num(quantity)} shares`

export default function PendingLoanRequests({ loan, requests, activity, action, reason, onAction, onReason, onSubmit, error, busy }) {
  const [filter, setFilter] = useState('all')
  const [selectedId, setSelectedId] = useState(null)
  const visible = requests.filter(item => filter === 'all' || item.group === filter)
  const selected = visible.find(item => item.id === selectedId) ?? visible[0]
  const rejection = selected && action === `${selected.reject}:${selected.row.id}`
  const isReturn = selected?.group === 'returns'
  function amount(item) {
    const row = item.row
    const release = item.confirm === 'collateralRelease'
    return row.assetType === 'CASH'
      ? money(release ? row.requestedReleaseCash ?? row.cashAmount : row.cashAmount, loan.currency)
      : shares(release ? row.requestedReleaseQuantity ?? row.quantity : row.quantity)
  }

  return <div className="manage-loan-grid manage-loan-request-layout">
    <div className="manage-loan-aside">
      <article className="manage-loan-card">
        <h2>Pending requests</h2>
        <div className="manage-request-filters" role="group" aria-label="Filter pending requests">
          {filters.map(([key, label]) => <button key={key} type="button" disabled={busy}
            aria-pressed={filter === key} onClick={() => {
              setFilter(key)
              setSelectedId(null)
              onAction('')
            }}>{label} ({key === 'all' ? requests.length : requests.filter(item => item.group === key).length})</button>)}
        </div>
        <div className="manage-loan-table-scroll">
          <table className="wf-table">
            <thead><tr><th>Request</th><th>Type</th><th>Quantity / amount</th><th>Status</th></tr></thead>
            <tbody>
              {visible.map(item => <tr key={item.id} className={selected?.id === item.id ? 'manage-request-selected' : undefined}>
                <td><button className="wf-text-button" type="button" disabled={busy} aria-pressed={selected?.id === item.id}
                  onClick={() => { setSelectedId(item.id); onAction('') }}>{item.id}</button></td>
                <td>{item.type}</td><td>{amount(item)}</td><td><LoanBadge>Pending</LoanBadge></td>
              </tr>)}
              {!visible.length && <tr><td colSpan={4}>No pending requests for this filter.</td></tr>}
            </tbody>
          </table>
        </div>
      </article>
      {activity}
    </div>
    <article className="manage-loan-card manage-request-review">
      <h2>{selected ? `Review ${selected.type.toLowerCase()}` : 'Request review'}</h2>
      {!selected ? <p>No request selected.</p> : <>
        <dl className="manage-loan-facts">
          {[
            ['Reference', selected.id],
            isReturn ? ['Security', loan.symbol] : ['Collateral asset', selected.row.assetType === 'CASH' ? 'Cash' : 'Securities'],
            ['Borrower', loan.borrower],
            [selected.row.assetType === 'CASH' ? 'Amount' : 'Quantity', amount(selected)],
            ...(isReturn ? [
              ['Current outstanding', shares(loan.outstandingQuantity)],
              ['Outstanding after settlement',
                loan.outstandingQuantity == null || selected.row.quantity == null ? '—' :
                  shares(Math.max(0, Number(loan.outstandingQuantity) - Number(selected.row.quantity)))],
            ] : []),
          ].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>)}
        </dl>
        <p className="manage-request-note">{selected.note}</p>
        <form className="manage-loan-review" onSubmit={event => {
          event.preventDefault()
          if (!rejection || reason.trim())
            onSubmit(`${rejection ? selected.reject : selected.confirm}:${selected.row.id}`)
        }}>
          {rejection && <label>Reason<textarea required maxLength={500} disabled={busy} value={reason}
            onChange={event => onReason(event.target.value)} /></label>}
          {error && <p role="alert" className="al-error">{error}</p>}
          <footer>
            <button type="button" disabled={busy} onClick={() => onAction(rejection ? '' : `${selected.reject}:${selected.row.id}`)}>
              {rejection ? 'Cancel rejection' : selected.rejectLabel}
            </button>
            <button type="submit" className="wf-primary" disabled={busy || (rejection && !reason.trim())}>
              {busy ? 'Submitting…' : rejection ? `Confirm ${selected.rejectLabel.toLowerCase().replace('reject ', '')} rejection` : selected.confirmLabel}
            </button>
          </footer>
        </form>
      </>}
    </article>
  </div>
}
