import { useEffect, useState } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { lenderGet } from '../api/lendDeskApi'
import { useDataSource } from '../hooks/useDataSource'
import { useReferenceSort } from '../hooks/useReferenceSort'
import { LoanBadge, LoanCard } from '../components/loans/LoanCards'
import {
  num,
  money,
  downloadLoans,
  loanId,
  display,
  date,
} from '../utils/loanFormat'
const empty = { connectionId: '', mslaId: '', coverage: '', q: '' }
export default function LiveLoansPage() {
  const { searchTerm = '' } = useOutletContext(),
    { organizationId, refreshKey } = useDataSource()
  const [draft, setDraft] = useState(empty),
    [filters, setFilters] = useState(empty),
    [tab, setTab] = useState('ACTIVE'),
    [selected, setSelected] = useState(null),
    [newLoanInfo, setNewLoanInfo] = useState(false),
    [state, setState] = useState({ scope: '', data: null, error: '' })
  const query = new URLSearchParams(
      Object.entries({
        ...filters,
        status: tab,
        q: [searchTerm, filters.q].filter(Boolean).join(' '),
      }).filter(([, v]) => v !== ''),
    ),
    queryText = query.toString(),
    scope = `${organizationId}:${refreshKey}:${queryText}`
  useEffect(() => {
    const abort = new AbortController()
    async function load() {
      try {
        let page = 0,
          result,
          rows = []
        do {
          result = await lenderGet(
            `/api/v1/organizations/${organizationId}/lenddesk/loans/overview?${queryText}&page=${page++}&size=200`,
            abort.signal,
          )
          rows.push(...result.loans.content)
        } while (page < result.loans.totalPages)
        if (!abort.signal.aborted)
          setState({
            scope,
            data: {
              ...result,
              rows: [...new Map(rows.map((l) => [l.id, l])).values()],
            },
            error: '',
          })
      } catch (error) {
        if (!abort.signal.aborted)
          setState({ scope, data: null, error: error.message })
      }
    }
    const timer = setTimeout(load, 150)
    return () => {
      clearTimeout(timer)
      abort.abort()
    }
  }, [organizationId, refreshKey, scope, queryText])
  const data = state.scope === scope ? state.data : null,
    { sorted, sortHeading } = useReferenceSort(data?.rows ?? [])
  const opts =
    data ?? (state.scope.startsWith(`${organizationId}:`) ? state.data : null)
  return (
    <section className="active-loans ref-page">
      <header className="al-heading">
        <div>
          <h1>
            Active Loans <span className="ref-demo">LIVE API</span>
          </h1>
          <p>
            Monitor on-loan positions, collateral coverage and return
            obligations
          </p>
        </div>
        <div>
          <button
            disabled={!data}
            onClick={() =>
              downloadLoans(sorted, [
                'id',
                'borrower',
                'symbol',
                'currency',
                'outstandingQuantity',
                'marketValue',
                'feeBps',
                'eligibleCollateral',
                'coverage',
                'status',
              ])
            }
          >
            Export
          </button>
          <button
            className="ref-primary"
            onClick={() => setNewLoanInfo((v) => !v)}
          >
            New loan
          </button>
        </div>
      </header>
      {newLoanInfo && (
        <p role="status">
          New loans are requested by borrowers against an approved reservation.
          Open Pending review to approve or reject incoming loan requests, then
          Pending settlement to confirm settlement.
        </p>
      )}
      {data && (
        <div className="al-metrics">
          <span>
            Active loans <b>{data.activeLoanCount}</b>
          </span>
          {data.totals.map((t) => (
            <span key={t.currency}>
              {t.currency} on-loan / collateral{' '}
              <b>
                {money(t.marketValue, t.currency)} /{' '}
                {money(t.eligibleCollateral, t.currency)}
              </b>
              {t.missingValuations > 0 && (
                <small>{t.missingValuations} unvalued</small>
              )}
            </span>
          ))}
          <span>
            Open margin calls <b>{data.openMarginCallCount}</b>
          </span>
          <span>
            Open recalls <b>{data.openRecallCount}</b>
          </span>
        </div>
      )}
      <nav className="al-tabs">
        {[
          ['', 'All loans'],
          ['ACTIVE', 'Active'],
          ['APPROVED_PENDING_SETTLEMENT', 'Pending settlement'],
          ['PENDING_REVIEW', 'Pending review'],
          ['CLOSED', 'Closed'],
        ].map(([value, text]) => (
          <button
            key={text}
            className={tab === value ? 'active' : ''}
            onClick={() => setTab(value)}
          >
            {text}
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
            value={draft.connectionId}
            onChange={(e) =>
              setDraft({ ...draft, connectionId: e.target.value })
            }
          >
            <option value="">All borrowers</option>
            {opts?.borrowers.map((b) => (
              <option key={b.id} value={b.id}>
                {b.label}
              </option>
            ))}
          </select>
        </label>
        <input
          aria-label="Search live loan security"
          value={draft.q}
          placeholder="Security, borrower, MSLA or loan ID"
          onChange={(e) => setDraft({ ...draft, q: e.target.value })}
        />
        <label>
          MSLA
          <select
            value={draft.mslaId}
            onChange={(e) => setDraft({ ...draft, mslaId: e.target.value })}
          >
            <option value="">All MSLAs</option>
            {opts?.agreements.map((a) => (
              <option key={a.id} value={a.id}>
                {a.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          Coverage
          <select
            value={draft.coverage}
            onChange={(e) => setDraft({ ...draft, coverage: e.target.value })}
          >
            <option value="">All coverage</option>
            <option value="SUFFICIENT">Sufficient</option>
            <option value="SHORTFALL">Shortfall</option>
            <option value="UNKNOWN">Not valued</option>
          </select>
        </label>
        <button
          type="button"
          onClick={() => {
            setDraft(empty)
            setFilters(empty)
          }}
        >
          Reset
        </button>
        <button className="ref-primary">Apply</button>
      </form>
      {state.scope !== scope ? (
        <p role="status">Loading live loans…</p>
      ) : state.error ? (
        <p role="alert" className="al-error">
          Loans unavailable: {state.error}
        </p>
      ) : (
        <>
          <div className="al-table-scroll">
            <table className="al-table">
              <thead>
                <tr>
                  {[
                    ['id', 'Loan ID'],
                    ['borrower', 'Borrower'],
                    ['symbol', 'Security'],
                    ['outstandingQuantity', 'Outstanding qty'],
                    ['marketValue', 'Market value'],
                    ['feeBps', 'Fee (bps p.a.)'],
                    ['eligibleCollateral', 'Eligible collateral'],
                    ['coverage', 'Coverage'],
                    ['maturity', 'Maturity'],
                    ['openMarginCount', 'Margin'],
                    ['openRecallCount', 'Recall'],
                    ['status', 'Status'],
                  ].map(([key, text]) => sortHeading(key, text))}
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {sorted.map((l) => (
                  <tr
                    key={l.id}
                    className={selected === l.id ? 'selected' : ''}
                  >
                    <td>
                      <button
                        onClick={() =>
                          setSelected(selected === l.id ? null : l.id)
                        }
                      >
                        {loanId(l.id)}
                      </button>
                    </td>
                    <td>{l.borrower}</td>
                    <td>{l.symbol}</td>
                    <td>{num(l.outstandingQuantity)}</td>
                    <td>{money(l.marketValue, l.currency)}</td>
                    <td>{num(l.feeBps)}</td>
                    <td>{money(l.eligibleCollateral, l.currency)}</td>
                    <td>
                      {l.coverage == null
                        ? 'Not valued'
                        : `${num(l.coverage)}%`}
                    </td>
                    <td>—</td>
                    <td>
                      {l.openMarginCount ? (
                        <LoanBadge>Margin open</LoanBadge>
                      ) : l.pendingMarginRequest ? (
                        'Request pending'
                      ) : l.shortfall > 0 ? (
                        'Shortfall'
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>
                      {l.openRecallCount ? (
                        <LoanBadge>Recall open</LoanBadge>
                      ) : (
                        'None'
                      )}
                    </td>
                    <td>
                      <LoanBadge>{display(l.status)}</LoanBadge>
                    </td>
                    <td>
                      <Link to={`/loans/${loanId(l.id)}/manage`}>Manage</Link>
                    </td>
                  </tr>
                ))}
                {!sorted.length && (
                  <tr>
                    <td colSpan={13}>No matching loans.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <p className="ref-empty">
            Amounts use the latest recorded valuation, not an intraday price
            estimate. Missing valuations are excluded from totals. Maturity is
            not recorded; fee and rebate are MSLA terms.
          </p>
          {selected != null && data.rows.some((l) => l.id === selected) && (
            <LiveLoanDetails
              key={`${organizationId}:${refreshKey}:${selected}`}
              id={selected}
            />
          )}
        </>
      )}
    </section>
  )
}
export function LiveLoanDetails({ id }) {
  const { organizationId, refreshKey } = useDataSource(),
    [state, setState] = useState({ scope: '', data: null, error: '' }),
    scope = `${organizationId}:${refreshKey}:${id}`
  useEffect(() => {
    const a = new AbortController()
    lenderGet(
      `/api/v1/organizations/${organizationId}/lenddesk/loans/${id}/screen`,
      a.signal,
    )
      .then((data) => {
        if (!a.signal.aborted) setState({ scope, data, error: '' })
      })
      .catch((e) => {
        if (!a.signal.aborted) setState({ scope, data: null, error: e.message })
      })
    return () => a.abort()
  }, [organizationId, refreshKey, id, scope])
  if (state.scope !== scope) return <p role="status">Loading loan details…</p>
  if (state.error)
    return (
      <p className="al-error" role="alert">
        {state.error}
      </p>
    )
  const d = state.data,
    l = d.loan
  return (
    <section className="al-detail">
      <header>
        <h2>
          {loanId(id)} / {l.symbol} <LoanBadge>{display(l.status)}</LoanBadge>
        </h2>
        <div>
          <Link className="ref-primary" to={`/loans/${loanId(id)}/manage`}>
            Manage loan
          </Link>
          {l.status === 'ACTIVE' &&
            !l.openMarginCount &&
            !l.pendingMarginRequest && (
              <Link to={`/margin-calls/${loanId(id)}/issue`}>
                Issue margin call
              </Link>
            )}
          {l.canRecall && (
            <Link to={`/recalls/${loanId(id)}/new`}>Create recall</Link>
          )}
          <Link to={`/agreements/${l.mslaId}`}>View MSLA</Link>
        </div>
      </header>
      <div className="al-detail-grid">
        <LoanCard
          title="Loan terms"
          rows={[
            ['MSLA', l.agreementNumber],
            ['Reservation', l.reservationId],
            ['Original qty', num(l.quantity)],
            ['Returned qty', num(l.returnedQuantity)],
            ['Outstanding qty', num(l.outstandingQuantity)],
            ['Cached price', money(l.price, l.currency)],
            ['MSLA fee', `${num(l.feeBps)} bps`],
            ['MSLA rebate', `${num(l.rebateBps)} bps`],
            ['Day count', `Actual/${l.dayBasis}`],
            ['Start date UTC', date(l.settledAt)],
            ['Maturity', 'Not recorded'],
          ]}
        />
        <article className="al-card">
          <h3>Collateral coverage</h3>
          <LoanCard
            title="Recorded valuation"
            rows={[
              ['Valued UTC', date(l.valuationAt)],
              ['Eligible collateral', money(l.eligibleCollateral, l.currency)],
              ['Required collateral', money(l.requiredCollateral, l.currency)],
              ['Shortfall', money(l.shortfall, l.currency)],
              [
                'Coverage',
                l.coverage == null ? 'Not valued' : `${num(l.coverage)}%`,
              ],
            ]}
          />
          <table>
            <thead>
              <tr>
                <th>Position</th>
                <th>Asset</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {d.collateral.map((c) => (
                <tr key={c.id}>
                  <td>{c.id}</td>
                  <td>
                    {c.assetType === 'CASH'
                      ? money(c.cashAmount, l.currency)
                      : `${num(c.quantity)} securities`}
                  </td>
                  <td>{display(c.status)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!d.collateral.length && <p>No collateral positions.</p>}
        </article>
        <article className="al-card">
          <h3>Returns & recalls</h3>
          <table>
            <thead>
              <tr>
                <th>Type / ID</th>
                <th>Quantity</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {d.returns.map((x) => (
                <tr key={`r${x.id}`}>
                  <td>Return {x.id}</td>
                  <td>{num(x.quantity)}</td>
                  <td>{display(x.status)}</td>
                </tr>
              ))}
              {d.recalls.map((x) => (
                <tr key={`c${x.id}`}>
                  <td>Recall {x.id}</td>
                  <td>{num(x.quantity - x.fulfilledQuantity)}</td>
                  <td>{display(x.status)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!d.returns.length && !d.recalls.length && (
            <p>No recorded returns or recalls.</p>
          )}
          <p>
            Only settled returns reduce outstanding. Full loan closure follows
            the existing collateral release workflow.
          </p>
          <Link to={`/loans/${loanId(id)}/manage`}>
            Receive securities / manage requests
          </Link>
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
              {d.activity.map((x, i) => (
                <tr key={i}>
                  <td>{date(x.at)}</td>
                  <td>{display(x.event)}</td>
                  <td>{x.reference}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </article>
      </div>
    </section>
  )
}
