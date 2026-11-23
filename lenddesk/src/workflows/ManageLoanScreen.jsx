import { Link, useSearchParams } from 'react-router-dom'
import { LoanBadge } from '../components/loans/LoanCards'
import { date, display, loanId, money, num } from '../utils/loanFormat'
import { pendingLoanRequests } from '../utils/loanRequests'
import PendingLoanRequests from './PendingLoanRequests'
import './ManageLoanScreen.css'

const percent = (value) => value == null ? '—' : `${num(value)}%`
const rate = (value) => value == null ? '—' : `${num(value)} bps`
const shares = (value) => value == null ? '—' : `${num(value)} shares`

export default function ManageLoanScreen({ data, action, reason, onAction, onReason, onSubmit, notice, error, busy }) {
  const { loan, collateral, activity } = data
  const [params, setParams] = useSearchParams()
  const showRequests = params.get('view') === 'requests'
  const requests = pendingLoanRequests(data)
  function chooseView(view) {
    onAction('')
    setParams(previous => {
      const next = new URLSearchParams(previous)
      if (view === 'requests') next.set('view', view)
      else next.delete('view')
      return next
    })
  }
  const workspace = `/loans/${loanId(loan.id)}`
  const pending = loan.status === 'PENDING_REVIEW'
  const canSettle = loan.status === 'APPROVED_PENDING_SETTLEMENT'
  const mainOperations = [
    { key: 'approve', label: 'Approve loan', enabled: pending, review: 'Review approval', detail: 'Approval records the lending decision. Settlement follows separately.', confirm: 'Confirm approval' },
    { key: 'reject', label: 'Reject loan', enabled: pending, review: 'Review rejection', detail: 'The reason will be sent to the borrower.', confirm: 'Confirm rejection', needsReason: true },
    { key: 'settle', label: 'Confirm settlement', enabled: canSettle, review: 'Review settlement', detail: 'Confirm only after verifying delivery of the securities to the borrower.', confirm: 'Confirm settlement' },
  ]
  const selected = mainOperations.find(operation => operation.key === action && operation.enabled)
  const facts = [
    ['Borrower', loan.borrower], ['Security', loan.symbol],
    ['Agreement', loan.mslaId ? <Link to={`/agreements/${loan.mslaId}`}>{loan.agreementNumber}</Link> : null],
    ['Reservation', loan.reservationId], ['Fee', rate(loan.feeBps)], ['Rebate', rate(loan.rebateBps)],
    ['Day count', loan.dayBasis ? `Actual/${loan.dayBasis}` : null], ['Billing', display(loan.billing)],
  ]
  const metrics = [
    ['Requested quantity', shares(loan.quantity)], ['Outstanding quantity', shares(loan.outstandingQuantity)],
    ['Loan market value', money(loan.marketValue, loan.currency)], ['Collateral coverage', percent(loan.coverage)],
    ['Annual lending fee', rate(loan.feeBps)],
  ]
  const activityTable = (
    <article className="manage-loan-card">
      <h2>{showRequests ? 'Recent activity' : 'Activity & settlement'}</h2>
      <div className="manage-loan-table-scroll"><table className="wf-table">
        <thead><tr><th>Time (IST)</th><th>Event</th><th>Reference</th></tr></thead>
        <tbody>{(showRequests ? activity.slice(0, 5) : activity).map((entry, index) => <tr key={`${entry.at}:${index}`}>
          <td>{date(entry.at)}</td><td>{entry.event.includes('_') ? display(entry.event) : entry.event}</td><td>{entry.reference ?? '—'}</td>
        </tr>)}
        {!activity.length && <tr><td colSpan={3}>No activity recorded.</td></tr>}</tbody>
      </table></div>
    </article>
  )
  return (
    <section className="manage-loan wf-page">
      <nav className="wf-breadcrumb" aria-label="Breadcrumb">
        <Link to="/loans">Loans</Link><span>/</span><Link to={workspace}>{loanId(loan.id)}</Link><span>/</span><span aria-current="page">Manage loan</span>
      </nav>
      <header className="manage-loan-header">
        <div>
          <div className="manage-loan-title"><h1>Manage loan</h1><LoanBadge>{display(loan.status)}</LoanBadge></div>
          <p>{[loanId(loan.id), loan.symbol, loan.borrower].filter(Boolean).join(' · ')}</p>
        </div>
        <Link to={workspace}>← Back to loan workspace</Link>
      </header>
      {notice && <p className="wf-notice" role="status">{notice}</p>}
      <dl className="manage-loan-metrics">
        {metrics.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd>
          {label === 'Collateral coverage' && loan.requiredRatio != null && <small>Required {percent(loan.requiredRatio)}</small>}
        </div>)}
      </dl>
      <nav className="manage-loan-tabs" aria-label="Manage loan sections">
        <button type="button" aria-pressed={!showRequests} disabled={busy} onClick={() => chooseView('operations')}>Loan operations</button>
        <button type="button" aria-pressed={showRequests} disabled={busy} onClick={() => chooseView('requests')}>Pending requests ({requests.length})</button>
      </nav>
      {showRequests ? (
        <PendingLoanRequests loan={loan} requests={requests} activity={activityTable} action={action} reason={reason}
          onAction={onAction} onReason={onReason} onSubmit={onSubmit} error={error} busy={busy} />
      ) : <>
      <div className="manage-loan-grid">
        <article className="manage-loan-card">
          <h2>Loan operations</h2>
          <div className="manage-loan-operations">
            {mainOperations.map((operation) => <button key={operation.key} type="button" aria-pressed={action === operation.key}
              disabled={busy || !operation.enabled} onClick={() => onAction(operation.key)}>
              <span>{operation.label}{operation.key === 'settle' && pending && <small>Available after approval</small>}</span>
              <span aria-hidden="true">›</span>
            </button>)}
          </div>
          {selected && <form className="manage-loan-section manage-loan-review" onSubmit={(event) => { event.preventDefault(); onSubmit() }}>
            <h3>{selected.review}</h3>
            <p>{selected.detail}</p>
            {selected.needsReason && <label>Reason<textarea required maxLength={500} value={reason} disabled={busy} onChange={(event) => onReason(event.target.value)} /></label>}
            {error && <p role="alert" className="al-error">{error}</p>}
            <footer>
              <button type="button" disabled={busy} onClick={() => onAction('')}>Cancel</button>
              <button className="wf-primary" type="submit" disabled={busy || (selected.needsReason && !reason.trim())}>{busy ? 'Submitting…' : selected.confirm}</button>
            </footer>
          </form>}
        </article>
        <aside className="manage-loan-aside">
          <article className="manage-loan-card"><h2>Loan summary</h2>
            <dl className="manage-loan-facts">{facts.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>)}</dl>
          </article>
          <article className="manage-loan-card"><h2>{pending ? 'Proposed collateral' : 'Collateral'}</h2>
            <div className="manage-loan-table-scroll"><table className="wf-table">
              <thead><tr><th>Lot</th><th>Asset</th><th>Amount / quantity</th><th>Haircut</th><th>Status</th></tr></thead>
              <tbody>{collateral.length ? collateral.map((lot) => <tr key={lot.id}>
                <td>{lot.id}</td><td>{lot.assetType === 'CASH' ? 'Cash' : 'Securities'}</td>
                <td>{lot.assetType === 'CASH' ? money(lot.cashAmount, loan.currency) : shares(lot.quantity)}</td>
                <td>{percent(lot.haircut == null ? null : Number(lot.haircut) * 100)}</td><td>{display(lot.status)}</td>
              </tr>) : <tr><td colSpan={5}>No collateral recorded.</td></tr>}</tbody>
            </table></div>
            <p className="manage-loan-total">Eligible total <strong>{money(loan.eligibleCollateral, loan.currency)}</strong></p>
          </article>
        </aside>
      </div>
      {activityTable}
      </>}
    </section>
  )
}
