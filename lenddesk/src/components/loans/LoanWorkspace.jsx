import { Link } from 'react-router-dom'
import { date, display, loanId, money, num } from '../../utils/loanFormat'
import { LoanBadge } from './LoanCards'
import { pendingLoanRequests } from '../../utils/loanRequests'
import './LoanWorkspace.css'

const percent = (value) => value == null ? '—' : `${num(value)}%`
const rate = (value) => value == null ? '—' : `${num(value)} bps`

function Terms({ loan }) {
  const mainTerms = [
    ['Borrower', loan.borrower],
    ['Security', loan.symbol],
    ['MSLA', loan.mslaId ? <Link to={`/agreements/${loan.mslaId}`}>{loan.agreementNumber}</Link> : null],
    ['Reservation', loan.reservationId],
    ['Original quantity', num(loan.quantity)],
    ['Outstanding quantity', num(loan.outstandingQuantity)],
    ['Settlement reference', loan.settlementReference],
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
    <h2>Loan details</h2>
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
    <h2>{pending ? 'Proposed collateral' : 'Collateral lots'} ({positions.length})</h2>
    {positions.length > 0
      ? <div className="loan-workspace-table">
        <table>
          <thead><tr><th>Collateral</th><th>Amount or quantity</th><th>Haircut</th><th>Status</th></tr></thead>
          <tbody>{positions.map((position) => <tr key={position.id}>
            <td><strong>{position.assetType === 'CASH' ? 'Cash collateral' : 'Securities collateral'}</strong><small>Lot {position.id}</small></td>
            <td>{position.assetType === 'CASH' ? money(position.cashAmount, loan.currency) : `${num(position.quantity)} shares`}</td>
            <td>{percent(position.haircut == null ? null : Number(position.haircut) * 100)}</td>
            <td>{display(position.status)}</td>
          </tr>)}</tbody>
        </table>
      </div>
      : <p className="loan-workspace-empty">No collateral positions.</p>}
    {loan.valuationAt && <p className="loan-workspace-empty">Last valuation (IST) {date(loan.valuationAt)}</p>}
  </article>
}

function Coverage({ loan }) {
  const eligible = loan.eligibleCollateral
  const required = loan.requiredCollateral
  const valued = eligible != null && required != null && Number(required) > 0
  const surplus = valued ? Number(eligible) - Number(required) : null
  return <article className="loan-workspace-card">
    <h2>Collateral coverage</h2>
    <dl className="loan-workspace-coverage">
      <div><dt>Eligible collateral</dt><dd>{money(eligible, loan.currency)}</dd></div>
      <div><dt>Required collateral</dt><dd>{money(required, loan.currency)}</dd></div>
      <div><dt>Coverage</dt><dd>{percent(loan.coverage)}</dd></div>
    </dl>
    {valued && <div className="loan-workspace-meter" data-shortfall={surplus < 0}>
      <progress aria-label="Eligible collateral as a share of required collateral" max={Number(required)} value={Math.min(Number(eligible), Number(required))} />
      <small>{surplus >= 0 ? 'Surplus' : 'Shortfall'} {money(Math.abs(surplus), loan.currency)} · Required {percent(loan.requiredRatio)}</small>
    </div>}
  </article>
}

function Obligations({ loan, data, managePath }) {
  const requests = pendingLoanRequests(data)
  const recalls = data.recalls.filter(recall => ['OPEN', 'PARTIALLY_FULFILLED', 'OVERDUE', 'PENDING'].includes(recall.status))
  const maturity = loan.maturityAt ?? loan.maturity
  return <article className="loan-workspace-card">
    <h2>Pending obligations</h2>
    <dl className="loan-workspace-terms">
      <div><dt>Maturity (IST)</dt><dd>{date(maturity)}</dd></div>
      <div><dt>Open recalls</dt><dd>{num(loan.openRecallCount ?? recalls.length)}</dd></div>
      <div><dt>Pending return receipts</dt><dd>{requests.filter(item => item.group === 'returns').length}</dd></div>
      <div><dt>Pending collateral requests</dt><dd>{requests.filter(item => item.group === 'collateral').length}</dd></div>
    </dl>
    {recalls.slice(0, 3).map(recall => <p className="loan-workspace-empty" key={recall.id}>
      Recall {recall.id} · {num(recall.quantity - (recall.fulfilledQuantity ?? 0))} shares · Due (IST) {date(recall.dueAt)}
    </p>)}
    {(data.returns.length > 0 || data.recalls.length > 0) && <details className="loan-workspace-more">
      <summary>Return and recall records</summary>
      <div className="loan-workspace-table"><table>
        <thead><tr><th>Record</th><th>Quantity</th><th>Status</th></tr></thead>
        <tbody>
          {data.returns.map(entry => <tr key={`return-${entry.id}`}><td>Return {entry.id}</td><td>{num(entry.quantity)}</td><td>{display(entry.status)}</td></tr>)}
          {data.recalls.map(entry => <tr key={`recall-${entry.id}`}><td>Recall {entry.id}</td><td>{num(entry.quantity)}</td><td>{display(entry.status)}</td></tr>)}
        </tbody>
      </table></div>
    </details>}
    <p className="loan-workspace-empty"><Link className="loan-workspace-requests" to={`${managePath}?view=requests`}>Manage requests →</Link></p>
  </article>
}

function Activity({ events }) {
  return <article className="loan-workspace-card">
    <h2>Activity &amp; settlements</h2>
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
  const { loan, collateral, activity } = data
  const managePath = `/loans/${loanId(loan.id)}/manage`
  const pending = loan.status === 'PENDING_REVIEW'
  const canIssueMargin = loan.status === 'ACTIVE' && !loan.openMarginCount && !loan.pendingMarginRequest
  const metrics = [
    ['quantity', pending ? 'Requested quantity' : 'Outstanding quantity', `${num(pending ? loan.quantity : loan.outstandingQuantity)} shares`],
    ['value', 'Loan market value', money(loan.marketValue, loan.currency)],
    ['eligible', 'Eligible collateral', money(loan.eligibleCollateral, loan.currency)],
    ['coverage', 'Collateral coverage', percent(loan.coverage)],
    ['fee', 'Annual lending fee', rate(loan.feeBps)],
  ]
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
        <Link className="loan-workspace-primary" to={managePath}>Manage loan</Link>
        <button type="button" disabled={!canIssueMargin} onClick={() => onOpen('margin')}>Issue margin call</button>
        <button type="button" disabled={!loan.canRecall} onClick={() => onOpen('recall')}>Create recall</button>
        {children}
      </div>
    </header>
    <dl className="loan-workspace-metrics">
      {metrics.map(([key, label, value]) => <div key={key} className={`loan-workspace-metric metric-${key}`}><dt>{label}</dt><dd>{value}</dd></div>)}
    </dl>
    <div className="loan-workspace-grid">
      <div className="loan-workspace-stack">
        <Coverage loan={loan} />
        <Collateral loan={loan} positions={collateral} pending={pending} />
        <Activity events={activity} />
      </div>
      <aside className="loan-workspace-stack">
        <Terms loan={loan} />
        <Obligations loan={loan} data={data} managePath={managePath} />
      </aside>
    </div>
  </section>
}
