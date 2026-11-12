import { useEffect, useState } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { lenderGet } from '../api/lendDeskApi'
import { useDataSource } from '../hooks/useDataSource'
import { useReferenceSort } from '../hooks/useReferenceSort'
import { LoanBadge } from '../components/loans/LoanCards'
import RunValuationButton from '../components/loans/RunValuationButton'
import { money, num, date, loanId, display } from '../utils/loanFormat'
import './ReferencePages.css'
import './LoansPage.css'
import './LiveValuationsPage.css'
export default function LiveValuationsPage() {
  const { organizationId, refreshKey, refresh } = useDataSource(),
    { searchTerm = '' } = useOutletContext()
  const [coverage, setCoverage] = useState(''),
    [status, setStatus] = useState('ACTIVE'),
    [expanded, setExpanded] = useState(null),
    [state, setState] = useState({ scope: '', data: null, error: '' })
  const query = new URLSearchParams(
      Object.entries({ coverage, status, q: searchTerm }).filter(([, v]) => v),
    ),
    queryText = query.toString(),
    scope = `${organizationId}:${refreshKey}:${queryText}`
  useEffect(() => {
    const a = new AbortController()
    async function load() {
      try {
        let p = 0,
          data,
          rows = []
        do {
          data = await lenderGet(
            `/api/v1/organizations/${organizationId}/lenddesk/valuations/overview?${queryText}&page=${p++}&size=200`,
            a.signal,
          )
          rows.push(...data.loans.content)
        } while (p < data.loans.totalPages)
        if (!a.signal.aborted)
          setState({ scope, data: { ...data, rows }, error: '' })
      } catch (e) {
        if (!a.signal.aborted) setState({ scope, data: null, error: e.message })
      }
    }
    load()
    return () => a.abort()
  }, [organizationId, refreshKey, queryText, scope])
  const data = state.scope === scope ? state.data : null,
    { sorted, sortHeading } = useReferenceSort(data?.rows ?? []),
    eligible = (data?.rows ?? [])
      .filter((l) => l.canRunValuation && !l.valuationRunPending)
      .map((l) => l.id)
  return (
    <section className="active-loans ref-page live-valuations">
      <header className="al-heading">
        <div>
          <h1>
            Daily Valuations <span className="ref-demo">LIVE API</span>
          </h1>
          <p>
            Run loan valuations using cached security prices and confirmed
            collateral.
          </p>
        </div>
        {data && (
          <RunValuationButton
            key={`${organizationId}:batch:${queryText}`}
            loanIds={eligible}
            onFinished={refresh}
            label={`Run visible active loans (${eligible.length})`}
          />
        )}
      </header>
      {eligible.length > 100 && (
        <p>Narrow the filters to at most 100 loans per batch.</p>
      )}
      <div className="al-filters">
        <label>
          Loan status
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="ACTIVE">Active loans</option>
            <option value="">All loans</option>
            <option value="CLOSED">Closed loans</option>
          </select>
        </label>
        <label>
          Coverage
          <select
            value={coverage}
            onChange={(e) => setCoverage(e.target.value)}
          >
            <option value="">All coverage</option>
            <option value="UNKNOWN">Not valued</option>
            <option value="SHORTFALL">Shortfall</option>
            <option value="SUFFICIENT">Sufficient</option>
          </select>
        </label>
        <button onClick={refresh}>Refresh valuations</button>
        <Link to="/collateral">View collateral</Link>
      </div>
      {state.scope !== scope ? (
        <p role="status">Loading valuations…</p>
      ) : state.error ? (
        <p className="al-error" role="alert">
          Valuations unavailable: {state.error}
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
                    ['outstandingQuantity', 'Outstanding'],
                    ['price', 'Cached price'],
                    ['priceAt', 'Price UTC'],
                    ['marketValue', 'Loan market value'],
                    ['eligibleCollateral', 'Eligible collateral'],
                    ['coverage', 'Coverage'],
                    ['requiredCollateral', 'Required collateral'],
                    ['shortfall', 'Shortfall'],
                    ['valuationAt', 'Valued UTC'],
                  ].map(([k, v]) => sortHeading(k, v))}
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {sorted.map((l) => (
                  <tr key={l.id}>
                    <td>
                      <button
                        onClick={() =>
                          setExpanded(expanded === l.id ? null : l.id)
                        }
                      >
                        {loanId(l.id)}
                      </button>
                    </td>
                    <td>{l.borrower}</td>
                    <td>{l.symbol}</td>
                    <td>{num(l.outstandingQuantity)}</td>
                    <td>{money(l.price, l.currency)}</td>
                    <td>{date(l.priceAt)}</td>
                    <td>{money(l.marketValue, l.currency)}</td>
                    <td>{money(l.eligibleCollateral, l.currency)}</td>
                    <td>
                      {l.coverage == null
                        ? 'Not valued'
                        : `${num(l.coverage)}%`}
                    </td>
                    <td>{money(l.requiredCollateral, l.currency)}</td>
                    <td>{money(l.shortfall, l.currency)}</td>
                    <td>{date(l.valuationAt)}</td>
                    <td>
                      {l.canRunValuation ? (
                        <RunValuationButton
                          key={`${organizationId}:${l.id}:${l.pendingValuationRunId ?? 0}`}
                          loanIds={[l.id]}
                          pendingRunId={l.pendingValuationRunId}
                          onFinished={refresh}
                        />
                      ) : (
                        <span>{display(l.status)}</span>
                      )}
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
            Cached price timestamps and recorded valuation timestamps are
            separate. Missing prices cause a failed run with an explanation. A
            valuation does not settle collateral or issue a margin call.
          </p>
          {expanded != null && (
            <ValuationDetails
              key={`${organizationId}:${expanded}`}
              id={expanded}
            />
          )}
        </>
      )}
    </section>
  )
}
function ValuationDetails({ id }) {
  const { organizationId, refreshKey } = useDataSource(),
    [state, setState] = useState({ scope: '', data: null, error: '' }),
    [page, setPage] = useState(0),
    scope = `${organizationId}:${refreshKey}:${id}:${page}`
  useEffect(() => {
    const a = new AbortController()
    lenderGet(
      `/api/v1/organizations/${organizationId}/lenddesk/valuations/loans/${id}/screen?page=${page}`,
      a.signal,
    )
      .then((data) => {
        if (!a.signal.aborted) setState({ scope, data, error: '' })
      })
      .catch((e) => {
        if (!a.signal.aborted) setState({ scope, data: null, error: e.message })
      })
    return () => a.abort()
  }, [organizationId, refreshKey, id, page, scope])
  if (state.scope !== scope)
    return <p role="status">Loading valuation history…</p>
  if (state.error) return <p role="alert">{state.error}</p>
  const d = state.data
  return (
    <section className="al-detail">
      <header>
        <h2>Valuation history · {loanId(id)}</h2>
        <Link to={`/loans/${loanId(id)}/manage`}>Manage loan</Link>
      </header>
      <div className="al-table-scroll">
        <table className="al-table">
          <thead>
            <tr>
              {[
                'Valuation ID',
                'Valued UTC',
                'Snapshot quantity',
                'Price',
                'Price UTC',
                'Loan market value',
                'Eligible collateral',
                'Required',
                'Coverage',
                'Shortfall',
              ].map((t) => (
                <th key={t}>{t}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {d.history.content.map((v) => (
              <tr key={v.id}>
                <td>VAL-{v.id}</td>
                <td>{date(v.valuedAt)}</td>
                <td>{num(v.quantity)}</td>
                <td>{money(v.price, d.loan.currency)}</td>
                <td>{date(v.priceAt)}</td>
                <td>{money(v.marketValue, d.loan.currency)}</td>
                <td>{money(v.eligibleCollateral, d.loan.currency)}</td>
                <td>{money(v.requiredCollateral, d.loan.currency)}</td>
                <td>{num(v.coverage)}%</td>
                <td>{money(v.shortfall, d.loan.currency)}</td>
              </tr>
            ))}
            {!d.history.content.length && (
              <tr>
                <td colSpan={10}>
                  No valuation recorded yet. Run a valuation for this active
                  loan.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {d.history.totalPages > 1 && (
        <div>
          <button disabled={!page} onClick={() => setPage((p) => p - 1)}>
            Previous
          </button>
          <span>
            {' '}
            Page {page + 1} of {d.history.totalPages}{' '}
          </span>
          <button
            disabled={page + 1 >= d.history.totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      )}
      <h3>Valuation runs</h3>
      {d.runs.map((r) => (
        <p key={r.id}>
          Run {r.id} · {date(r.createdAt)} ·{' '}
          <LoanBadge>{display(r.status)}</LoanBadge> {r.failureReason}
        </p>
      ))}
      {!d.runs.length && <p>No recorded valuation requests.</p>}
    </section>
  )
}
