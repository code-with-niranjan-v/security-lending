import { useId, useState } from 'react'
import { Link } from 'react-router-dom'
import { date, display, loanId, money, num } from '../../utils/loanFormat'
import { LoanBadge } from './LoanCards'
import './LoanWorkspace.css'

const views = [
  ['overview', 'Overview'],
  ['collateral', 'Collateral'],
  ['returns', 'Returns & recalls'],
  ['activity', 'Activity'],
]
const percent = (value) => value == null ? '—' : `${num(value)}%`
const rate = (value) => value == null ? '—' : `${num(value)} bps`

function Terms({ loan }) {
  const mainTerms = [
    ['Borrower', loan.borrower],
    ['Security', loan.symbol],
    ['MSLA', loan.mslaId ? <Link to={`/agreements/${loan.mslaId}`}>{loan.agreementNumber}</Link> : null],
    ['Reservation', loan.reservationId],
    ['Original quantity', num(loan.quantity)],
    ['Maturity (IST)', date(loan.maturityAt ?? loan.maturity)],
  ]
  const settlementTerms = [
    ['Returned quantity', num(loan.returnedQuantity)],
    ['Cached price', money(loan.price, loan.currency)],
    ['Cash rebate', rate(loan.rebateBps)],
    ['Day count', loan.dayBasis && `Actual/${loan.dayBasis}`],
    ['Start date (IST)', date(loan.settledAt)],
  ]
  return <article className="loan-workspace-card">
    <h2>Contract terms</h2>
    <dl className="loan-workspace-terms">
      {mainTerms.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>)}
    </dl>
    <details className="loan-workspace-more">
      <summary>Accrual and settlement terms</summary>
      <dl className="loan-workspace-terms">
        {settlementTerms.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>)}
      </dl>
    </details>
  </article>
}

function Collateral({ loan, positions, pending }) {
  return <article className="loan-workspace-card">
    <h2>{pending ? 'Proposed collateral' : 'Collateral positions'}</h2>
    <dl className="loan-workspace-coverage">
      <div><dt>Eligible value</dt><dd>{money(loan.eligibleCollateral, loan.currency)}</dd></div>
      <div><dt>Required value</dt><dd>{money(loan.requiredCollateral, loan.currency)}</dd></div>
      <div><dt>Coverage</dt><dd>{percent(loan.coverage)}</dd></div>
      <div><dt>Shortfall</dt><dd>{money(loan.shortfall, loan.currency)}</dd></div>
    </dl>
    {positions.length > 0
      ? <div className="loan-workspace-table">
        <table>
          <thead><tr><th>Collateral</th><th>Amount or quantity</th><th>Status</th></tr></thead>
          <tbody>{positions.map((position) => <tr key={position.id}>
            <td><strong>{position.assetType === 'CASH' ? 'Cash collateral' : 'Securities collateral'}</strong><small>Lot {position.id}</small></td>
            <td>{position.assetType === 'CASH' ? money(position.cashAmount, loan.currency) : `${num(position.quantity)} shares`}</td>
            <td>{display(position.status)}</td>
          </tr>)}</tbody>
        </table>
      </div>
      : <p className="loan-workspace-empty">No collateral positions.</p>}
    {loan.valuationAt && <p className="loan-workspace-empty">Last valuation (IST) {date(loan.valuationAt)}</p>}
  </article>
}

function Activity({ events, recent }) {
  return <article className="loan-workspace-card">
    <h2>{recent ? 'Recent activity' : 'Activity & settlement timeline'}</h2>
    {events.length
      ? <ol className="loan-workspace-timeline">{events.map((event, index) => (
        <li key={`${event.at}:${event.event}:${index}`}>
          <div><strong>{event.event.includes('_') ? display(event.event) : event.event}</strong>
            {event.reference != null && <small>Reference {event.reference}</small>}
          </div>
          <time dateTime={event.at}>{date(event.at)} IST</time>
        </li>
      ))}</ol>
      : <p className="loan-workspace-empty">No activity recorded.</p>}
  </article>
}

export default function LoanWorkspace({ data, children, onOpen, embedded = false }) {
  const [view, setView] = useState('overview')
  const tabId = useId()
  const { loan, collateral, returns, recalls, activity } = data
  const pending = loan.status === 'PENDING_REVIEW'
  const canIssueMargin = loan.status === 'ACTIVE' && !loan.openMarginCount && !loan.pendingMarginRequest
  const metrics = [
    ['quantity', pending ? 'Requested quantity' : 'Outstanding quantity', `${num(pending ? loan.quantity : loan.outstandingQuantity)} shares`],
    ['value', 'Loan market value', money(loan.marketValue, loan.currency)],
    ['coverage', 'Collateral coverage', percent(loan.coverage)],
    ['fee', 'Annual lending fee', rate(loan.feeBps)],
  ]
  function navigateTabs(event, index) {
    let next
    if (event.key === 'ArrowRight') next = (index + 1) % views.length
    else if (event.key === 'ArrowLeft') next = (index + views.length - 1) % views.length
    else if (event.key === 'Home') next = 0
    else if (event.key === 'End') next = views.length - 1
    else return
    event.preventDefault()
    setView(views[next][0])
    event.currentTarget.parentElement.children[next].focus()
  }
  return <section className="loan-workspace-view">
    {!embedded && <nav className="loan-workspace-crumb" aria-label="Breadcrumb">
      <Link to="/loans">Loans</Link><span aria-hidden="true">/</span><span aria-current="page">{loanId(loan.id)}</span>
    </nav>}
    <header className="loan-workspace-heading">
      <div>
        <div className="loan-workspace-identity">
          <h1>{loanId(loan.id)}</h1><LoanBadge>{display(loan.status)}</LoanBadge>
        </div>
        <p>{[loan.symbol, loan.borrower].filter(Boolean).join(' · ')}</p>
      </div>
      <div className="loan-workspace-actions">
        <Link className="loan-workspace-primary" to={`/loans/${loanId(loan.id)}/manage`}>Manage loan</Link>
        <button type="button" disabled={!canIssueMargin} onClick={() => onOpen('margin')}>Issue margin call</button>
        <button type="button" disabled={!loan.canRecall} onClick={() => onOpen('recall')}>Create recall</button>
        {children}
      </div>
    </header>
    <dl className="loan-workspace-metrics">
      {metrics.map(([key, label, value]) => <div key={key} className={`loan-workspace-metric metric-${key}`}><dt>{label}</dt><dd>{value}</dd></div>)}
    </dl>
    <div className="loan-workspace-tabs" role="tablist" aria-label="Loan details">
      {views.map(([key, title], index) => <button key={key} type="button" role="tab"
        id={`${tabId}-${key}`} aria-selected={view === key} aria-controls={`${tabId}-panel`}
        tabIndex={view === key ? 0 : -1} onClick={() => setView(key)}
        onKeyDown={(event) => navigateTabs(event, index)}>{title}</button>)}
    </div>
    <div id={`${tabId}-panel`} role="tabpanel" tabIndex={0} aria-labelledby={`${tabId}-${view}`}>
      {view === 'overview' && <>
        <div className="loan-workspace-grid"><Terms loan={loan} /><Collateral loan={loan} positions={collateral} pending={pending} /></div>
        <Activity events={activity.slice(0, 3)} recent />
      </>}
      {view === 'collateral' && <Collateral loan={loan} positions={collateral} pending={pending} />}
      {view === 'returns' && <article className="loan-workspace-card">
        <h2>Returns & recalls</h2>
        {returns.length + recalls.length > 0 ? <div className="loan-workspace-table"><table>
          <thead><tr><th>Type / ID</th><th>Quantity</th><th>Status</th></tr></thead>
          <tbody>
            {returns.map((entry) => <tr key={`r${entry.id}`}><td>Return {entry.id}</td><td>{num(entry.quantity)}</td><td>{display(entry.status)}</td></tr>)}
            {recalls.map((entry) => <tr key={`c${entry.id}`}><td>Recall {entry.id}</td><td>{num(entry.quantity - entry.fulfilledQuantity)}</td><td>{display(entry.status)}</td></tr>)}
          </tbody>
        </table></div> : <p className="loan-workspace-empty">No recorded returns or recalls.</p>}
        <p className="loan-workspace-empty"><Link to={`/loans/${loanId(loan.id)}/manage`}>Receive securities / manage requests</Link></p>
      </article>}
      {view === 'activity' && <Activity events={activity} />}
    </div>
  </section>
}
