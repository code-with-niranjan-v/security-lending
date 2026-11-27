import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { Link, NavLink, useOutletContext } from 'react-router-dom'
import { lenderGet, lenderPost } from '../api/lendDeskApi'
import ReferenceTable, { Badge } from '../components/reference/ReferenceTable'
import { useDataSource } from '../hooks/useDataSource'
import './ReferencePages.css'
import './LifecycleExceptionsPage.css'

const number = (value) => Number(value ?? 0).toLocaleString('en-US')
const label = (value) => String(value ?? '—').toLowerCase().replaceAll('_', ' ').replace(/^./, (letter) => letter.toUpperCase())
const returnId = (id) => `RET-${String(id).padStart(6, '0')}`
const loanId = (id) => `LOAN-${String(id).padStart(6, '0')}`
const recallId = (id) => id == null ? '—' : `REC-${String(id).padStart(6, '0')}`
const marginCallId = (id) => id == null ? '—' : `MARGIN-${String(id).padStart(6, '0')}`
const stamp = (value) => value ? new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Kolkata', dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)).replace(',', '') : '—'
const tone = (value) => /rejected|failed|cancelled|overdue/i.test(value) ? 'risk' : /pending/i.test(value) ? 'warn' : 'good'
const State = ({ value }) => <Badge tone={tone(value)}>{label(value)}</Badge>

function ReturnLifecycleTimeline({ row }) {
  const settled = row.status === 'SETTLED'
  const rejected = row.status === 'REJECTED'
  const releaseRequested = row.collateralReleaseState === 'RELEASE_REQUESTED'
  const released = row.collateralReleaseState === 'RELEASED'
  const closed = row.loanStatus === 'CLOSED'
  const stages = [
    ['Return requested', stamp(row.createdAt), 'done'],
    ['Lender receipt confirmation', settled ? stamp(row.settledAt) : rejected ? 'Rejected' : 'Awaiting lender', rejected ? 'risk' : settled ? 'done' : 'current'],
    ['Securities returned to lender', settled ? stamp(row.settledAt) : '—', settled ? 'done' : 'future'],
    ['Collateral release', released ? 'Released' : releaseRequested ? 'Release requested' : 'Not required / pending', released ? 'done' : releaseRequested ? 'current' : 'future'],
    ['Loan closed', closed ? stamp(row.loanClosedAt) : '—', closed ? 'done' : 'future'],
    ['Final fee & rebate settlement', closed ? 'Queued to Lifecycle Service' : '—', closed ? 'current' : 'future'],
  ]
  return <section className="ref-detail-card lifecycle-timeline live-return-timeline"><h2>▣ Return Lifecycle Timeline</h2><ol>{stages.map(([name, time, state]) => <li key={name} className={state}><span className="lifecycle-timeline-dot" aria-label={state === 'done' ? 'Completed' : state === 'current' ? 'Current stage' : state === 'risk' ? 'Rejected' : 'Pending'}>{state === 'done' ? '✓' : state === 'risk' ? '!' : ''}</span><strong>{name}</strong><time>{time}</time></li>)}</ol></section>
}

function ReturnDecision({ row, onDone }) {
  const { organizationId } = useDataSource()
  const [action, setAction] = useState('settle')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const reference = useRef(null)
  const dialog = useRef(null)
  function automaticReference() {
    if (!reference.current) {
      const suffix = globalThis.crypto?.randomUUID?.().slice(0, 8) ?? Math.random().toString(36).slice(2, 10)
      reference.current = `LENDDESK-RETURN-RECEIPT-${row.id}-${new Date().toISOString().replace(/[-:.TZ]/g, '')}-${suffix}`
    }
    return reference.current
  }
  async function submit() {
    setBusy(true); setError('')
    try {
      const path = `/api/v1/organizations/${organizationId}/returns/${row.id}/${action === 'settle' ? 'settle' : 'reject'}`
      await lenderPost(path, action === 'settle' ? { reference: automaticReference() } : { reason: reason.trim() })
      dialog.current.close()
      onDone(`${returnId(row.id)} ${action === 'settle' ? 'settled; returned securities were recorded and the borrower notified.' : 'rejected and the borrower notified.'}`)
    } catch (e) { setError(`${e.message}. Refresh before retrying if the outcome is uncertain.`) } finally { setBusy(false) }
  }
  if (row.status !== 'PENDING') return <p className="ref-empty">This return request is already {label(row.status)}.</p>
  return <form className="live-return-decision" onSubmit={(event) => { event.preventDefault(); if (action === 'settle' || reason.trim()) dialog.current.showModal() }}><div><label>Decision<select value={action} onChange={(event) => setAction(event.target.value)}><option value="settle">Confirm receipt and settle return</option><option value="reject">Reject return request</option></select></label>{action === 'reject' && <label>Rejection reason<textarea required maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} /></label>}</div><button className="ref-primary" type="submit">Review decision</button><dialog ref={dialog}><h2>Confirm live return decision</h2><p>{returnId(row.id)} · {number(row.quantity)} shares · {action === 'settle' ? 'returned securities will be received into lender inventory.' : 'the borrower return request will be rejected.'}</p>{action === 'settle' && <p>LendDesk will attach an internal settlement audit reference automatically.</p>}{error && <p role="alert" className="al-error">{error}</p>}<div><button type="button" disabled={busy} onClick={() => dialog.current.close()}>Back</button><button type="button" className="ref-primary" disabled={busy} onClick={submit}>{busy ? 'Submitting…' : 'Confirm live decision'}</button></div></dialog></form>
}

export default function LiveReturnsPage() {
  const { searchTerm = '' } = useOutletContext()
  const { organizationId, refreshKey, refresh } = useDataSource()
  const [state, setState] = useState({ scope: '', data: [], loading: true, error: '' })
  const [expanded, setExpanded] = useState(null)
  const [status, setStatus] = useState('All statuses')
  const [notice, setNotice] = useState('')
  const scope = `${organizationId}:${refreshKey}`
  useEffect(() => { const abort = new AbortController(); lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/returns/overview?page=0&size=200`, abort.signal).then((data) => { if (!abort.signal.aborted) setState({ scope, data: data.content ?? [], loading: false, error: '' }) }).catch((error) => { if (!abort.signal.aborted) setState({ scope, data: [], loading: false, error: error.message }) }); return () => abort.abort() }, [organizationId, refreshKey, scope])
  const rows = useMemo(() => (state.scope === scope ? state.data : []).map((item) => ({ ...item, displayId: returnId(item.id), displayLoanId: loanId(item.loanId), displayRecallId: recallId(item.recallId), displayMarginCallId: marginCallId(item.marginCallId) })), [scope, state.data, state.scope])
  const filtered = useMemo(() => rows.filter((row) => (status === 'All statuses' || row.status === status) && Object.values(row).join(' ').toLowerCase().includes(searchTerm.toLowerCase())), [rows, searchTerm, status])
  const pending = rows.filter((row) => row.status === 'PENDING')
  const pendingQuantity = pending.reduce((sum, row) => sum + Number(row.quantity ?? 0), 0)
  const selectedReturn = rows.find((row) => row.id === expanded)
  function done(message) { setNotice(message); setExpanded(null); refresh() }
  return <section className="ref-page lifecycle-page lifecycle-returns live-returns"><header className="ref-heading"><h1>Lifecycle Exceptions </h1><nav className="lifecycle-tabs" aria-label="Lifecycle views"><NavLink to="/returns">Returns</NavLink><NavLink to="/recalls">Recalls</NavLink><NavLink to="/margin-calls">Margin Calls</NavLink></nav></header>
    <section className="lifecycle-metrics" aria-label="Return summary"><div><span>↶</span><div><small>Return requests</small><strong>{rows.length}</strong></div></div><div><span>▣</span><div><small>Awaiting receipt</small><strong>{pending.length}</strong></div></div><div><span>⇄</span><div><small>Pending quantity</small><strong>{number(pendingQuantity)}</strong></div></div><div><span>✓</span><div><small>Settled returns</small><strong>{rows.filter((row) => row.status === 'SETTLED').length}</strong></div></div></section>
    <section className="live-return-toolbar"><label>Return status<select value={status} onChange={(event) => setStatus(event.target.value)}><option>All statuses</option>{[...new Set(rows.map((row) => row.status))].map((value) => <option key={value} value={value}>{label(value)}</option>)}</select></label><button className="ref-clear" type="button" onClick={() => setStatus('All statuses')}>Clear</button><button className="ref-primary" type="button" onClick={refresh}>Refresh live data</button></section>
    {notice && <p className="live-return-notice" role="status">{notice}</p>}
    {state.loading || state.scope !== scope ? <p className="ref-empty" role="status">Loading live return requests…</p> : state.error ? <p className="live-error" role="alert">Live return requests unavailable: {state.error}</p> : <div className="ref-scroll"><table className="ref-main-table lifecycle-table live-return-table"><thead><tr><th aria-label="Expand return"/><th>Return ID</th><th>Loan ID</th><th>Security</th><th>Recall ID</th><th>Margin call</th><th>Borrower return ID</th><th>Quantity</th><th>Requested IST</th><th>Settlement reference</th><th>Settled IST</th><th>Status</th><th>Action</th></tr></thead><tbody>{filtered.map((row) => <Fragment key={row.id}><tr className={expanded === row.id ? 'ref-selected' : ''}><td><button className="ref-expander" type="button" aria-expanded={expanded === row.id} aria-label={`${expanded === row.id ? 'Collapse' : 'Expand'} ${row.displayId}`} onClick={() => setExpanded((current) => current === row.id ? null : row.id)}>{expanded === row.id ? '⌄' : '›'}</button></td><td><button className="ref-id" type="button" onClick={() => setExpanded((current) => current === row.id ? null : row.id)}>{row.displayId}</button></td><td><Link to={`/loans/${row.loanId}`}>{row.displayLoanId}</Link></td><td><b>{row.securitySymbol ?? '—'}</b>{row.securityName ? ` · ${row.securityName}` : ''}</td><td>{row.displayRecallId}</td><td>{row.displayMarginCallId}</td><td>{row.borrowerReturnId}</td><td className="ref-num">{number(row.quantity)}</td><td>{stamp(row.createdAt)}</td><td>{row.settlementReference ?? '—'}</td><td>{stamp(row.settledAt)}</td><td><State value={row.status} /></td><td><button className="lifecycle-manage" type="button" onClick={() => setExpanded(row.id)}>{row.status === 'PENDING' ? 'Review' : 'View'}</button></td></tr>{expanded === row.id && <tr className="ref-child"><td colSpan="13"><div className="live-return-details"><ReferenceTable title={`▤ Return request summary — ${row.displayId}`} headers={['Field', 'Value']} rows={[["Loan", row.displayLoanId], ["Security", `${row.securitySymbol ?? '—'}${row.securityName ? ` · ${row.securityName}` : ''}`], ["Recall", row.displayRecallId], ["Margin call", row.displayMarginCallId], ["Borrower", row.borrowerName ?? '—'], ["Borrower return ID", row.borrowerReturnId], ["Quantity", number(row.quantity)], ["Requested IST", stamp(row.createdAt)], ["Collateral release", <State value={row.collateralReleaseState} />], ["Current status", <State value={row.status} />]]} /><ReturnDecision row={row} onDone={done} /></div></td></tr>}</Fragment>)}{!filtered.length && <tr><td colSpan="13" className="ref-empty">No live return requests match this view.</td></tr>}</tbody></table></div>}
    {selectedReturn && <ReturnLifecycleTimeline row={selectedReturn} />}
  </section>
}
