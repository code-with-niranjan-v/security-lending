import { Fragment, useMemo, useState } from 'react'
import { useNavigate, useOutletContext } from 'react-router-dom'
import StatusBadge from '../components/StatusBadge'
import { reservations } from '../data/reservationData'

const statusTone = (status) => ({ Active: 'good', 'Converted to loan': 'neutral', 'Expiring soon': 'warn', Expired: 'risk', Released: 'neutral' }[status] ?? 'neutral')

export default function ReservationsPage() {
  const { searchTerm } = useOutletContext()
  const navigate = useNavigate()
  const [tab, setTab] = useState('Active reservations')
  const [status, setStatus] = useState('All statuses')
  const [selectedId, setSelectedId] = useState('RSV-000219')
  const [expandedId, setExpandedId] = useState('RSV-000219')
  const rows = useMemo(() => reservations.filter((reservation) => {
    const matchesTab = tab === 'All reservations' || (tab === 'Active reservations' && ['Active', 'Expiring soon'].includes(reservation.status)) || (tab === 'Historical' && !['Active', 'Expiring soon'].includes(reservation.status))
    const matchesStatus = status === 'All statuses' || reservation.status === status
    return matchesTab && matchesStatus && Object.values(reservation).join(' ').toLowerCase().includes(searchTerm.toLowerCase())
  }), [tab, status, searchTerm])

  function selectReservation(id) {
    setSelectedId(id)
    setExpandedId((current) => current === id ? null : id)
  }

  return <>
    <section className="heading reservation-heading"><h1>Inventory Reservations</h1><div><span>Active reservations <b>19</b></span><span>Reserved quantity <b>2.48M</b></span><span>Expiring &lt;1 hour <b>3</b></span><span className="critical">1 requires follow-up</span></div><button className="primary-action" type="button" onClick={() => navigate('/reservations/RES-004891/manage')}>Manage reservation</button></section>
    <section className="reservation-toolbar panel"><div className="table-tabs" role="tablist" aria-label="Reservation view"><button type="button" className={tab === 'All reservations' ? 'active' : ''} onClick={() => setTab('All reservations')}>All reservations <StatusBadge tone="neutral">36</StatusBadge></button><button type="button" className={tab === 'Active reservations' ? 'active' : ''} onClick={() => setTab('Active reservations')}>Active reservations <StatusBadge tone="good">19</StatusBadge></button><button type="button" className={tab === 'Historical' ? 'active' : ''} onClick={() => setTab('Historical')}>Converted &amp; expired <StatusBadge tone="warn">17</StatusBadge></button></div><div><select value={status} onChange={(event) => setStatus(event.target.value)}><option>All statuses</option><option>Active</option><option>Expiring soon</option><option>Converted to loan</option><option>Expired</option></select><button className="link" type="button" onClick={() => setStatus('All statuses')}>Clear filters</button><button className="outline-action" type="button">Export register</button></div></section>
    <section className="reservation-workspace"><article className="panel reservation-register"><div className="panel-title"><h2>Reservation register <small>{rows.length} reservations shown</small></h2><small>Expand a reservation to review committed inventory sources</small></div><div className="scroll"><table><thead><tr><th /><th>Reservation ID</th><th>Locate ID</th><th>Created UTC</th><th>Borrower</th><th>MSLA</th><th>Security</th><th>Reserved</th><th>Converted</th><th>Available after</th><th>Rate</th><th>Expiry</th><th>Time remaining</th><th>Status</th></tr></thead><tbody>{rows.map((reservation) => <Fragment key={reservation.id}><tr className={selectedId === reservation.id ? 'selected' : ''} onClick={() => selectReservation(reservation.id)}><td><button className="row-expander" type="button" aria-label={`${expandedId === reservation.id ? 'Collapse' : 'Expand'} ${reservation.id}`}>{expandedId === reservation.id ? '⌄' : '›'}</button></td><td><a>{reservation.id}</a></td><td><a>{reservation.locate}</a></td><td>{reservation.created}</td><td><strong>{reservation.borrower}</strong></td><td><a>{reservation.msla}</a></td><td><b>{reservation.symbol}</b><small className="table-subline mono">{reservation.isin}</small></td><td className="num"><b>{reservation.reserved}</b></td><td className="num">{reservation.converted}</td><td className="num">{reservation.availableAfter}</td><td>{reservation.rate}</td><td>{reservation.expires}</td><td className={reservation.status === 'Expiring soon' || reservation.status === 'Expired' ? 'review-overdue' : ''}>{reservation.remaining}</td><td><StatusBadge tone={statusTone(reservation.status)}>{reservation.status}</StatusBadge></td></tr>{expandedId === reservation.id && <tr className="child-row"><td colSpan="14"><div className="child-table reservation-child"><div><b>Committed inventory · {reservation.id}</b><span>Created from {reservation.locate} · {reservation.borrower}</span></div><table><thead><tr><th>Custody source</th><th>Inventory before reservation</th><th>Committed quantity</th><th>Available after reservation</th></tr></thead><tbody>{reservation.sources.map((source) => <tr key={source[0]}><td>{source[0]}</td><td className="num">{source[1]}</td><td className="num"><b>{source[2]}</b></td><td className="num">{source[3]}</td></tr>)}</tbody></table><div className="reservation-actions"><span>Reserved <b>{reservation.reserved}</b> at <b>{reservation.rate}</b> · {reservation.remaining === 'Expired' ? 'Inventory has been released' : `Reservation expires ${reservation.expires}`}</span>{['Active', 'Expiring soon'].includes(reservation.status) && <div><button type="button" className="outline-action">Release reservation</button><button type="button" className="primary-action">Extend expiry</button></div>}</div></div></td></tr>}</Fragment>)}</tbody></table></div><footer>Showing {rows.length} of 36 reservations · Active commitments automatically unlock when expiry is reached unless converted to a loan</footer></article></section>
  </>
}
