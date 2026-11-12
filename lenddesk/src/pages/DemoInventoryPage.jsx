import { Fragment, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { inventory, inventorySummary, nvidiaDetails } from '../data/inventoryData'
import './DemoInventoryPage.css'

const number = value => value.toLocaleString('en-US')
const money = (value, decimals = 0) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: decimals, maximumFractionDigits: decimals }).format(value)
const columns = [['symbol', 'Symbol'], ['isin', 'ISIN'], ['name', 'Security name'], ['assetClass', 'Asset class'], ['total', 'Total quantity'], ['onLoan', 'Lent quantity'], ['reserved', 'Reserved quantity'], ['available', 'Available quantity'], ['price', 'Market price'], ['marketValue', 'Market value'], ['utilisation', 'Utilization %'], ['demand', 'Locate demand'], ['status', 'Status']]

function DetailTable({ title, headers, rows, identifiers = false }) {
  return <section className="design-detail-section"><h2>{title} ({rows.length})</h2><table><thead><tr>{headers.map(label => <th key={label}>{label}<span className="design-sort-mark">♢</span></th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={index}>{row.map((value, cell) => <td key={cell} className={identifiers && cell === 0 ? 'design-id' : ''}>{value}</td>)}</tr>)}</tbody></table></section>
}

function NvidiaDetails() {
  return <div className="design-inventory-details"><section className="design-price-section">
    <h2>NVDA — 30-day price / valuation</h2><div className="design-chart-legend"><span>Price (USD)</span><span>Valuation (USD, M)</span></div>
    <svg viewBox="0 0 270 105" role="img" aria-label="NVIDIA demo price and valuation from December 29 to January 28; ending at $128.18 and $160.23 million">
      <defs><linearGradient id="inventory-demo-fill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#a69aff" stopOpacity=".28"/><stop offset="100%" stopColor="#a69aff" stopOpacity="0"/></linearGradient></defs>
      <path d="M2 83 L9 81 L16 83 L23 79 L30 80 L37 75 L44 77 L51 72 L58 73 L65 66 L72 68 L79 66 L86 67 L93 64 L100 67 L107 61 L114 63 L121 57 L128 61 L135 59 L142 50 L149 48 L156 44 L163 44 L170 49 L177 43 L184 45 L191 39 L198 33 L205 26 L212 23 L212 103 L2 103Z" fill="url(#inventory-demo-fill)"/>
      <path d="M2 83 L9 81 L16 83 L23 79 L30 80 L37 75 L44 77 L51 72 L58 73 L65 66 L72 68 L79 66 L86 67 L93 64 L100 67 L107 61 L114 63 L121 57 L128 61 L135 59 L142 50 L149 48 L156 44 L163 44 L170 49 L177 43 L184 45 L191 39 L198 33 L205 26 L212 23" fill="none" stroke="#3021ff" strokeWidth="2"/>
      <path d="M2 88 L9 86 L16 88 L23 84 L30 86 L37 83 L44 84 L51 80 L58 82 L65 77 L72 79 L79 77 L86 78 L93 76 L100 78 L107 74 L114 76 L121 71 L128 74 L135 72 L142 66 L149 64 L156 61 L163 61 L170 65 L177 60 L184 62 L191 58 L198 54 L205 48 L212 46" fill="none" stroke="#a596ff" strokeWidth="2"/>
      <circle cx="212" cy="23" r="3" fill="#3021ff"/><circle cx="212" cy="46" r="3" fill="#a596ff"/>
      <rect x="221" y="8" width="49" height="19" rx="4" fill="#ddd6ff"/><text x="245" y="21" textAnchor="middle" fontSize="11" fill="#2111cd" fontWeight="700">$128.18</text>
      <rect x="221" y="45" width="49" height="19" rx="4" fill="#e6e0ff"/><text x="245" y="58" textAnchor="middle" fontSize="10" fill="#24203c" fontWeight="700">$160.23M</text><path d="M2 102H212" stroke="#d8d0fb"/>
    </svg><div className="design-chart-dates"><span>Dec 29</span><span>Jan 28</span></div>
  </section><DetailTable title="Active loans" headers={['Loan ID', 'Borrower', 'Quantity', 'Rate']} rows={nvidiaDetails.loans} identifiers/><DetailTable title="Reservations" headers={['Reservation ID', 'Borrower', 'Quantity', 'Expiry']} rows={nvidiaDetails.reservations} identifiers/><DetailTable title="Locate requests" headers={['Locate ID', 'Borrower', 'Quantity', 'Age']} rows={nvidiaDetails.locates} identifiers/><DetailTable title="Inventory movements" headers={['Time', 'Type', 'Quantity', 'Balance']} rows={nvidiaDetails.movements}/></div>
}

export default function DemoInventoryPage() {
  const { searchTerm = '' } = useOutletContext()
  const [expanded, setExpanded] = useState('NVDA')
  const [search, setSearch] = useState('')
  const [asset, setAsset] = useState('All asset classes')
  const [availability, setAvailability] = useState('All')
  const [borrower, setBorrower] = useState('All borrowers')
  const [utilization, setUtilization] = useState('All')
  const [sort, setSort] = useState(null)
  const rows = useMemo(() => {
    const filtered = inventory.filter(row => {
      const text = `${row.symbol} ${row.isin} ${row.name}`.toLowerCase()
      return text.includes(search.toLowerCase()) && text.includes(searchTerm.toLowerCase()) && (asset === 'All asset classes' || row.assetClass === asset) && (availability === 'All' || (availability === 'Available' ? row.available > 0 : row.available === 0)) && (borrower === 'All borrowers' || (row.symbol === 'NVDA' && nvidiaDetails.loans.some(loan => loan[1] === borrower))) && (utilization === 'All' || (utilization === 'Below 50%' ? row.utilisation < 50 : row.utilisation >= 50))
    })
    if (sort) filtered.sort((a, b) => (typeof a[sort.key] === 'number' ? a[sort.key] - b[sort.key] : a[sort.key].localeCompare(b[sort.key])) * sort.direction)
    return filtered
  }, [search, searchTerm, asset, availability, borrower, utilization, sort])
  const toggle = symbol => setExpanded(current => current === symbol ? null : symbol)
  return <div className="design-inventory"><header className="design-inventory-heading"><h1>Securities Inventory</h1><div>{inventorySummary.map(([label, value]) => <div key={label}><span>{label}</span><strong>{value}</strong></div>)}</div></header>
    <section className="design-inventory-filters" aria-label="Inventory filters">
      <label>Asset class<select aria-label="Asset class" value={asset} onChange={event => setAsset(event.target.value)}><option>All asset classes</option><option>Equity</option></select></label>
      <label>Availability<select aria-label="Availability" value={availability} onChange={event => setAvailability(event.target.value)}><option>All</option><option>Available</option><option>Unavailable</option></select></label>
      <label>Borrower<select aria-label="Borrower" value={borrower} onChange={event => setBorrower(event.target.value)}><option>All borrowers</option>{nvidiaDetails.loans.map(loan => <option key={loan[1]}>{loan[1]}</option>)}</select></label>
      <label>Utilization<select aria-label="Utilization" value={utilization} onChange={event => setUtilization(event.target.value)}><option>All</option><option>Below 50%</option><option>50% and above</option></select></label>
      <label className="design-security-search">Security<input aria-label="Search securities" placeholder="Search symbol, ISIN, or security name..." value={search} onChange={event => setSearch(event.target.value)}/></label>
    </section><div className="design-inventory-scroll"><table className="design-inventory-table"><colgroup>{[28, 72, 112, 196, 88, 94, 88, 104, 110, 88, 104, 85, 100, 65].map((width, index) => <col key={index} style={{ width: `${width / 1434 * 100}%` }} />)}</colgroup><thead><tr><th aria-label="Expand security"/>{columns.map(([key, label]) => <th key={key} aria-sort={sort?.key === key ? sort.direction === 1 ? 'ascending' : 'descending' : 'none'}><button type="button" onClick={() => setSort(current => ({ key, direction: current?.key === key ? -current.direction : 1 }))}>{label}<span className="design-sort-mark">{sort?.key === key ? sort.direction === 1 ? '↑' : '↓' : '♢'}</span></button></th>)}</tr></thead><tbody>{rows.map(row => <Fragment key={row.id}><tr className={expanded === row.symbol ? 'is-expanded' : ''}>
      <td><button type="button" className="design-row-expander" aria-label={`${expanded === row.symbol ? 'Collapse' : 'Expand'} ${row.symbol}`} aria-expanded={expanded === row.symbol} onClick={() => toggle(row.symbol)}>{expanded === row.symbol ? '⌄' : '›'}</button></td>
      <td><button className="design-symbol" type="button" onClick={() => toggle(row.symbol)}>{row.symbol}</button></td><td>{row.isin}</td><td>{row.name}</td><td><span className="design-equity">{row.assetClass}</span></td>
      {[row.total, row.onLoan, row.reserved, row.available].map((value, index) => <td className="design-number" key={index}>{number(value)}</td>)}<td className="design-number">{money(row.price, 2)}</td><td className="design-number">{money(row.marketValue)}</td><td className="design-number">{row.utilisation.toFixed(1)}%</td><td className="design-demand">{row.demand}</td><td><span className="design-active">{row.status}</span></td>
    </tr>{expanded === row.symbol && <tr className="design-expanded-row"><td colSpan={14}>{row.symbol === 'NVDA' ? <NvidiaDetails/> : <div className="design-no-details">No additional demo details for {row.symbol}.</div>}</td></tr>}</Fragment>)}{rows.length === 0 && <tr><td colSpan={14} className="design-no-details">No securities match your filters.</td></tr>}</tbody></table></div></div>
}
