import { Fragment, useState } from 'react'
import { Link, useNavigate, useOutletContext } from 'react-router-dom'
import { designAgreements } from '../data/designAgreements'
import ReferenceTable, { Badge } from '../components/reference/ReferenceTable'
import { useReferenceSort } from '../hooks/useReferenceSort'
import './ReferencePages.css'

const columns = [['id', 'Agreement ID'], ['borrower', 'Borrower'], ['status', 'Status'], ['effectiveDate', 'Effective date'], ['expiryDate', 'Expiry date'], ['fee', 'Lending fee rate'], ['rebate', 'Rebate frequency'], ['collateralRatio', 'Collateral ratio'], ['recall', 'Recall'], ['activeLoans', 'Active loans'], ['exposure', 'Exposure']]

function AgreementDetails({ agreement }) {
  const [tab, setTab] = useState('Collateral rules')
  const rules = <ReferenceTable title={`Eligible collateral rules (${agreement.collateralRules.length})`} headers={['#', 'Asset type', 'Eligibility', 'Advance / Haircut', 'Concentration']} rows={agreement.collateralRules.map(([id, type, eligibility, haircut, concentration]) => [id, type, <Badge tone={eligibility === 'Include' ? 'good' : 'risk'}>{eligibility}</Badge>, haircut, concentration])}/>
  const loans = <ReferenceTable title={`Active loans (${agreement.activeLoans})`} headers={['Loan ID', 'Security', 'Quantity', 'Market value', 'Start date']} rows={(agreement.id === 'MSLA-00124' ? agreement.loanRows : []).map(([id, ...cells]) => [<Link to={`/loans/${id}`}>{id}</Link>, ...cells])}/>
  const activity = <ReferenceTable title={`Recent locates / reservations (${agreement.id === 'MSLA-00124' ? 10 : agreement.locates.length})`} headers={['Time (UTC)', 'Type', 'Security', 'Quantity', 'Status']} rows={agreement.locates.map(([time, type, security, qty, status]) => [time, type, security, qty, <Badge tone={status === 'Reserved' ? 'purple' : 'good'}>{status}</Badge>])}/>
  return <div className="ref-agreement-details"><div className="ref-tabs" role="tablist" aria-label={`${agreement.id} details`}>{['Terms', 'Collateral rules', 'Loans', 'Billing', 'Audit & FIX'].map(label => <button key={label} type="button" role="tab" aria-selected={tab === label} onClick={() => setTab(label)}>{label}</button>)}</div>
    {tab === 'Collateral rules' ? <div className="ref-three-cards">{rules}{loans}{activity}</div> : tab === 'Loans' ? loans : tab === 'Terms' ? <ReferenceTable title="Agreement terms" headers={['Borrower', 'Effective date', 'Expiry date', 'Lending fee', 'Rebate frequency', 'Collateral ratio', 'Recall']} rows={[[agreement.borrower, agreement.effectiveDate, agreement.expiryDate, agreement.fee, agreement.rebate, agreement.collateralRatio, agreement.recall]]}/> : <div className="ref-empty">No {tab.toLowerCase()} records are included in this demo.</div>}
  </div>
}

export default function AgreementsPage() {
  const { searchTerm = '' } = useOutletContext()
  const [expanded, setExpanded] = useState('MSLA-00124')
  const [borrower, setBorrower] = useState('All borrowers')
  const [status, setStatus] = useState('All statuses')
  const [recall, setRecall] = useState('All')
  const [date, setDate] = useState('')
  const navigate = useNavigate()
  const rows = designAgreements.filter(row => `${row.id} ${row.borrower}`.toLowerCase().includes(searchTerm.toLowerCase()) && (borrower === 'All borrowers' || row.borrower === borrower) && (status === 'All statuses' || row.status === status) && (recall === 'All' || row.recall === recall) && (!date || new Date(row.effectiveDate) <= new Date(date) && new Date(row.expiryDate) >= new Date(date)))
  const { sorted, sortHeading } = useReferenceSort(rows)
  return <section className="ref-page ref-agreements"><header className="ref-heading"><h1>MSLA Agreements</h1><span className="ref-active-count">48 active</span><button className="ref-primary" type="button" onClick={() => navigate('/agreements/new')}>New agreement</button></header>
    <section className="ref-filters" aria-label="Agreement filters"><label>Borrower<select aria-label="Borrower" value={borrower} onChange={event => setBorrower(event.target.value)}><option>All borrowers</option>{designAgreements.map(row => <option key={row.id}>{row.borrower}</option>)}</select></label><label>Status<select aria-label="Status" value={status} onChange={event => setStatus(event.target.value)}><option>All statuses</option><option>Active</option><option>Under review</option><option>Suspended</option></select></label><label>Jurisdiction<select aria-label="Jurisdiction"><option>All jurisdictions</option></select></label><label>Recall enabled<select aria-label="Recall enabled" value={recall} onChange={event => setRecall(event.target.value)}><option>All</option><option>Enabled</option><option>Disabled</option></select></label><label>Date range<input aria-label="Agreement active on date" type={date ? 'date' : 'text'} placeholder="Effective or expiry date" value={date} onFocus={event => { event.target.type = 'date' }} onChange={event => setDate(event.target.value)}/></label></section>
    <div className="ref-scroll"><table className="ref-main-table ref-agreements-table"><thead><tr><th aria-label="Expand agreement"/>{columns.map(([key, label]) => sortHeading(key, label))}</tr></thead><tbody>{sorted.map(row => <Fragment key={row.id}><tr className={expanded === row.id ? 'ref-selected' : ''}><td><button className="ref-expander" aria-label={`${expanded === row.id ? 'Collapse' : 'Expand'} ${row.id}`} aria-expanded={expanded === row.id} onClick={() => setExpanded(current => current === row.id ? null : row.id)}>{expanded === row.id ? '⌄' : '›'}</button></td><td><button className="ref-id" onClick={() => setExpanded(current => current === row.id ? null : row.id)}>{row.id}</button></td><td>{row.borrower}</td><td><Badge>{row.status}</Badge></td><td>{row.effectiveDate}</td><td>{row.expiryDate}</td><td>{row.fee}</td><td>{row.rebate}</td><td>{row.collateralRatio}</td><td><Badge tone={row.recall === 'Enabled' ? 'good' : 'warn'}>{row.recall}</Badge></td><td>{row.activeLoans}</td><td className="ref-num">{row.exposure}</td></tr>{expanded === row.id && <tr className="ref-child"><td colSpan={12}><AgreementDetails agreement={row}/></td></tr>}</Fragment>)}{sorted.length === 0 && <tr><td className="ref-empty" colSpan={12}>No agreements match your filters.</td></tr>}</tbody></table></div>
  </section>
}
