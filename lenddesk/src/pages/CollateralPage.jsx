import { useDataSource } from '../hooks/useDataSource'
import LiveCollateralPage from './LiveCollateralPage'
import { Fragment, useState } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import { designCollateral, collateralValuations, collateralMovements } from '../data/designCollateral'
import ReferenceTable, { Badge } from '../components/reference/ReferenceTable'
import { useReferenceSort } from '../hooks/useReferenceSort'
import './ReferencePages.css'

const money = (value, decimals = 0) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: decimals, maximumFractionDigits: decimals }).format(value)
const allFilters = { borrower: '', loan: '', type: '', security: '', settlement: '' }
const columns = [['id', 'Collateral ID'], ['loan', 'Loan ID'], ['borrower', 'Borrower'], ['type', <>Asset /<br/>Cash Type</>], ['security', 'Security'], ['quantity', 'Pledged qty'], ['price', 'Price'], ['marketValue', 'Market Value'], ['haircut', 'Haircut'], ['adjusted', 'Adjusted Value'], ['required', 'Required Value'], ['ratio', <>Collateral<br/>Ratio</>], ['settlement', <>Settlement<br/>State</>], ['valuation', 'Last Valuation']]

function CollateralDetails({ row }) {
  if (row.id !== 'COL-006-001') return <div className="ref-empty">No additional valuation or movement history is included for {row.id} in this demo.</div>
  return <div className="ref-collateral-details"><ReferenceTable title="⌁  Daily Valuation History" headers={['Valuation Date (UTC)', 'Price', 'Market Value', 'Haircut', 'Adjusted Value', 'Required Value', 'Collateral Ratio']} rows={collateralValuations}/><ReferenceTable title="▣  Collateral Movement / Settlement History" headers={['Event Time (UTC)', 'Event', 'Details', 'Pledged qty', 'Pledged qty (After)', 'Settlement State']} rows={collateralMovements.map(([time, event, details, qty, after, state]) => [time, <><span className="ref-event-icon">{event === 'Settlement' ? '⊙' : '⇄'}</span>{event}</>, details, qty, after, <Badge>{state}</Badge>])}/></div>
}

function DemoCollateralPage() {
  const { searchTerm = '' } = useOutletContext()
  const [expanded, setExpanded] = useState('COL-006-001')
  const [draft, setDraft] = useState(allFilters)
  const [filters, setFilters] = useState(allFilters)
  const rows = designCollateral.filter(row => Object.entries(filters).every(([key, value]) => !value || row[key] === value) && `${row.id} ${row.loan} ${row.borrower} ${row.security}`.toLowerCase().includes(searchTerm.toLowerCase()))
  const { sorted, sortHeading } = useReferenceSort(rows)
  return <section className="ref-page ref-collateral"><header className="ref-heading"><h1>Collateral &amp; Valuations</h1><div className="ref-summary-cards">{[['▱', 'Collateral Market Value', '$1,982,500,000'], ['◎', 'Required Value', '$1,760,000,000'], ['⚖', 'Excess / Shortfall', '+$222,500,000 (+12.6%)'], ['⚒', 'Average Haircut', '12.8%']].map(([icon, label, value]) => <div key={label}><span aria-hidden="true">{icon}</span><div><small>{label}</small><strong className={label === 'Excess / Shortfall' ? 'ref-positive' : ''}>{value}</strong></div></div>)}</div></header>
    <form className="ref-filters ref-collateral-filters" onSubmit={event => { event.preventDefault(); setFilters(draft) }}>{[['borrower', 'Borrower', 'All borrowers'], ['loan', 'Loan', 'All loans'], ['type', 'Collateral type', 'All types'], ['security', 'Security', 'All securities'], ['settlement', 'Status', 'All statuses']].map(([key, label, placeholder]) => <label key={key}>{label}<select aria-label={label} value={draft[key]} onChange={event => setDraft(current => ({ ...current, [key]: event.target.value }))}><option value="">{placeholder}</option>{[...new Set(designCollateral.map(row => row[key]))].map(value => <option key={value}>{value}</option>)}</select></label>)}<button className="ref-clear" type="button" onClick={() => { setDraft(allFilters); setFilters(allFilters) }}>Clear</button><button className="ref-primary" type="submit">Apply</button></form>
    <div className="ref-scroll"><table className="ref-main-table ref-collateral-table"><thead><tr><th aria-label="Expand collateral"/>{columns.map(([key, label]) => sortHeading(key, label))}</tr></thead><tbody>{sorted.map(row => <Fragment key={row.id}><tr className={expanded === row.id ? 'ref-selected' : ''}><td><button className="ref-expander" aria-label={`${expanded === row.id ? 'Collapse' : 'Expand'} ${row.id}`} aria-expanded={expanded === row.id} onClick={() => setExpanded(current => current === row.id ? null : row.id)}>{expanded === row.id ? '⌄' : '›'}</button></td><td><button className="ref-id" onClick={() => setExpanded(current => current === row.id ? null : row.id)}>{row.id}</button></td><td><Link to={`/loans/${row.loan}`}>{row.loan}</Link></td><td>{row.borrower}</td><td>{row.type}</td><td>{row.security}</td><td className="ref-num">{row.quantity.toLocaleString('en-US')}</td><td className="ref-num">{money(row.price, row.type === 'Cash' ? 4 : 2)}</td><td className="ref-num">{money(row.marketValue)}</td><td className="ref-num">{row.haircut.toFixed(1)}%</td><td className="ref-num">{money(row.adjusted)}</td><td className="ref-num">{money(row.required)}</td><td className="ref-num">{row.ratio < 100 ? <Badge tone={row.ratio < 95 ? 'risk' : 'warn'}>⚠ {row.ratio.toFixed(1)}%</Badge> : `${row.ratio.toFixed(1)}%`}</td><td><Badge tone={row.settlement === 'Pending' ? 'purple' : 'good'}>{row.settlement}</Badge></td><td>{row.valuation}</td></tr>{expanded === row.id && <tr className="ref-child"><td colSpan={15}><CollateralDetails row={row}/></td></tr>}</Fragment>)}{sorted.length === 0 && <tr><td colSpan={15} className="ref-empty">No collateral positions match your filters.</td></tr>}</tbody></table></div>
  </section>
}

export default function CollateralPage(){const {mode}=useDataSource();return mode==='live'?<LiveCollateralPage/>:<DemoCollateralPage/>}
