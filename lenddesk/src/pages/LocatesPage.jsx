import { Fragment, useState } from 'react'
import { Link, useNavigate, useOutletContext } from 'react-router-dom'
import ReferenceTable, { Badge } from '../components/reference/ReferenceTable'
import { useReferenceSort } from '../hooks/useReferenceSort'
import { designLocates, locateReservations, locateActivity } from '../data/designLocates'
import './ReferencePages.css'
import './LocatesPage.css'
import LiveLocatesPage from './LiveLocatesPage'
import { useDataSource } from '../hooks/useDataSource'

const number = value => value.toLocaleString('en-US')
const emptyFilters = { borrower: '', msla: '', security: '', status: '', expiryTone: '' }
const columns = [['id', 'Locate ID'], ['borrower', 'Borrower'], ['msla', 'MSLA'], ['security', 'Security'], ['requested', 'Requested qty'], ['approved', 'Approved qty'], ['reserved', 'Reserved qty'], ['available', 'Available qty'], ['expiry', 'Expires UTC'], ['status', 'Status']]

function Expiry({ row, inline = false }) {
  if (row.expiryTone === 'plain') return <span>{row.expiry}</span>
  const tone = row.expiryTone ?? row.tone
  return <span className={`locate-expiry ref-${tone} ${inline ? 'locate-expiry-inline' : ''}`}><span aria-hidden="true">{tone === 'purple' ? '◷' : '⚠'}</span><span>{inline ? <>{row.countdown} ({row.expiry})</> : <>{row.expiry}<br/>({row.countdown})</>}</span></span>
}

function LocateDetails({ row }) {
  const hasDetails = row.id === 'LOC-017222'
  return <div className="locate-reference-details"><div className="locate-detail-grid"><ReferenceTable title={`▣  Reservations (${hasDetails ? 3 : 0})`} headers={['Reservation ID', 'Quantity', 'Remaining qty', 'Expiry countdown', 'Linked loan', 'Status']} rows={hasDetails ? locateReservations.map(reservation => [<Link to="/reservations" state={{ reservationId: reservation.id }}>{reservation.id}</Link>, number(reservation.quantity), number(reservation.remaining), <Expiry row={reservation} inline/>, <Link to={`/loans/${reservation.loan}`}>{reservation.loan}</Link>, <Badge>Active</Badge>]) : []}/>
    <section className="ref-detail-card locate-summary"><h2>▱  Locate Summary — {row.id}</h2><div>{[[['Borrower', row.borrower], ['MSLA', row.msla], ['Security', row.security], ['Requested qty', number(row.requested)], ['Approved qty', number(row.approved)]], [['Reserved qty', number(row.reserved)], ['Available qty', number(row.available)], ['Expires (UTC)', <Expiry row={row}/>], ['Status', <Badge tone={row.status === 'Pending' ? 'purple' : 'good'}>{row.status}</Badge>]]].map((items, index) => <dl key={index}>{items.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>)}</div></section></div>
    <ReferenceTable title="⌁  Activity Trail" headers={['Time (UTC)', 'Event', 'Details']} rows={hasDetails ? locateActivity.map(([time, event, details]) => [time, <><span className="ref-event-icon">{event === 'Adjustment' ? '⇄' : event === 'Locate approved' ? '⊙' : '▣'}</span>{event}</>, details]) : []}/>
  </div>
}

export default function LocatesPage() {
  const { mode } = useDataSource()
  return mode === 'live' ? <LiveLocatesPage /> : <DemoLocatesPage />
}

function DemoLocatesPage() {
  const { searchTerm = '' } = useOutletContext()
  const navigate = useNavigate()
  const [expanded, setExpanded] = useState('LOC-017222')
  const [draft, setDraft] = useState(emptyFilters)
  const [filters, setFilters] = useState(emptyFilters)
  const rows = designLocates.filter(row => Object.entries(filters).every(([key, value]) => !value || row[key] === value) && `${row.id} ${row.borrower} ${row.msla} ${row.security}`.toLowerCase().includes(searchTerm.toLowerCase()))
  const { sorted, sortHeading } = useReferenceSort(rows)
  const toggle = id => setExpanded(current => current === id ? null : id)
  return <section className="ref-page ref-locates"><header className="ref-heading"><h1>Locate Requests</h1><div className="locate-heading-stats"><span><i aria-hidden="true">◎</i>6 Pending</span><span><i aria-hidden="true">▣</i>19 Active Reservations</span></div></header>
    <form className="ref-filters locate-reference-filters" onSubmit={event => { event.preventDefault(); setFilters(draft) }}>{[['borrower', 'Borrower', 'All borrowers'], ['msla', 'MSLA', 'All MSLAs'], ['security', 'Security', 'All securities'], ['status', 'Request status', 'All statuses']].map(([key, label, placeholder]) => <label key={key}>{label}<select aria-label={label} value={draft[key]} onChange={event => setDraft(current => ({ ...current, [key]: event.target.value }))}><option value="">{placeholder}</option>{[...new Set(designLocates.map(row => row[key]))].map(value => <option key={value}>{value}</option>)}</select></label>)}<label>Expiry<select aria-label="Expiry" value={draft.expiryTone} onChange={event => setDraft(current => ({ ...current, expiryTone: event.target.value }))}><option value="">All expiries</option><option value="risk">Urgent (red)</option><option value="warn">Upcoming (amber)</option><option value="purple">Later (purple)</option></select></label><button className="ref-clear" type="button" onClick={() => { setDraft(emptyFilters); setFilters(emptyFilters) }}>Clear</button><button className="ref-primary" type="submit">Apply</button></form>
    <div className="ref-scroll"><table className="ref-main-table locate-reference-table"><thead><tr><th aria-label="Expand locate"/>{columns.map(([key, label]) => sortHeading(key, label))}<th>Action</th></tr></thead><tbody>{sorted.map(row => <Fragment key={row.id}><tr className={expanded === row.id ? 'ref-selected' : ''}><td><button type="button" className="ref-expander" aria-label={`${expanded === row.id ? 'Collapse' : 'Expand'} ${row.id}`} aria-expanded={expanded === row.id} onClick={() => toggle(row.id)}>{expanded === row.id ? '⌄' : '›'}</button></td><td><button type="button" className="ref-id" onClick={() => toggle(row.id)}>{row.id}</button></td><td>{row.borrower}</td><td>{row.msla}</td><td>{row.security}</td>{[row.requested, row.approved, row.reserved, row.available].map((value, index) => <td key={index} className="ref-num">{number(value)}</td>)}<td><Expiry row={row}/></td><td><Badge tone={row.status === 'Pending' ? 'purple' : 'good'}>{row.status}</Badge></td><td><button type="button" className="locate-manage" aria-label={`Manage ${row.id}`} aria-expanded={expanded === row.id} onClick={() => row.id === 'LOC-017221' ? navigate(`/locates/${row.id}/manage`) : toggle(row.id)}>Manage <span aria-hidden="true">{expanded === row.id ? '⌃' : '⌄'}</span></button></td></tr>{expanded === row.id && <tr className="ref-child"><td colSpan={12}><LocateDetails row={row}/></td></tr>}</Fragment>)}{sorted.length === 0 && <tr><td colSpan={12} className="ref-empty">No locate requests match your filters.</td></tr>}</tbody></table></div>
  </section>
}
