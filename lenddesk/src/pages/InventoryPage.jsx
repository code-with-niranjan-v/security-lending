import { Fragment, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { lenderGet, lendDeskApi } from '../api/lendDeskApi'
import StatusBadge from '../components/StatusBadge'
import { inventory } from '../data/inventoryData'
import { loans } from '../data/loanData'
import { reservations } from '../data/reservationData'
import { locateRequests } from '../data/locateData'
import { useDataSource } from '../hooks/useDataSource'
import './InventoryPage.css'

const RESOURCES = ['inventory', 'loans', 'reservations', 'locates', 'borrowers', 'agreements', 'inventory-history']
const DEMO = { inventory, loans, reservations, locates: locateRequests, borrowers: [], agreements: [], 'inventory-history': [] }
const COLUMNS = [
  ['Symbol', 'symbol'], ['ISIN', 'isin'], ['Security name', 'name'], ['Asset class', 'assetClass'],
  ['Total quantity', 'total'], ['Lent quantity', 'lent'], ['Reserved quantity', 'reserved'],
  ['Available quantity', 'available'], ['Market price', 'price'], ['Market value', 'marketValue'],
  ['Utilisation %', 'utilisation'], ['Locate demand', 'demand'], ['Status', 'status']
]
const PAGE_SIZE = 20
const nf = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 })

function numeric(value) {
  if (value == null || value === '') return null
  const number = Number(typeof value === 'string' ? value.replace(/[$₹,%\s,]/g, '') : value)
  return Number.isFinite(number) ? number : null
}

function quantity(value) {
  const number = numeric(value)
  return number === null ? '—' : nf.format(number)
}

function money(value, live) {
  const number = numeric(value)
  if (number === null) return '—'
  return new Intl.NumberFormat(live ? 'en-IN' : 'en-US', {
    style: 'currency', currency: live ? 'INR' : 'USD', maximumFractionDigits: 2
  }).format(number)
}

function time(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('en-GB', {
    day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit'
  })
}

async function loadAll(org, resource, signal) {
  const rows = []
  let page = 0
  while (true) {
    const result = await lenderGet(`/api/v1/organizations/${org}/lenddesk/${resource}?page=${page}&size=200`, signal)
    const batch = Array.isArray(result) ? result : result?.content
    if (!Array.isArray(batch)) throw new Error(`Unexpected ${resource} response`)
    rows.push(...batch)
    const finished = Number.isInteger(result?.totalPages)
      ? page + 1 >= result.totalPages
      : batch.length < 200
    if (finished || batch.length === 0) return rows
    page += 1
  }
}

function normalise(item, live) {
  const total = numeric(item.totalQuantity ?? item.total) ?? 0
  const lent = numeric(item.onLoanQuantity ?? item.loanedQuantity ?? item.onLoan) ?? 0
  const reserved = numeric(item.reservedQuantity ?? item.reserved) ?? 0
  const price = numeric(item.marketPrice ?? item.price)
  return {
    ...item,
    id: String(item.id), total, lent, reserved, price,
    available: numeric(item.availableQuantity ?? item.available) ?? Math.max(0, total - lent - reserved),
    marketValue: live ? numeric(item.marketValue) : price === null ? null : price * total,
    utilisation: numeric(item.utilisation) ?? (total ? lent / total * 100 : 0),
    status: item.availability ?? item.status ?? '—',
    demand: item.locateDemand ?? item.locationDemand ?? '—'
  }
}

function related(record, security, live) {
  return live ? String(record.securityId) === String(security.securityId) : record.symbol === security.symbol
}

function statusTone(status) {
  return {
    Available: 'good', Active: 'good', Tight: 'warn',
    'Hard to borrow': 'risk', Restricted: 'neutral'
  }[status] ?? 'neutral'
}

function SmallTable({ title, columns, rows, error }) {
  return (
    <section className="ldi-detail-panel">
      <h3>{title} <small>({error ? '—' : rows.length})</small></h3>
      {error ? <p className="ldi-empty" role="status">{error}</p> : (
        <div className="ldi-mini-scroll">
          <table className="ldi-mini-table">
            <thead><tr>{columns.map(column => <th key={column}>{column}</th>)}</tr></thead>
            <tbody>
              {rows.map((row, index) => (
                <tr key={`${row[0]}-${index}`}>
                  {row.map((cell, column) => <td key={column}>{cell ?? '—'}</td>)}
                </tr>
              ))}
              {!rows.length && <tr><td colSpan={columns.length} className="ldi-empty">No records for this security.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function ExpandedInventory({ security, live, org, data, errors }) {
  const [detail, setDetail] = useState(null)
  useEffect(() => {
    if (!live) return undefined
    const controller = new AbortController()
    Promise.allSettled([
      lenderGet(`/api/v1/securities/${security.securityId}/history`, controller.signal),
      lendDeskApi.inventoryDetail(org, security.id, controller.signal)
    ]).then(([history, position]) => {
      if (controller.signal.aborted) return
      setDetail({
        prices: history.status === 'fulfilled' && Array.isArray(history.value) ? history.value : [],
        error: history.status === 'rejected' ? history.reason.message : '',
        lots: position.status === 'fulfilled' ? position.value?.lots ?? [] : [],
        lotError: position.status === 'rejected' ? position.reason.message : ''
      })
    })
    return () => controller.abort()
  }, [live, org, security.id, security.securityId])

  const select = resource => (data[resource] ?? []).filter(row => related(row, security, live))
  const borrower = row => live
    ? data.borrowers?.find(item => String(item.id) === String(row.connectionId))?.name ?? `Connection ${row.connectionId}`
    : row.borrower
  const feeRate = row => {
    if (!live) return row.rate ?? '—'
    const agreement = data.agreements?.find(item => String(item.id) === String(row.mslaId))
    return agreement?.feeRate == null ? '—' : `${(Number(agreement.feeRate) * 100).toFixed(2)}%`
  }
  const activeLoans = select('loans').filter(row => String(row.status).toUpperCase() === 'ACTIVE')
  const movements = live
    ? (data['inventory-history'] ?? []).filter(row => String(row.inventoryId) === security.id)
    : security.inventoryMovements ?? []
  const prices = live
    ? (detail?.prices ?? []).filter(row => numeric(row.close) !== null && Number.isFinite(Date.parse(row.tradedDateTimestamp)))
      .sort((a, b) => Date.parse(a.tradedDateTimestamp) - Date.parse(b.tradedDateTimestamp)).slice(-120).map(row => Number(row.close))
    : security.price === null ? [] : [0.91, 0.918, 0.914, 0.925, 0.94, 0.934, 0.95, 0.963, 0.958, 0.976, 0.971, 0.988, 1].map(ratio => security.price * ratio)
  const low = prices.length ? Math.min(...prices) : 0
  const range = prices.length ? Math.max(...prices) - low || 1 : 1
  const points = prices.map((price, i) => `${8 + i / Math.max(1, prices.length - 1) * 244},${76 - (price - low) / range * 56}`).join(' ')
  const lots = live ? detail?.lots ?? [] : security.lots ?? []

  return (
    <div className="ldi-expanded">
      <div className="ldi-detail-grid">
        <section className="ldi-detail-panel ldi-chart-panel">
          <h3>{security.symbol} — price history</h3>
          <div className="ldi-chart-legend"><span>━ Price ({live ? 'INR' : 'USD'})</span><small>{live ? 'Latest 120 candles' : 'Illustrative demo series'}</small></div>
          {live && !detail ? <p className="ldi-empty">Loading price history…</p> : detail?.error ? (
            <p className="ldi-empty" role="status">{detail.error}</p>
          ) : prices.length < 2 ? <p className="ldi-empty">Insufficient price history.</p> : (
            <>
              <svg className="ldi-price-svg" viewBox="0 0 270 100" role="img" aria-label={`${security.symbol} price history`}>
                {[20, 48, 76].map(y => <line key={y} x1="8" x2="258" y1={y} y2={y} className="ldi-grid-line" />)}
                <polyline points={points} className="ldi-price-line" />
                <circle cx="252" cy={76 - (prices.at(-1) - low) / range * 56} r="3" />
              </svg>
              <div className="ldi-chart-range"><span>Low {money(low, live)}</span><strong>{money(prices.at(-1), live)}</strong></div>
            </>
          )}
        </section>
        <SmallTable title="Active loans" columns={['Loan ID', 'Borrower', 'Quantity', 'Rate']} error={errors.loans}
          rows={activeLoans.map(row => [row.id, borrower(row), quantity(row.outstandingQuantity ?? row.outstanding ?? row.quantity), feeRate(row)])} />
        <SmallTable title="Reservations" columns={['Reservation', 'Borrower', 'Quantity', 'Expiry']} error={errors.reservations}
          rows={select('reservations').map(row => [row.id, borrower(row), quantity(row.remainingQuantity ?? row.reserved ?? row.quantity), live ? time(row.expiresAt) : row.expires])} />
        <SmallTable title="Locate requests" columns={['Locate ID', 'Borrower', 'Quantity', 'Status']} error={errors.locates}
          rows={select('locates').map(row => [row.id, borrower(row), quantity(row.quantity ?? row.requested), row.status])} />
        <SmallTable title="Inventory movements" columns={['Time', 'Type', 'Quantity']} error={errors['inventory-history']}
          rows={movements.map(row => [live ? time(row.createdAt) : row.date, row.type, quantity(row.quantity)])} />
      </div>
      <details className="ldi-custody">
        <summary>Custody lots{live && !detail ? ' · loading…' : ` · ${lots.length} sources`}</summary>
        <SmallTable title="Custody balances" columns={['Lot ID', 'Custodian account', 'Settled balance', 'On loan', 'Available', 'Availability']} error={detail?.lotError}
          rows={lots.map(lot => [lot.lotId ?? lot[0], lot.custodianAccount ?? lot[1], quantity(lot.settledBalance ?? lot[2]), quantity(lot.onLoan ?? lot[3]), quantity(lot.availableToLend ?? lot[4]), lot.availability ?? lot[5]])} />
      </details>
    </div>
  )
}

export default function InventoryPage() {
  const { searchTerm = '' } = useOutletContext()
  const { mode, organizationId, refreshKey, refresh } = useDataSource()
  const live = mode === 'live'
  const scope = `${mode}:${organizationId}:${refreshKey}`
  const [request, setRequest] = useState({ scope: '', data: {}, errors: {} })
  const [filters, setFilters] = useState({ search: '', assetClass: '', availability: '', borrower: '', utilisation: '' })
  const [sort, setSort] = useState({ key: 'symbol', direction: 1 })
  const [page, setPage] = useState(0)
  const [expanded, setExpanded] = useState({ scope, id: live ? null : 'INV-00074' })

  useEffect(() => {
    if (!live) return undefined
    const controller = new AbortController()
    Promise.allSettled(RESOURCES.map(resource => loadAll(organizationId, resource, controller.signal))).then(results => {
      if (controller.signal.aborted) return
      const data = {}
      const errors = {}
      results.forEach((result, index) => {
        const resource = RESOURCES[index]
        data[resource] = result.status === 'fulfilled' ? result.value : []
        if (result.status === 'rejected') errors[resource] = result.reason.message
      })
      setRequest({ scope, data, errors })
    })
    return () => controller.abort()
  }, [live, organizationId, scope])

  const loading = live && request.scope !== scope
  const data = live ? request.scope === scope ? request.data : {} : DEMO
  const errors = live && request.scope === scope ? request.errors : {}
  const sourceRows = (data.inventory ?? []).map(row => normalise(row, live))
  const activity = [...(data.loans ?? []), ...(data.reservations ?? []), ...(data.locates ?? [])]
  const borrowers = live ? (data.borrowers ?? []).map(row => ({ id: String(row.id), name: row.name }))
    : [...new Set(activity.map(row => row.borrower).filter(Boolean))].map(name => ({ id: name, name }))
  const rows = sourceRows.filter(security => {
    const text = `${security.symbol} ${security.isin} ${security.name}`.toLowerCase()
    const borrowerMatches = !filters.borrower || activity.some(row => related(row, security, live) && String(live ? row.connectionId : row.borrower) === filters.borrower)
    const utilisationMatches = !filters.utilisation ||
      (filters.utilisation === 'high' && security.utilisation >= 80) ||
      (filters.utilisation === 'medium' && security.utilisation >= 50 && security.utilisation < 80) ||
      (filters.utilisation === 'low' && security.utilisation < 50)
    return text.includes(filters.search.trim().toLowerCase()) && text.includes(searchTerm.trim().toLowerCase()) &&
      (!filters.assetClass || security.assetClass === filters.assetClass) &&
      (!filters.availability || security.status === filters.availability) && borrowerMatches && utilisationMatches
  }).sort((a, b) => {
    const left = a[sort.key]
    const right = b[sort.key]
    if (left == null) return right == null ? 0 : 1
    if (right == null) return -1
    return (typeof left === 'number' ? left - right : String(left).localeCompare(String(right))) * sort.direction
  })
  const totals = sourceRows.reduce((result, row) => ({ total: result.total + row.total, lent: result.lent + row.lent, reserved: result.reserved + row.reserved, available: result.available + row.available }), { total: 0, lent: 0, reserved: 0, available: 0 })
  const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE))
  const currentPage = Math.min(page, pages - 1)
  const visible = rows.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE)
  const expandedId = expanded.scope === scope ? expanded.id : null

  function changeFilter(key, value) {
    setFilters(current => ({ ...current, [key]: value }))
    setPage(0)
  }
  function toggleRow(id) {
    setExpanded({ scope, id: expandedId === id ? null : id })
  }
  function sortBy(key) {
    setSort(current => ({ key, direction: current.key === key ? -current.direction : 1 }))
    setPage(0)
  }
  function exportRows() {
    const keys = COLUMNS.map(([, key]) => key)
    const escape = value => {
      const text = String(value ?? '')
      const safe = typeof value === 'string' && /^\s*[=+@-]/.test(text) ? `'${text}` : text
      return `"${safe.replaceAll('"', '""')}"`
    }
    const csv = [keys.map(escape).join(','), ...rows.map(row => keys.map(key => escape(row[key])).join(','))].join('\r\n')
    const url = URL.createObjectURL(new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'lenddesk-inventory.csv'
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  return (
    <section className="ld-inventory">
      <header className="ldi-heading">
        <div><h1>Securities Inventory</h1><small>{live ? 'Live lender inventory' : 'Demo inventory'}</small></div>
        <div className="ldi-summary">
          {[['Total inventory', totals.total], ['Lent', totals.lent], ['Reserved', totals.reserved], ['Available', totals.available]].map(([label, value]) => (
            <div key={label}><span>{label}</span><strong>{loading || errors.inventory ? '—' : quantity(value)}</strong></div>
          ))}
        </div>
      </header>
      <div className="ldi-filters">
        <label>Asset class<select value={filters.assetClass} onChange={e => changeFilter('assetClass', e.target.value)}><option value="">All asset classes</option>{[...new Set(sourceRows.map(row => row.assetClass))].map(value => <option key={value}>{value}</option>)}</select></label>
        <label>Availability<select value={filters.availability} onChange={e => changeFilter('availability', e.target.value)}><option value="">All</option>{[...new Set(sourceRows.map(row => row.status))].map(value => <option key={value}>{value}</option>)}</select></label>
        <label>Borrower<select value={filters.borrower} disabled={Boolean(errors.borrowers)} onChange={e => changeFilter('borrower', e.target.value)}><option value="">All borrowers</option>{borrowers.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}</select></label>
        <label>Utilisation<select value={filters.utilisation} onChange={e => changeFilter('utilisation', e.target.value)}><option value="">All</option><option value="high">80% and above</option><option value="medium">50%–79%</option><option value="low">Below 50%</option></select></label>
        <label className="ldi-search">Security<input value={filters.search} placeholder="Search symbol, ISIN or security name…" onChange={e => changeFilter('search', e.target.value)} /></label>
        <button type="button" className="ldi-text-button" onClick={() => { setFilters({ search: '', assetClass: '', availability: '', borrower: '', utilisation: '' }); setPage(0) }}>Clear</button>
        <button type="button" className="ldi-button" onClick={exportRows}>Export</button>
        <button type="button" className="ldi-button" disabled={loading} onClick={refresh} aria-label="Refresh inventory">↻</button>
      </div>
      {errors.inventory && <div className="ldi-error" role="alert">Could not load inventory. {errors.inventory}</div>}
      {Object.keys(errors).some(key => key !== 'inventory') && <div className="ldi-warning" role="status">Some related activity is unavailable. Expanded panels show the affected resources.</div>}
      <div className="ldi-register">
        <div className="ldi-table-scroll">
          <table className="ldi-main-table">
            <thead><tr><th className="ldi-expander-column" />{COLUMNS.map(([label, key]) => (
              <th key={key} aria-sort={sort.key === key ? sort.direction === 1 ? 'ascending' : 'descending' : 'none'}><button type="button" className="ldi-sort" onClick={() => sortBy(key)} aria-label={`Sort by ${label}`}>{label}<span aria-hidden="true">{sort.key === key ? sort.direction === 1 ? '↑' : '↓' : '↕'}</span></button></th>
            ))}</tr></thead>
            <tbody>
              {loading && <tr><td colSpan={14} className="ldi-empty">Loading inventory…</td></tr>}
              {!loading && !visible.length && <tr><td colSpan={14} className="ldi-empty">{errors.inventory ? 'Inventory is unavailable.' : 'No securities match the current filters.'}</td></tr>}
              {!loading && visible.map(security => {
                const open = expandedId === security.id
                return <Fragment key={security.id}>
                  <tr className={open ? 'ldi-security is-expanded' : 'ldi-security'} onClick={() => toggleRow(security.id)}>
                    <td><button type="button" className="ldi-expander" aria-expanded={open} aria-controls={open ? `inventory-detail-${security.id}` : undefined} aria-label={`${open ? 'Collapse' : 'Expand'} ${security.symbol}`} onClick={e => { e.stopPropagation(); toggleRow(security.id) }}>{open ? '⌄' : '›'}</button></td>
                    <td className="ldi-symbol">{security.symbol}</td><td className="ldi-isin">{security.isin}</td><td className="ldi-name" title={security.name}>{security.name}</td><td><span className="ldi-asset">{security.assetClass}</span></td>
                    <td className="ldi-number">{quantity(security.total)}</td><td className="ldi-number">{quantity(security.lent)}</td><td className="ldi-number">{quantity(security.reserved)}</td><td className="ldi-number"><strong>{quantity(security.available)}</strong></td>
                    <td className="ldi-number">{money(security.price, live)}</td><td className="ldi-number">{money(security.marketValue, live)}</td><td className="ldi-number">{security.utilisation.toFixed(1)}%</td><td className="ldi-demand">{security.demand}</td><td><StatusBadge tone={statusTone(security.status)}>{security.status}</StatusBadge></td>
                  </tr>
                  {open && <tr className="ldi-child-row"><td colSpan={14} id={`inventory-detail-${security.id}`}><ExpandedInventory key={`${scope}:${security.id}`} security={security} live={live} org={organizationId} data={data} errors={errors} /></td></tr>}
                </Fragment>
              })}
            </tbody>
          </table>
        </div>
        <footer className="ldi-footer">
          <span>{loading ? 'Loading…' : `${rows.length} matching securities · ${sourceRows.length} loaded`}</span>
          <div className="ldi-pagination"><button type="button" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>Previous</button><span>Page {currentPage + 1} of {pages}</span><button type="button" disabled={currentPage + 1 >= pages} onClick={() => setPage(currentPage + 1)}>Next</button></div>
        </footer>
      </div>
    </section>
  )
}
