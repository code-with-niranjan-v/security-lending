import { Fragment, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { lendDeskApi } from '../api/lendDeskApi'
import StatusBadge from '../components/StatusBadge'
import { inventory } from '../data/inventoryData'
import { useDataSource } from '../hooks/useDataSource'

const inventoryTone = (status) =>
  ({ Available: 'good', Tight: 'warn', 'Hard to borrow': 'risk', Restricted: 'neutral' }[status] ?? 'neutral')

const formatNumber = (value) => {
  if (value === null || value === undefined || value === '') return '—'
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return String(value)
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(numeric)
}

const formatMoney = (value) => {
  if (value === null || value === undefined || value === '') return '—'
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return String(value)
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(numeric)
}

const normaliseLiveInventory = (item) => {
  const total = Number(item.totalQuantity ?? item.total ?? 0)
  const onLoan = Number(item.onLoanQuantity ?? item.onLoan ?? 0)
  const reserved = Number(item.reservedQuantity ?? item.reserved ?? 0)
  const available = Number(item.availableQuantity ?? item.available ?? 0)
  const utilisation = total > 0 ? Math.round((onLoan / total) * 100) : 0
  const status =
    item.availability ??
    (available <= 0 ? 'Hard to borrow' : utilisation >= 80 ? 'Tight' : 'Available')

  return {
    id: String(item.id ?? item.securityId ?? '—'),
    symbol: item.symbol ?? '—',
    name: item.name ?? '—',
    isin: item.isin ?? '—',
    assetClass: item.assetClass ?? 'US Equity',
    total: formatNumber(total),
    onLoan: formatNumber(onLoan),
    reserved: formatNumber(reserved),
    available: formatNumber(available),
    price: formatMoney(item.marketPrice ?? 0),
    marketValue: formatMoney(item.marketValue ?? 0),
    rate: item.indicativeRateBps != null ? `${Number(item.indicativeRateBps).toLocaleString('en-US')} bps` : '—',
    utilisation,
    status,
    lots: []
  }
}

export default function InventoryPage() {
  const { searchTerm, dataMode } = useOutletContext()
  const { organizationId } = useDataSource()
  const [availability, setAvailability] = useState('All availability')
  const [selectedId, setSelectedId] = useState('INV-00074')
  const [expandedId, setExpandedId] = useState('INV-00074')
  const [liveInventory, setLiveInventory] = useState([])
  const [expandedInventory, setExpandedInventory] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [demoTab, setDemoTab] = useState('price')

  const demoTabs = [
    { key: 'price', label: 'Price chart' },
    { key: 'loans', label: 'Active loans' },
    { key: 'reservations', label: 'Reservations' },
    { key: 'locates', label: 'Locate request' },
    { key: 'movements', label: 'Inventory movements' }
  ]

  useEffect(() => {
    if (dataMode !== 'live') return undefined

    const controller = new AbortController()
    setLoading(true)
    setError('')

    lendDeskApi.inventory(organizationId, controller.signal)
      .then((rows) => {
        const items = Array.isArray(rows) ? rows : Array.isArray(rows?.content) ? rows.content : []
        setLiveInventory(items.map(normaliseLiveInventory))
      })
      .catch((requestError) => {
        if (requestError.name !== 'AbortError') setError(requestError.message)
      })
      .finally(() => setLoading(false))

    return () => controller.abort()
  }, [dataMode, organizationId])

  useEffect(() => {
    if (dataMode !== 'live' || !expandedId) {
      setExpandedInventory(null)
      return undefined
    }

    const controller = new AbortController()
    lendDeskApi.inventoryDetail(organizationId, expandedId, controller.signal)
      .then((detail) => {
        const inventoryItem = detail?.inventory ?? detail ?? {}
        const lots = Array.isArray(detail?.lots) ? detail.lots : []
        const normalized = normaliseLiveInventory(inventoryItem)
        const expandedLots = lots.map((lot) => ({
          lotId: lot.lotId ?? lot.id ?? '—',
          custodianAccount: lot.custodianAccount ?? lot.custodian ?? '—',
          settledBalance: formatNumber(lot.settledBalance ?? lot.settled ?? 0),
          onLoan: formatNumber(lot.onLoan ?? 0),
          availableToLend: formatNumber(lot.availableToLend ?? lot.available ?? 0),
          availability: lot.availability ?? normalized.status
        }))
        setExpandedInventory({ ...normalized, lots: expandedLots })
      })
      .catch((requestError) => {
        if (requestError.name !== 'AbortError') setExpandedInventory(null)
      })

    return () => controller.abort()
  }, [dataMode, expandedId, organizationId])

  const sourceRows = dataMode === 'live' ? liveInventory : inventory
  const rows = useMemo(
    () =>
      sourceRows.filter(
        (security) =>
          (availability === 'All availability' || security.status === availability) &&
          Object.values(security).join(' ').toLowerCase().includes(searchTerm.toLowerCase())
      ),
    [sourceRows, availability, searchTerm]
  )

  function selectSecurity(id) {
    setSelectedId(id)
    setExpandedId((current) => (current === id ? null : id))
  }

  const displayRows = rows.length ? rows : []
  const activeSecurity = dataMode === 'live' ? expandedInventory : (displayRows.find((security) => security.id === expandedId) ?? null)

  const renderDemoExpanded = (security) => (
    <div className="inventory-detail demo-inventory-detail">
      <div className="inventory-detail-header">
        <div className="inventory-detail-title">
          <small>Custody lots for {security.symbol}</small>
          <h3>{security.symbol}</h3>
        </div>

        <div className="inventory-detail-summary">
          <div>
            <span>Location demand</span>
            <strong>{security.locationDemand || 'High'}</strong>
          </div>
          <div>
            <span>Available</span>
            <strong>{security.available}</strong>
          </div>
          <div>
            <span>On loan</span>
            <strong>{security.onLoan}</strong>
          </div>
        </div>
      </div>

      <div className="inventory-detail-tabs">
        {demoTabs.map((tab) => (
          <button
            key={tab.key}
            type="button"
            className={demoTab === tab.key ? 'inventory-detail-tab is-active' : 'inventory-detail-tab'}
            onClick={() => setDemoTab(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className="inventory-detail-body">
  {demoTab === 'price' && (
  <div className="inventory-price-panel">
    <div className="inventory-price-header">
      <div>
        <strong>{security.symbol}</strong>
        <small>{security.name}</small>
      </div>
      <div className="inventory-price-spot">
        <span>Spot</span>
        <b>{security.price}</b>
      </div>
    </div>

    <div className="inventory-price-chart" aria-label="Price chart">
      <svg viewBox="0 0 720 180" preserveAspectRatio="none" className="inventory-price-svg" role="img">
        <defs>
          <linearGradient id="priceFill" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="rgba(109,92,231,0.20)" />
            <stop offset="100%" stopColor="rgba(109,92,231,0.02)" />
          </linearGradient>
        </defs>

        {[0, 1, 2, 3].map((line) => (
          <line
            key={line}
            x1="0"
            x2="720"
            y1={25 + line * 40}
            y2={25 + line * 40}
            stroke="rgba(109,92,231,0.12)"
            strokeWidth="1"
          />
        ))}

        <path
          d="M0,120 C55,118, 80,86, 120,110 S192,122, 240,100 S330,62, 370,82 S445,110, 500,98 S585,66, 620,84 S680,58, 720,48 L720,180 L0,180 Z"
          fill="url(#priceFill)"
        />

        <path
          d="M0,120 C55,118, 80,86, 120,110 S192,122, 240,100 S330,62, 370,82 S445,110, 500,98 S585,66, 620,84 S680,58, 720,48"
          fill="none"
          stroke="#6d5ce7"
          strokeWidth="3"
          strokeLinecap="round"
          strokeLinejoin="round"
        />

        <path
          d="M0,138 C55,140, 95,134, 145,122 S215,118, 260,128 S348,130, 410,102 S535,124, 585,118 S646,95, 720,98"
          fill="none"
          stroke="rgba(109,92,231,0.42)"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </div>

    <div className="inventory-price-labels">
      <span>Dec 29</span>
      <span>Jan 02</span>
      <span>Jan 08</span>
      <span>Jan 12</span>
      <span>Jan 18</span>
    </div>
  </div>
)}
        {demoTab === 'loans' && (
          <table className="inventory-detail-table">
            <thead>
              <tr>
                <th>Loan ID</th>
                <th>Borrower</th>
                <th>Quantity</th>
                <th>Rate</th>
                <th>Reservation ID</th>
              </tr>
            </thead>
            <tbody>
              {(security.activeLoans ?? []).map((loan) => (
                <tr key={loan.loanId}>
                  <td><a>{loan.loanId}</a></td>
                  <td>{loan.borrower}</td>
                  <td className="num">{loan.quantity}</td>
                  <td>{loan.rate}</td>
                  <td>{loan.reservationId}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {demoTab === 'reservations' && (
          <table className="inventory-detail-table">
            <thead>
              <tr>
                <th>Reservation ID</th>
                <th>Borrower</th>
                <th>Quantity</th>
                <th>Expiry</th>
              </tr>
            </thead>
            <tbody>
              {(security.reservations ?? []).map((reservation) => (
                <tr key={reservation.reservationId}>
                  <td><a>{reservation.reservationId}</a></td>
                  <td>{reservation.borrower}</td>
                  <td className="num">{reservation.quantity}</td>
                  <td>{reservation.expiry}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {demoTab === 'locates' && (
          <table className="inventory-detail-table">
            <thead>
              <tr>
                <th>Locate ID</th>
                <th>Borrower</th>
                <th>Quantity</th>
                <th>Age</th>
              </tr>
            </thead>
            <tbody>
              {(security.locateRequests ?? []).map((locate) => (
                <tr key={locate.locateId}>
                  <td><a>{locate.locateId}</a></td>
                  <td>{locate.borrower}</td>
                  <td className="num">{locate.quantity}</td>
                  <td>{locate.age}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {demoTab === 'movements' && (
          <table className="inventory-detail-table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Type</th>
                <th>Quantity</th>
                <th>Balance</th>
              </tr>
            </thead>
            <tbody>
              {(security.inventoryMovements ?? []).map((movement, index) => (
                <tr key={`${movement.date}-${index}`}>
                  <td>{movement.date}</td>
                  <td>{movement.type}</td>
                  <td className="num">{movement.quantity}</td>
                  <td className="num">{movement.balance}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )

  const renderLiveExpanded = (security) => (
    <div className="child-table inventory-child">
      <div>
        <b>Custody lots for {security.symbol}</b>
        <span>{(activeSecurity?.lots ?? security.lots ?? []).length} sources · {security.available} shares available</span>
      </div>

      {(activeSecurity?.lots ?? security.lots ?? []).length ? (
        <table>
          <thead>
            <tr>
              <th>Lot ID</th>
              <th>Custodian account</th>
              <th>Settled balance</th>
              <th>On loan</th>
              <th>Available to lend</th>
              <th>Availability</th>
            </tr>
          </thead>
          <tbody>
            {(activeSecurity?.lots ?? security.lots ?? []).map((lot) => (
              <tr key={lot.lotId ?? lot[0]}>
                <td><a>{lot.lotId ?? lot[0]}</a></td>
                <td>{lot.custodianAccount ?? lot[1]}</td>
                <td className="num"><b>{lot.settledBalance ?? lot[2]}</b></td>
                <td className="num">{lot.onLoan ?? lot[3]}</td>
                <td className="num">{lot.availableToLend ?? lot[4]}</td>
                <td>
                  <StatusBadge tone={inventoryTone(lot.availability ?? lot[5])}>
                    {lot.availability ?? lot[5]}
                  </StatusBadge>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="empty-state">No custody lots available.</div>
      )}
    </div>
  )

  return (
    <>
      <section className="heading inventory-heading">
        <h1>Lendable Inventory</h1>

        <div className="inventory-summary-cards">
          <span className="inventory-summary-card"><small>Gross inventory</small><b>$4.76B</b></span>
          <span className="inventory-summary-card"><small>Available to lend</small><b>$1.89B</b></span>
          <span className="inventory-summary-card"><small>On loan</small><b>$2.77B</b></span>
          <span className="inventory-summary-card critical"><small>Hard-to-borrow</small><b>2</b></span>
        </div>

        <button className="primary-action" type="button">Import inventory</button>
      </section>

      <section className="inventory-filters panel">
        <div>
          <label>
            Security
            <input placeholder="Search symbol, ISIN or security name" />
          </label>

          <label>
            Asset class
            <select>
              <option>All asset classes</option>
              <option>US Equity</option>
              <option>US Treasury</option>
              <option>ETF</option>
            </select>
          </label>

          <label>
            Availability
            <select value={availability} onChange={(event) => setAvailability(event.target.value)}>
              <option>All availability</option>
              <option>Available</option>
              <option>Tight</option>
              <option>Hard to borrow</option>
            </select>
          </label>

          <label>
            Custodian
            <select>
              <option>All custodians</option>
              <option>DTC 2191</option>
              <option>State Street 447</option>
              <option>BNY Mellon 008</option>
            </select>
          </label>
        </div>

        <div>
          <button className="link" type="button" onClick={() => setAvailability('All availability')}>
            Clear filters
          </button>
          <button className="outline-action" type="button">Export availability</button>
        </div>
      </section>

      <section className="inventory-workspace">
        <article className="panel inventory-register">
          <div className="panel-title">
            <h2>
              Availability register
              <small>{loading ? 'loading live data…' : `${displayRows.length} securities shown`}</small>
            </h2>
            <small>
              {error ? `Live API unavailable: ${error}` : 'Expand a security to view custody lots and lendable balances'}
            </small>
          </div>

          <div className="scroll">
            <table className={dataMode === 'live' ? 'live-mode-table' : ''}>
              <thead>
                <tr>
                  <th />
                  <th>Security</th>
                  <th>ISIN</th>
                  <th>Asset class</th>
                  <th>Total inventory</th>
                  <th>On loan</th>
                  <th>Reserved</th>
                  <th>Available</th>
                  <th>Availability</th>
                  <th>Market price</th>
                  <th>Market value</th>
                  <th>Indicative rate</th>
                  <th>Status</th>
                </tr>
              </thead>

              <tbody>
                {displayRows.map((security) => (
                  <Fragment key={security.id}>
                    <tr className={selectedId === security.id ? 'selected' : ''} onClick={() => selectSecurity(security.id)}>
                      <td>
                        <button
                          className="row-expander"
                          type="button"
                          aria-label={`${expandedId === security.id ? 'Collapse' : 'Expand'} ${security.symbol}`}
                        >
                          {expandedId === security.id ? '⌄' : '›'}
                        </button>
                      </td>

                      <td>
                        <a>{security.symbol}</a>
                        <small className="table-subline">{security.name}</small>
                      </td>

                      <td className="mono">{security.isin}</td>
                      <td>{security.assetClass}</td>
                      <td className="num">{security.total}</td>
                      <td className="num">{security.onLoan}</td>
                      <td className="num">{security.reserved}</td>
                      <td className="num"><b>{security.available}</b></td>

                      <td>
                        <div className="utilisation">
                          <span><i style={{ width: `${security.utilisation}%` }} /></span>
                          <b>{security.utilisation}%</b>
                        </div>
                      </td>

                      <td className="num">{security.price}</td>
                      <td className="num">{security.marketValue}</td>
                      <td>{security.rate}</td>
                      <td>
                        <StatusBadge tone={inventoryTone(security.status)}>{security.status}</StatusBadge>
                      </td>
                    </tr>

                    {expandedId === security.id && (
                      <tr className="child-row">
                        <td colSpan="13">
                          {dataMode === 'live' ? renderLiveExpanded(security) : renderDemoExpanded(security)}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>

          <footer>
            {dataMode === 'live'
              ? 'Live lender inventory view'
              : `Showing ${displayRows.length} of 1,284 lendable securities · Availability equals settled inventory less outstanding loans and active reservations`}
          </footer>
        </article>
      </section>
    </>
  )
}