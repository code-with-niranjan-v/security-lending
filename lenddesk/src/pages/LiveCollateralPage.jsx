import { useEffect, useRef, useState } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { lenderGet, lenderPost } from '../api/lendDeskApi'
import { useDataSource } from '../hooks/useDataSource'
import { useReferenceSort } from '../hooks/useReferenceSort'
import { LoanBadge } from '../components/loans/LoanCards'
import RunValuationButton from '../components/loans/RunValuationButton'
import { money, num, date, loanId, display } from '../utils/loanFormat'
import './ReferencePages.css'
import './LoansPage.css'
import './LiveValuationsPage.css'
const empty = { connectionId: '', loanId: '', assetType: '', status: '' }
export default function LiveCollateralPage() {
  const { organizationId, refreshKey, refresh } = useDataSource(),
    { searchTerm = '' } = useOutletContext(),
    [draft, setDraft] = useState(empty),
    [filters, setFilters] = useState(empty),
    [expanded, setExpanded] = useState(null),
    [state, setState] = useState({ scope: '', data: null, error: '' })
  const queryText = new URLSearchParams(
      Object.entries({ ...filters, q: searchTerm }).filter(([, v]) => v),
    ).toString(),
    scope = `${organizationId}:${refreshKey}:${queryText}`
  useEffect(() => {
    const a = new AbortController()
    async function load() {
      try {
        let page = 0,
          data,
          rows = []
        do {
          data = await lenderGet(
            `/api/v1/organizations/${organizationId}/collateral/overview?${queryText}&page=${page++}&size=200`,
            a.signal,
          )
          rows.push(...data.positions.content)
        } while (page < data.positions.totalPages)
        if (!a.signal.aborted)
          setState({ scope, data: { ...data, rows }, error: '' })
      } catch (e) {
        if (!a.signal.aborted) setState({ scope, data: null, error: e.message })
      }
    }
    load()
    return () => a.abort()
  }, [organizationId, refreshKey, scope, queryText])
  const data = state.scope === scope ? state.data : null,
    options =
      data ??
      (state.scope.startsWith(`${organizationId}:`) ? state.data : null),
    { sorted, sortHeading } = useReferenceSort(data?.rows ?? [])
  return (
    <section className="active-loans ref-page live-collateral">
      <header className="al-heading">
        <div>
          <h1>
            Collateral &amp; Valuations{' '}
            <span className="ref-demo">LIVE API</span>
          </h1>
          <p>
            Confirmed collateral, cached asset marks and settlement movements.
          </p>
        </div>
        <Link to="/valuations">Run loan valuations</Link>
      </header>
      {data && (
        <div className="al-metrics">
          {data.totals.map((t) => (
            <span key={t.currency}>
              {t.currency} held market / eligible{' '}
              <b>
                {money(t.marketValue, t.currency)} /{' '}
                {money(t.eligibleValue, t.currency)}
              </b>
              {t.missingPrices > 0 && (
                <small>{t.missingPrices} missing prices</small>
              )}
            </span>
          ))}
        </div>
      )}
      <form
        className="al-filters"
        onSubmit={(e) => {
          e.preventDefault()
          setFilters(draft)
        }}
      >
        {[
          ['connectionId', 'Borrower', options?.borrowers ?? []],
          ['loanId', 'Loan', options?.loans ?? []],
          [
            'assetType',
            'Collateral type',
            [
              { id: 'CASH', label: 'Cash' },
              { id: 'SECURITY', label: 'Security' },
            ],
          ],
          [
            'status',
            'Status',
            [
              'PLEDGE_PENDING',
              'POSTED',
              'RELEASE_REQUESTED',
              'RELEASED',
              'REJECTED',
            ].map((id) => ({ id, label: display(id) })),
          ],
        ].map(([key, label, values]) => (
          <label key={key}>
            {label}
            <select
              value={draft[key]}
              onChange={(e) => setDraft({ ...draft, [key]: e.target.value })}
            >
              <option value="">All</option>
              {values.map((v) => (
                <option key={v.id} value={v.id}>
                  {v.label}
                </option>
              ))}
            </select>
          </label>
        ))}
        <button
          type="button"
          onClick={() => {
            setDraft(empty)
            setFilters(empty)
          }}
        >
          Clear
        </button>
        <button className="ref-primary">Apply</button>
        <button type="button" onClick={refresh}>
          Refresh collateral
        </button>
      </form>
      {state.scope !== scope ? (
        <p role="status">Loading collateral…</p>
      ) : state.error ? (
        <p role="alert" className="al-error">
          Collateral unavailable: {state.error}
        </p>
      ) : (
        <>
          <div className="al-table-scroll">
            <table className="al-table">
              <thead>
                <tr>
                  {[
                    ['id', 'Collateral ID'],
                    ['loanId', 'Loan ID'],
                    ['borrower', 'Borrower'],
                    ['assetType', 'Asset type'],
                    ['symbol', 'Security'],
                    ['quantity', 'Quantity'],
                    ['price', 'Cached price'],
                    ['marketValue', 'Market value'],
                    ['haircut', 'Haircut'],
                    ['eligibleValue', 'Eligible value'],
                    ['loanRequiredValue', 'Loan required'],
                    ['loanCoverage', 'Loan collateral / required'],
                    ['status', 'Settlement state'],
                    ['priceAt', 'Price UTC'],
                  ].map(([k, label]) => sortHeading(k, label))}
                </tr>
              </thead>
              <tbody>
                {sorted.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <button
                        onClick={() =>
                          setExpanded(expanded === p.id ? null : p.id)
                        }
                      >
                        COL-{String(p.id).padStart(6, '0')}
                      </button>
                    </td>
                    <td>
                      {p.loanId == null ? (
                        'Not assigned'
                      ) : (
                        <Link to={`/loans/${loanId(p.loanId)}`}>
                          {loanId(p.loanId)}
                        </Link>
                      )}
                    </td>
                    <td>{p.borrower}</td>
                    <td>{display(p.assetType)}</td>
                    <td>{p.symbol ?? '—'}</td>
                    <td>
                      {p.assetType === 'CASH'
                        ? money(p.cashAmount, p.currency)
                        : num(p.quantity)}
                    </td>
                    <td>{money(p.price, p.currency)}</td>
                    <td>{money(p.marketValue, p.currency)}</td>
                    <td>{num(p.haircut)}%</td>
                    <td>
                      {money(p.eligibleValue, p.currency)}
                      {p.valuationIssue && <small>{p.valuationIssue}</small>}
                    </td>
                    <td>{money(p.loanRequiredValue, p.currency)}</td>
                    <td>
                      {p.loanCoverage == null ? '—' : `${num(p.loanCoverage)}%`}
                    </td>
                    <td>
                      <LoanBadge>{display(p.status)}</LoanBadge>
                      {!p.includedInCoverage && (
                        <small>Excluded from coverage</small>
                      )}
                    </td>
                    <td>
                      {p.assetType === 'CASH' ? 'Cash at par' : date(p.priceAt)}
                    </td>
                  </tr>
                ))}
                {!sorted.length && (
                  <tr>
                    <td colSpan={14}>No matching collateral positions.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <p className="ref-empty">
            Current marks are indicative cached-price values. Only posted and
            release-requested positions count toward coverage; pending pledges
            and released positions are excluded. The required amount and ratio
            refer to the whole loan, not an individual collateral lot.
          </p>
          {expanded != null && (
            <CollateralDetails
              key={`${organizationId}:${expanded}`}
              id={expanded}
            />
          )}
        </>
      )}
    </section>
  )
}
function CollateralDetails({ id }) {
  const { organizationId, refreshKey, refresh } = useDataSource(),
    [state, setState] = useState({ scope: '', data: null, error: '' }),
    scope = `${organizationId}:${refreshKey}:${id}`
  useEffect(() => {
    const a = new AbortController()
    lenderGet(
      `/api/v1/organizations/${organizationId}/collateral/${id}/screen`,
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
  if (state.scope !== scope)
    return <p role="status">Loading collateral details…</p>
  if (state.error) return <p role="alert">{state.error}</p>
  const d = state.data,
    p = d.position
  return (
    <section className="al-detail">
      <header>
        <h2>
          COL-{String(id).padStart(6, '0')} · {p.symbol}
        </h2>
        {p.canRunValuation && (
          <RunValuationButton
            key={`${organizationId}:${p.loanId}`}
            loanIds={[p.loanId]}
            onFinished={refresh}
          />
        )}
      </header>
      {['PLEDGE_PENDING', 'RELEASE_REQUESTED'].includes(p.status) && (
        <CollateralDecision key={scope} position={p} />
      )}
      <h3>Recorded position valuation history</h3>
      <div className="al-table-scroll">
        <table className="al-table">
          <thead>
            <tr>
              {[
                'Valuation ID',
                'Valued UTC',
                'Price',
                'Asset price UTC',
                'Market value',
                'Haircut',
                'Eligible value',
              ].map((h) => (
                <th key={h}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {d.valuations.map((v) => (
              <tr key={v.valuationId}>
                <td>VAL-{v.valuationId}</td>
                <td>{date(v.valuedAt)}</td>
                <td>{money(v.price, p.currency)}</td>
                <td>{date(v.priceAt)}</td>
                <td>{money(v.marketValue, p.currency)}</td>
                <td>{num(Number(v.haircut) * 100)}%</td>
                <td>{money(v.eligibleValue, p.currency)}</td>
              </tr>
            ))}
            {!d.valuations.length && (
              <tr>
                <td colSpan={7}>
                  No per-position valuation snapshot recorded. Run a loan
                  valuation to record one.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <p>{d.historyWindow}</p>
      <h3>Settlement movement history</h3>
      <table className="al-table">
        <thead>
          <tr>
            <th>Time UTC</th>
            <th>Event</th>
            <th>Reference</th>
          </tr>
        </thead>
        <tbody>
          {d.movements.map((m) => (
            <tr key={m.id}>
              <td>{date(m.createdAt)}</td>
              <td>{display(m.type)}</td>
              <td>{m.reference}</td>
            </tr>
          ))}
          {!d.movements.length && (
            <tr>
              <td colSpan={3}>No recorded settlement movements.</td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  )
}
function CollateralDecision({ position: p }) {
  const { organizationId, refresh } = useDataSource(),
    [action, setAction] = useState('confirm'),
    [reference, setReference] = useState(''),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    dialog = useRef(null),
    alive = useRef(true),
    posting = useRef(false)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])
  async function submit() {
    if (posting.current) return
    posting.current = true
    setBusy(true)
    setError('')
    const release = p.status === 'RELEASE_REQUESTED',
      path = release
        ? action === 'confirm'
          ? 'release-confirmations'
          : 'release-rejections'
        : action
    try {
      await lenderPost(
        `/api/v1/organizations/${organizationId}/collateral/${p.id}/${path}`,
        action === 'confirm'
          ? { reference: reference.trim() }
          : { reason: reference.trim() },
      )
      if (alive.current) {
        dialog.current.close()
        refresh()
      }
    } catch (e) {
      if (alive.current)
        setError(
          `${e.message}. Refresh before retrying if the outcome is uncertain.`,
        )
    } finally {
      posting.current = false
      if (alive.current) setBusy(false)
    }
  }
  return (
    <form
      className="al-live-form"
      onSubmit={(e) => {
        e.preventDefault()
        if (reference.trim()) dialog.current.showModal()
      }}
    >
      <h3>
        {p.status === 'RELEASE_REQUESTED'
          ? 'Review requested collateral release'
          : 'Review collateral pledge'}
      </h3>
      <label>
        Decision
        <select value={action} onChange={(e) => setAction(e.target.value)}>
          <option value="confirm">Confirm</option>
          <option value="reject">Reject</option>
        </select>
      </label>
      <label>
        {action === 'confirm' ? 'Settlement reference' : 'Rejection reason'}
        <input
          required
          maxLength={action === 'confirm' ? 160 : 500}
          value={reference}
          onChange={(e) => setReference(e.target.value)}
        />
      </label>
      <button className="ref-primary" disabled={!reference.trim()}>
        Review collateral decision
      </button>
      <dialog
        ref={dialog}
        onCancel={(e) => {
          if (busy) e.preventDefault()
        }}
      >
        <h2>Confirm live collateral decision</h2>
        <p>
          COL-{p.id} · {display(p.status)} · {action}
        </p>
        <p>{reference}</p>
        <p>
          Confirm only after checking custody settlement. Confirming a pledge or
          release changes the recorded collateral balance.
        </p>
        {error && <p role="alert">{error}</p>}
        <button
          type="button"
          disabled={busy}
          onClick={() => dialog.current.close()}
        >
          Back
        </button>
        <button
          type="button"
          className="ref-primary"
          disabled={busy}
          onClick={submit}
        >
          {busy ? 'Submitting…' : 'Confirm live decision'}
        </button>
      </dialog>
    </form>
  )
}
