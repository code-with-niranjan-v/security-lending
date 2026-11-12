import { useState } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { useDataSource } from '../hooks/useDataSource'
import { activeLoans } from '../data/activeLoanData'
import LiveLoansPage from './LiveLoansPage'
import { useReferenceSort } from '../hooks/useReferenceSort'
import './ReferencePages.css'
import './LoansPage.css'
import { LoanBadge, LoanCard } from '../components/loans/LoanCards'
import { num, money, downloadLoans } from '../utils/loanFormat'
export default function LoansPage() {
  const { mode } = useDataSource()
  return mode === 'live' ? <LiveLoansPage /> : <DemoLoans />
}
function DemoLoans() {
  const { searchTerm = '' } = useOutletContext(),
    [tab, setTab] = useState('Active'),
    [draft, setDraft] = useState({
      borrower: '',
      q: '',
      msla: '',
      coverage: '',
      maturity: '',
    }),
    [filters, setFilters] = useState(draft),
    [selected, setSelected] = useState(activeLoans[0].id)
  const filtered = activeLoans.filter(
    (l) =>
      !['Closed', 'Pending settlement'].includes(tab) &&
      (!filters.borrower || l.borrower === filters.borrower) &&
      (!filters.msla || l.msla === filters.msla) &&
      (!filters.coverage || filters.coverage === 'Sufficient') &&
      (!filters.maturity || l.maturity === filters.maturity) &&
      `${l.symbol} ${l.id} ${l.borrower}`
        .toLowerCase()
        .includes(`${filters.q}`.toLowerCase()) &&
      Object.values(l)
        .join(' ')
        .toLowerCase()
        .includes(searchTerm.toLowerCase()),
  )
  const { sorted: sortedRows, sortHeading } = useReferenceSort(filtered),
    loan = filtered.find((l) => l.id === selected)
  function exportRows() {
    const keys = [
      'id',
      'borrower',
      'symbol',
      'outstanding',
      'value',
      'fee',
      'collateral',
      'coverage',
      'maturity',
      'margin',
      'recall',
      'status',
    ]
    downloadLoans(sortedRows, keys)
  }
  return (
    <section className="active-loans ref-page">
      <header className="al-heading">
        <div>
          <h1>
            Active Loans <span className="ref-demo">DEMO DATA</span>
          </h1>
          <p>
            Monitor on-loan positions, collateral coverage and return
            obligations
          </p>
        </div>
        <div>
          <button onClick={exportRows}>♧ Export</button>
          <Link className="ref-primary" to="/reservations">
            New loan
          </Link>
        </div>
      </header>
      <div className="al-metrics">
        {[
          ['Active loans', '128'],
          ['On-loan value', '$84.60M'],
          ['Eligible collateral', '$89.44M'],
          ['Open margin calls', '4'],
          ['Open recalls', '6'],
        ].map(([k, v]) => (
          <span key={k}>
            {k}
            <b>{v}</b>
          </span>
        ))}
      </div>
      <nav className="al-tabs">
        {['All loans', 'Active', 'Pending settlement', 'Closed'].map((t) => (
          <button
            key={t}
            className={tab === t ? 'active' : ''}
            onClick={() => setTab(t)}
          >
            {t}
          </button>
        ))}
      </nav>
      <form
        className="al-filters"
        onSubmit={(e) => {
          e.preventDefault()
          setFilters(draft)
        }}
      >
        <label>
          Borrower
          <select
            value={draft.borrower}
            onChange={(e) => setDraft({ ...draft, borrower: e.target.value })}
          >
            <option value="">All borrowers</option>
            {[...new Set(activeLoans.map((l) => l.borrower))].map((v) => (
              <option key={v}>{v}</option>
            ))}
          </select>
        </label>
        <input
          aria-label="Search loan security"
          placeholder="Search security (e.g., NVDA, AAPL, ISIN, CUSIP)…"
          value={draft.q}
          onChange={(e) => setDraft({ ...draft, q: e.target.value })}
        />
        <label>
          MSLA
          <select
            value={draft.msla}
            onChange={(e) => setDraft({ ...draft, msla: e.target.value })}
          >
            <option value="">All MSLAs</option>
            <option>MSLA-00124</option>
          </select>
        </label>
        <label>
          Coverage
          <select
            value={draft.coverage}
            onChange={(e) => setDraft({ ...draft, coverage: e.target.value })}
          >
            <option value="">All coverage</option>
            <option>Sufficient</option>
            <option>Shortfall</option>
          </select>
        </label>
        <label>
          Maturity date
          <select
            value={draft.maturity}
            onChange={(e) => setDraft({ ...draft, maturity: e.target.value })}
          >
            <option value="">Any date</option>
            {activeLoans.map((l) => (
              <option key={l.id}>{l.maturity}</option>
            ))}
          </select>
        </label>
        <button
          type="button"
          onClick={() => {
            const f = {
              borrower: '',
              q: '',
              msla: '',
              coverage: '',
              maturity: '',
            }
            setDraft(f)
            setFilters(f)
          }}
        >
          Reset
        </button>
        <button className="ref-primary">Apply</button>
      </form>
      <div className="al-table-scroll">
        <table className="al-table">
          <thead>
            <tr>
              {[
                ['id', 'Loan ID'],
                ['borrower', 'Borrower'],
                ['symbol', 'Security'],
                ['outstanding', 'Outstanding qty'],
                ['value', 'Market value'],
                ['fee', 'Fee (bps p.a.)'],
                ['collateral', 'Eligible collateral'],
                ['coverage', 'Coverage'],
                ['maturity', 'Maturity'],
                ['margin', 'Margin'],
                ['recall', 'Recall'],
                ['status', 'Status'],
              ].map(([key, label]) => sortHeading(key, label))}
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {sortedRows.map((l) => (
              <tr key={l.id} className={selected === l.id ? 'selected' : ''}>
                <td>
                  <button
                    onClick={() => setSelected(selected === l.id ? null : l.id)}
                  >
                    {l.id}
                  </button>
                </td>
                <td>{l.borrower}</td>
                <td>{l.symbol}</td>
                <td>{num(l.outstanding)}</td>
                <td>{money(l.value)}</td>
                <td>{l.fee}</td>
                <td>{money(l.collateral)}</td>
                <td>{l.coverage.toFixed(2)}%</td>
                <td>{l.maturity}</td>
                <td>
                  {l.margin === 'Clear' ? (
                    l.margin
                  ) : (
                    <LoanBadge>{l.margin}</LoanBadge>
                  )}
                </td>
                <td>
                  {l.recall === 'None' ? (
                    l.recall
                  ) : (
                    <LoanBadge>{l.recall}</LoanBadge>
                  )}
                </td>
                <td>
                  <LoanBadge>{l.status}</LoanBadge>
                </td>
                <td>
                  <Link to={`/loans/${l.id}/manage`}>Manage</Link>
                </td>
              </tr>
            ))}
            {!sortedRows.length && (
              <tr>
                <td colSpan={13}>No matching loans.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {loan && <DemoLoanDetail loan={loan} />}
    </section>
  )
}
function DemoLoanDetail({ loan: l }) {
  return (
    <section className="al-detail">
      <header>
        <h2>
          ⌄ {l.id} / {l.symbol} <LoanBadge>{l.status}</LoanBadge>
        </h2>
        <div>
          <Link className="ref-primary" to={`/loans/${l.id}/manage`}>
            Manage loan
          </Link>
          <Link to={`/margin-calls/${l.id}/issue`}>Issue margin call</Link>
          <Link to={`/recalls/${l.id}/new`}>Create recall</Link>
          <Link to={`/agreements/${l.msla}`}>View MSLA</Link>
        </div>
      </header>
      <div className="al-detail-grid">
        <LoanCard
          title="Loan terms"
          rows={[
            ['MSLA', l.msla],
            ['Reservation', l.reservation],
            ['Original qty', num(l.quantity)],
            ['Returned qty', num(l.returned)],
            ['Outstanding qty', num(l.outstanding)],
            ['Price', money(l.price)],
            ['Fee', `${l.fee} bps (${l.fee / 100}% p.a.)`],
            ['Rebate', `${l.rebate} bps`],
            ['Day count', 'Actual/360'],
            ['Start date', l.start],
            ['Maturity date', l.maturity],
          ]}
        />
        <LoanCard
          title="Collateral coverage"
          rows={[
            ['Collateral lot', 'Amount'],
            ['Cash', money(l.id === 'LOAN-000006' ? 6000000 : l.collateral)],
            [
              'US Treasury (eligible)',
              l.id === 'LOAN-000006' ? money(4752000) : '—',
            ],
            ['Total eligible collateral', money(l.collateral)],
            ['Required (105%)', money(l.collateral)],
            ['Excess', money(0)],
            ['Coverage', '105.00%'],
            ['Coverage status', <LoanBadge>Sufficient</LoanBadge>],
          ]}
        />
        <article className="al-card">
          <h3>Returns & recalls</h3>
          <table>
            <thead>
              <tr>
                <th>Type</th>
                <th>ID</th>
                <th>Qty</th>
                <th>Status</th>
                <th>Due date</th>
              </tr>
            </thead>
            <tbody>
              {l.id === 'LOAN-000006' && (
                <tr>
                  <td>Return</td>
                  <td>
                    <Link to="/returns/RET-001782/manage">RET-001782</Link>
                  </td>
                  <td>20,000</td>
                  <td>
                    <LoanBadge>Pending review</LoanBadge>
                  </td>
                  <td>13 Nov 2026</td>
                </tr>
              )}
            </tbody>
          </table>
          <dl>
            <div>
              <dt>Already deducted from outstanding</dt>
              <dd>No</dd>
            </div>
            <div>
              <dt>Open recall</dt>
              <dd>{l.recall}</dd>
            </div>
          </dl>
          <Link
            className="al-button"
            to={
              l.id === 'LOAN-000006' ? '/returns/RET-001782/manage' : '/returns'
            }
          >
            ♧ Receive securities
          </Link>
          <p>
            ⓘ Release collateral after confirmed receipt of returned securities.
          </p>
        </article>
        <article className="al-card">
          <h3>Activity & settlement timeline</h3>
          <table>
            <thead>
              <tr>
                <th>Time (UTC)</th>
                <th>Event</th>
                <th>Reference</th>
              </tr>
            </thead>
            <tbody>
              {[
                ['10 Nov 2026 14:02:11', 'Loan confirmed', l.id],
                ['10 Nov 2026 14:03:26', 'Collateral received', 'COL-009781'],
                [
                  '12 Nov 2026 13:41:05',
                  'Partial return settled (20,000)',
                  'RET-001782',
                ],
                ['12 Nov 2026 14:10:37', 'Valuation updated', 'VAL-004563'],
              ].map((r) => (
                <tr key={r[1]}>
                  {r.map((v, i) => (
                    <td key={i}>{v}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </article>
      </div>
    </section>
  )
}
