import { Fragment, useState } from 'react'
import { Link, NavLink, useOutletContext, useSearchParams } from 'react-router-dom'
import ReferenceTable, { Badge } from '../components/reference/ReferenceTable'
import { useReferenceSort } from '../hooks/useReferenceSort'
import { recallConfig, marginCallConfig } from '../data/remainingData'
import { lifecycleReturns, returnTimeline } from '../data/designLifecycle'
import './ReferencePages.css'
import './LifecycleExceptionsPage.css'

const tone = value => /overdue|failed|deficit/i.test(value) ? 'risk' : /pending/i.test(value) && !/payment|^pending$/i.test(value) ? 'purple' : /payment_pending|^pending$/i.test(value) ? 'warn' : /submitted|^open$|acknowledged|scheduled/i.test(value) ? 'purple' : 'good'
const State = ({ value }) => <Badge tone={tone(value)}>{value}</Badge>
const returnColumns = [['Return ID','id'], ['Loan ID','loan'], ['Recall ID','recall'], ['Borrower','borrower'], ['Security','security'], ['Quantity','quantity'], ['Requested UTC','requested'], ['Collateral Release State','collateral'], ['Fee Payment State','fee'], ['Rebate Payment State','rebate'], ['Settlement State','settlement']]
const emptyFilters = { borrower: '', security: '', state: '', settlement: '' }

function DetailStrip({ title, entries }) {
  return <section className="ref-detail-card lifecycle-strip"><h2>{title}</h2><dl>{entries.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl></section>
}

function ReturnDetails({ row }) {
  if (row.id !== 'RET-000041') return <div className="ref-empty">No additional settlement details are included for {row.id} in the reference snapshot.</div>
  return <div className="lifecycle-return-details">
    <DetailStrip title="▤  Linked Recall Summary" entries={[
      ['Recall ID', <Link to="/recalls?id=REC-000122">REC-000122</Link>], ['Recall Type','FULL'], ['Requested UTC','Jan 27, 2025 16:20:11'], ['Recall State',<State value="SETTLED"/>], ['Return ID',<Link to="/returns?id=RET-000041">RET-000041</Link>],
    ]}/>
    <ReferenceTable title="▱  Collateral Release" headers={['Release ID','Collateral ID','Asset / Cash Type','Quantity','Market Value','Release Requested UTC','Release State']} rows={[[<Link to="/collateral">CR-000771</Link>,<Link to="/collateral">COL-006-001</Link>,'Security','1,000,000','$415,600,000','Jan 28, 2025 14:05:40',<State value="PENDING"/>]]}/>
    <DetailStrip title="%  Fee Payment" entries={[
      ['Fee ID',<Link to="/fees-rebates">FEE-009314</Link>], ['Amount','$128,750'], ['Due UTC','Jan 28, 2025 18:00:00'], ['Payment State',<State value="PAYMENT_PENDING"/>],
    ]}/>
    <DetailStrip title="▱  Rebate Payment" entries={[
      ['Rebate ID',<Link to="/fees-rebates">REB-004981</Link>], ['Amount','$36,400'], ['Due UTC','Jan 28, 2025 18:00:00'], ['Payment State',<State value="PAID"/>], ['Paid UTC','Jan 28, 2025 14:11:09'],
    ]}/>
    <section className="ref-detail-card lifecycle-timeline"><h2>▣  Settlement Event Timeline</h2><ol>{returnTimeline.map(([label, date, state]) => <li key={label} className={state}><span className="lifecycle-timeline-dot" aria-label={state === 'done' ? 'Completed' : state === 'current' ? 'Current' : 'Pending'}>{state === 'done' ? '✓' : ''}</span><strong>{label}</strong>{state === 'current' && <State value="SETTLEMENT_SUBMITTED"/>}<time>{date}</time></li>)}</ol></section>
  </div>
}

const linkedRecall = { id: 'REC-000122', loan: 'LOAN-000010', borrower: 'BARC01', security: 'MSFT', quantity: '1,000,000', notice: 'Jan 27, 2025 16:20:11', due: '—', returnId: 'RET-000041', status: 'Settled', detail: [['Recall type','FULL'], ['Requested UTC','Jan 27, 2025 16:20:11'], ['Recall state','SETTLED'], ['Linked return','RET-000041']] }

export default function LifecycleExceptionsPage({ view = 'returns' }) {
  const { searchTerm = '' } = useOutletContext()
  const [params] = useSearchParams()
  const config = view === 'recalls' ? recallConfig : marginCallConfig
  const isReturns = view === 'returns'
  const source = isReturns ? lifecycleReturns : view === 'recalls' ? [linkedRecall, ...config.rows] : config.rows
  const columns = isReturns ? returnColumns : config.columns
  const defaultId = isReturns ? 'RET-000041' : source[0].id
  const [expanded, setExpanded] = useState(params.get('id') ?? defaultId)
  const [draft, setDraft] = useState(emptyFilters)
  const [filters, setFilters] = useState(emptyFilters)
  const rows = source.filter(row => (!filters.borrower || row.borrower === filters.borrower) && (!filters.security || row.security === filters.security) && (!filters.state || (isReturns ? row.collateral : row.status) === filters.state) && (!filters.settlement || row.settlement === filters.settlement) && Object.values(row).join(' ').toLowerCase().includes(searchTerm.toLowerCase()))
  const { sorted, sortHeading } = useReferenceSort(rows)
  const metrics = isReturns ? [['↶','Open Returns','12'], ['▣','Returns Pending Settlement','4'], ['▱','Fee Payments (Outstanding)','$128,750'], ['%','Rebate Payments (Outstanding)','$36,400']] : config.metrics.map(([label, value], index) => [['⚑','▱','◷','⚠'][index], label, value])
  const toggle = id => setExpanded(current => current === id ? null : id)
  function cell(row, key) {
    const value = row[key] ?? '—'
    if (key === 'id') return <button className="ref-id" onClick={() => toggle(row.id)}>{value}</button>
    if (key === 'loan') return <Link to={`/loans/${value}`}>{value}</Link>
    if (key === 'recall') return <Link to={`/recalls?id=${value}`}>{value}</Link>
    if (key === 'returnId') return <Link to={`/returns?id=${value}`}>{value}</Link>
    if (['collateral','fee','rebate','settlement','status'].includes(key)) return <State value={value}/>
    return typeof value === 'number' ? value.toLocaleString('en-US') : value
  }
  return <section className={`ref-page lifecycle-page lifecycle-${view}`}><header className="ref-heading"><h1>Lifecycle Exceptions</h1><nav className="lifecycle-tabs" aria-label="Lifecycle views">{[['returns','Returns'],['recalls','Recalls'],['margin-calls','Margin Calls']].map(([path, label]) => <NavLink key={path} to={`/${path}`}>{label}</NavLink>)}</nav>{isReturns && <Link className="ref-primary lifecycle-workflow-link" to="/returns/RET-001782/manage">Manage return request</Link>}</header>
    <section className="lifecycle-metrics" aria-label="Lifecycle summary">{metrics.map(([icon, label, value]) => <div key={label}><span aria-hidden="true">{icon}</span><div><small>{label}</small><strong>{value}</strong></div></div>)}</section>
    <form className="ref-filters lifecycle-filters" onSubmit={event => { event.preventDefault(); setFilters(draft) }}>{[['borrower','Borrower','All borrowers'], ['security','Security','All securities'], ['state',isReturns ? 'Collateral Release State' : 'Status','All states'], ...(isReturns ? [['settlement','Settlement State','All states']] : [])].map(([key, label, placeholder]) => <label key={key}>{label}<select aria-label={label} value={draft[key]} onChange={event => setDraft(current => ({...current,[key]:event.target.value}))}><option value="">{placeholder}</option>{[...new Set(source.map(row => row[key === 'state' ? isReturns ? 'collateral' : 'status' : key]))].map(value => <option key={value}>{value}</option>)}</select></label>)}<button type="button" className="ref-clear" onClick={() => { setDraft(emptyFilters); setFilters(emptyFilters) }}>Clear</button><button className="ref-primary" type="submit">Apply</button></form>
    <div className="ref-scroll"><table className="ref-main-table lifecycle-table"><thead><tr><th aria-label="Expand record"/>{columns.map(([label, key]) => sortHeading(key,label))}<th>Action</th></tr></thead><tbody>{sorted.map(row => <Fragment key={row.id}><tr className={expanded === row.id ? 'ref-selected' : ''}><td><button className="ref-expander" aria-label={`${expanded === row.id ? 'Collapse' : 'Expand'} ${row.id}`} aria-expanded={expanded === row.id} onClick={() => toggle(row.id)}>{expanded === row.id ? '⌄' : '›'}</button></td>{columns.map(([,key]) => <td key={key} className={key === 'quantity' || key === 'amount' ? 'ref-num' : ''}>{cell(row,key)}</td>)}<td><button className="lifecycle-manage" aria-label={`View details ${row.id}`} aria-expanded={expanded === row.id} onClick={() => toggle(row.id)}>•••</button></td></tr>{expanded === row.id && <tr className="ref-child"><td colSpan={columns.length + 2}>{isReturns ? <ReturnDetails row={row}/> : <div className="lifecycle-other-details"><ReferenceTable title={`${view === 'recalls' ? 'Recall' : 'Margin Call'} Summary — ${row.id}`} headers={['Field','Value']} rows={row.detail.map(([key,value]) => [key,key === 'Linked return' && row.returnId ? <Link to={`/returns?id=${row.returnId}`}>{value}</Link> : value])}/></div>}</td></tr>}</Fragment>)}{sorted.length === 0 && <tr><td colSpan={columns.length + 2} className="ref-empty">No records match your filters.</td></tr>}</tbody></table></div>
  </section>
}
