import { Link, useParams } from 'react-router-dom'
import StatusBadge from '../components/StatusBadge'
import { loans } from '../data/loanData'
import { useDataSource } from '../hooks/useDataSource'
import { LiveLoanDetails } from './LiveLoansPage'

const tone = (value) => ({ Compliant: 'good', Active: 'good', 'Margin call open': 'risk', Partial: 'warn', None: 'neutral', Settled: 'good', Pending: 'warn', Open: 'warn', Recorded: 'neutral', Alert: 'risk' }[value] ?? 'neutral')

export default function LoanDetailPage() {
  const { loanId } = useParams()
  const {mode,organizationId,refreshKey}=useDataSource()
  if(mode==='live')return <LiveLoanDetails key={`${organizationId}:${refreshKey}:${loanId}`} id={Number(loanId.replace(/^LOAN-/,''))}/>
  const loan = loans.find((item) => item.id === loanId) ?? loans[0]
  return <section className="detail-page">
    <div className="detail-crumb"><Link to="/loans">‹ Active loans</Link><span>/</span><b>{loan.id}</b></div>
    <header className="detail-header"><div><small>SECURITIES LENDING CONTRACT</small><h1>{loan.id} · {loan.symbol}</h1><p><Link to={`/agreements/${loan.msla}`}>{loan.msla}</Link> · {loan.borrower} · ISIN <span className="mono">{loan.isin}</span></p></div><div><StatusBadge tone={tone(loan.status)}>{loan.status}</StatusBadge><Link className="outline-action" to="/loans/LOAN-000006/manage">Manage demo loan</Link><Link className="outline-action" to={`/recalls/${loan.id}/new`}>Create recall</Link><button className="primary-action" type="button">Record return</button></div></header>
    <section className="detail-metrics"><div><small>OUTSTANDING QUANTITY</small><b>{loan.outstanding}</b><span>{loan.symbol} shares</span></div><div><small>ON-LOAN VALUE</small><b>{loan.value}</b><span>Market price {loan.price}</span></div><div><small>COLLATERAL COVERAGE</small><b className={loan.margin === 'Compliant' ? 'coverage' : 'review-overdue'}>{loan.coverage}</b><span>Required 102.0%</span></div><div><small>LENDING FEE</small><b>{loan.rate}</b><span>Accrued daily</span></div></section>
    <section className="detail-grid"><article className="panel"><div className="panel-title"><h2>Contract terms</h2><small>Lifecycle state</small></div><table className="compact-kv"><tbody><tr><th>Borrower</th><td>{loan.borrower}</td><th>MSLA</th><td><Link to={`/agreements/${loan.msla}`}>{loan.msla}</Link></td></tr><tr><th>Start date</th><td>{loan.start}</td><th>Maturity</th><td>{loan.maturity}</td></tr><tr><th>Margin state</th><td><StatusBadge tone={tone(loan.margin)}>{loan.margin}</StatusBadge></td><th>Recall state</th><td><StatusBadge tone={tone(loan.recall)}>{loan.recall}</StatusBadge></td></tr></tbody></table></article><article className="panel"><div className="panel-title"><h2>Accrual summary</h2><small>Current period</small></div><table className="compact-kv"><tbody><tr><th>Fee accrued</th><td>$18,742.63</td><th>Daily fee</th><td>$3,741.82</td></tr><tr><th>Cash rebate accrued</th><td>$8,104.16</td><th>Settlement rule</th><td>Monthly</td></tr><tr><th>Next settlement</th><td>31 Jan 2026</td><th>Fee payment state</th><td>Accruing</td></tr></tbody></table></article></section>
    <article className="panel detail-table"><div className="panel-title"><h2>Pledged collateral</h2><small>{loan.collateralRows.length} collateral positions</small></div><div className="scroll"><table><thead><tr><th>Collateral ID</th><th>Asset</th><th>Pledged value</th><th>Price</th><th>Haircut</th><th>Adjusted value</th><th>Settlement</th></tr></thead><tbody>{loan.collateralRows.map((row) => <tr key={row[0]}><td><a>{row[0]}</a></td><td>{row[1]}</td><td className="num">{row[2]}</td><td className="num">{row[3]}</td><td className="num">{row[4]}</td><td className="num"><b>{row[5]}</b></td><td><StatusBadge tone={tone(row[6])}>{row[6]}</StatusBadge></td></tr>)}</tbody></table></div></article>
    <article className="panel detail-table"><div className="panel-title"><h2>Lifecycle events</h2><small>Most recent events</small></div><div className="scroll"><table><thead><tr><th>UTC time</th><th>Event</th><th>Detail</th><th>State</th></tr></thead><tbody>{loan.events.map((event) => <tr key={`${event[0]}-${event[1]}`}><td>{event[0]}</td><td><b>{event[1]}</b></td><td>{event[2]}</td><td><StatusBadge tone={tone(event[3])}>{event[3]}</StatusBadge></td></tr>)}</tbody></table></div></article>
  </section>
}
