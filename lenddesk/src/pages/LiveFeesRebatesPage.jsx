import { Fragment, useEffect, useState } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { lenderGet } from '../api/lendDeskApi'
import { useDataSource } from '../hooks/useDataSource'
import { useReferenceSort } from '../hooks/useReferenceSort'
import { LoanBadge, LoanCard } from '../components/loans/LoanCards'
import RunAccrualButton from '../components/loans/RunAccrualButton'
import { num, date, loanId, display, downloadLoans } from '../utils/loanFormat'
import './ReferencePages.css'
import './LoansPage.css'
import './LiveValuationsPage.css'
import './LiveFeesRebatesPage.css'
const money = (v, currency = 'INR') =>
  v == null
    ? 'Not recorded'
    : new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency,
        minimumFractionDigits: 2,
        maximumFractionDigits: 6,
      }).format(v)
export default function LiveFeesRebatesPage() {
  const { organizationId, refreshKey, refresh } = useDataSource(),
    { searchTerm = '' } = useOutletContext(),
    [tab, setTab] = useState('ALL'),
    [loan, setLoan] = useState(''),
    [expanded, setExpanded] = useState(null),
    [state, setState] = useState({ scope: '', data: null, error: '' })
  const queryText = new URLSearchParams(
      Object.entries({ tab, loanId: loan, q: searchTerm }).filter(([, v]) => v),
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
            `/api/v1/organizations/${organizationId}/lenddesk/fees-rebates/overview?${queryText}&page=${page++}&size=200`,
            a.signal,
          )
          rows.push(...data.records.content)
        } while (page < data.records.totalPages)
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
    options =
      data ??
      (state.scope.startsWith(`${organizationId}:`) ? state.data : null),
    { sorted, sortHeading } = useReferenceSort(data?.rows ?? [])
  return (
    <section className="active-loans ref-page live-fees-rebates">
      <header className="al-heading">
        <div>
          <h1>
            Fees &amp; Rebates <span className="ref-demo">LIVE API</span>
          </h1>
          <p>Recorded loan fees, cash rebates and outstanding settlements.</p>
        </div>
        <div>
          {data &&
            ['FEE', 'REBATE'].map((kind) => {
              const eligible = data.eligibleLoans.filter((l) => l.kind === kind)
              return (
                <RunAccrualButton
                  key={`${organizationId}:${loan}:${searchTerm}:${kind}`}
                  kind={kind}
                  loans={eligible}
                  onFinished={refresh}
                />
              )
            })}
        </div>
      </header>
      {data && (
        <>
          <div className="al-metrics">
            {data.totals.map((t) => (
              <Fragment key={t.currency}>
                <span>
                  {t.currency} fee accrued today{' '}
                  <b>{money(t.feeToday, t.currency)}</b>
                </span>
                <span>
                  Rebate accrued today <b>{money(t.rebateToday, t.currency)}</b>
                </span>
                <span>
                  Net recorded accrual{' '}
                  <b>{money(t.feeTotal - t.rebateTotal, t.currency)}</b>
                </span>
              </Fragment>
            ))}
            <span>
              Outstanding settlements <b>{data.counts.DUE}</b>
            </span>
          </div>
          <p className="fee-business-date">
            Business date: {data.businessDate} · {data.businessZone}. Accrual
            totals are recorded daily entries, not unpaid balances.
          </p>
        </>
      )}
      <nav className="al-tabs" aria-label="Fee and rebate views">
        {[
          ['ALL', 'All accruals'],
          ['FEE', 'Fees'],
          ['REBATE', 'Rebates'],
          ['DUE', 'Due settlement'],
        ].map(([value, label]) => (
          <button
            type="button"
            key={value}
            className={tab === value ? 'active' : ''}
            onClick={() => {
              setTab(value)
              setExpanded(null)
            }}
          >
            {label}
            {data && <b> {data.counts[value]}</b>}
          </button>
        ))}
      </nav>
      <div className="al-filters">
        <label>
          Loan
          <select
            value={loan}
            onChange={(e) => {
              setLoan(e.target.value)
              setExpanded(null)
            }}
          >
            <option value="">All loans</option>
            {options?.loans.map((l) => (
              <option key={l.id} value={l.id}>
                {l.label}
              </option>
            ))}
          </select>
        </label>
        <button onClick={refresh}>Refresh accruals</button>
        <button
          disabled={!data?.rows.length}
          onClick={() =>
            downloadLoans(sorted, [
              'id',
              'loanId',
              'borrower',
              'type',
              'entryDate',
              'currency',
              'rateBps',
              'amount',
              'accruedTotal',
              'settlementRule',
              'dueDate',
              'status',
            ])
          }
        >
          Export
        </button>
      </div>
      {state.scope !== scope ? (
        <p role="status">Loading fees and rebates…</p>
      ) : state.error ? (
        <p role="alert" className="al-error">
          Fees &amp; Rebates unavailable: {state.error}
        </p>
      ) : (
        <>
          <div className="al-table-scroll">
            <table className="al-table">
              <thead>
                <tr>
                  {[
                    ['id', tab === 'DUE' ? 'Settlement ID' : 'Accrual ID'],
                    ['loanId', 'Loan ID'],
                    ['borrower', 'Borrower'],
                    ['type', 'Type'],
                    ['entryDate', 'Accrual / period date'],
                    ['rateBps', 'Rate (bps p.a.)'],
                    [
                      'amount',
                      tab === 'DUE' ? 'Settlement amount' : 'Daily amount',
                    ],
                    ['accruedTotal', 'Recorded accrued total'],
                    ['settlementRule', 'Settlement rule'],
                    ['dueDate', 'Scheduled / due date'],
                    ['status', 'Status'],
                  ].map(([key, label]) => sortHeading(key, label))}
                </tr>
              </thead>
              <tbody>
                {sorted.map((r) => (
                  <Fragment key={r.key}>
                    <tr>
                      <td>
                        <button
                          aria-expanded={expanded === r.key}
                          onClick={() =>
                            setExpanded(expanded === r.key ? null : r.key)
                          }
                        >
                          {r.category === 'accruals'
                            ? 'ACC'
                            : r.type === 'FEE'
                              ? 'FEE'
                              : 'REB'}
                          -{String(r.id).padStart(6, '0')}
                        </button>
                      </td>
                      <td>
                        <Link to={`/loans/${loanId(r.loanId)}`}>
                          {loanId(r.loanId)}
                        </Link>
                      </td>
                      <td>{r.borrower}</td>
                      <td>
                        {r.type === 'FEE' ? 'Lending fee' : 'Cash rebate'}
                      </td>
                      <td>{r.entryDate}</td>
                      <td>{num(r.rateBps)}</td>
                      <td>{money(r.amount, r.currency)}</td>
                      <td>{money(r.accruedTotal, r.currency)}</td>
                      <td>{display(r.settlementRule)}</td>
                      <td>
                        {r.dueDate ??
                          (r.settlementRule === 'AT_CLOSE'
                            ? 'At loan close'
                            : 'Not scheduled')}
                      </td>
                      <td>
                        <LoanBadge>{display(r.status)}</LoanBadge>
                      </td>
                    </tr>
                    {expanded === r.key && (
                      <tr>
                        <td colSpan={11}>
                          <div className="al-detail-grid fee-detail-grid">
                            <LoanCard
                              title="Accrual context"
                              rows={[
                                ['Loan', loanId(r.loanId)],
                                ['Borrower', r.borrower],
                                ['Agreement', r.agreementNumber],
                                ['Security', r.symbol],
                                [
                                  'Type',
                                  r.type === 'FEE'
                                    ? 'Lending fee'
                                    : 'Cash rebate',
                                ],
                                ['Accrual date', r.entryDate],
                                [
                                  'Historical base value',
                                  money(r.baseValue, r.currency),
                                ],
                                ['Annual rate', `${num(r.rateBps)} bps`],
                                [
                                  'Recorded amount',
                                  money(r.amount, r.currency),
                                ],
                              ]}
                            />
                            <LoanCard
                              title="Settlement context"
                              rows={[
                                [
                                  'Current MSLA day basis',
                                  `Actual/${r.dayBasis}`,
                                ],
                                [
                                  'Current MSLA settlement rule',
                                  display(r.settlementRule),
                                ],
                                [
                                  'Scheduled / due date',
                                  r.dueDate ?? 'At loan close',
                                ],
                                [
                                  'Recorded settlement',
                                  display(r.settlementStatus),
                                ],
                                [
                                  'Settlement ID',
                                  r.settlementId ??
                                    (r.category !== 'accruals'
                                      ? r.id
                                      : 'Not issued'),
                                ],
                              ]}
                            />
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
                {!sorted.length && (
                  <tr>
                    <td colSpan={11}>
                      {tab === 'DUE'
                        ? 'No outstanding fee bills or rebate settlements match this view.'
                        : 'No recorded accruals match this view. Use the due accrual buttons to process eligible settled loans.'}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <p className="ref-empty">
            Fees use loan market values and MSLA rates. Rebates use confirmed
            cash collateral balances. Due processing keeps existing daily
            records and follows the contractual settlement schedule; a fresh
            valuation alone does not create accrual entries.
          </p>
          <section className="al-detail">
            <h2>Recent accrual runs</h2>
            <div className="al-table-scroll">
              <table className="al-table">
                <thead>
                  <tr>
                    <th>Run ID</th>
                    <th>Loan</th>
                    <th>Type</th>
                    <th>Requested UTC</th>
                    <th>Status</th>
                    <th>Result / failure</th>
                  </tr>
                </thead>
                <tbody>
                  {data.runs.map((r) => (
                    <tr key={r.id}>
                      <td>{r.id}</td>
                      <td>{loanId(r.loanId)}</td>
                      <td>{r.kind === 'FEE' ? 'Fee' : 'Rebate'}</td>
                      <td>{date(r.createdAt)}</td>
                      <td>
                        <LoanBadge>{display(r.status)}</LoanBadge>
                      </td>
                      <td>
                        {r.failureReason ??
                          (r.result
                            ? `${r.result.daysRecorded} daily record(s); ${r.result.billsQueued ?? r.result.settlementsQueued ?? 0} bill/settlement(s); processed through ${r.result.processedThrough ?? 'not processed'}`
                            : 'Awaiting lifecycle-service')}
                      </td>
                    </tr>
                  ))}
                  {!data.runs.length && (
                    <tr>
                      <td colSpan={6}>No manual accrual runs recorded.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </section>
  )
}
