import { useState } from 'react'
import { Link, useNavigate, useOutletContext } from 'react-router-dom'
import ReferenceTable, {Badge} from '../components/reference/ReferenceTable'
import ReferenceRegister from '../components/reference/ReferenceRegister'
import { designBorrowers,borrowerMslas } from '../data/designBorrowers'
import { designAgreements } from '../data/designAgreements'
import './ReferencePages.css'
import './FinanceReferencePages.css'
const columns=[['name','Borrower'],['lei','Legal entity ID'],['status','Status'],['mslas','Active MSLAs'],['loans','Active loans'],['exposure','On-loan exposure'],['ratio','Collateral ratio'],['marginCalls','Open margin calls'],['recalls','Open recalls'],['settlements','Pending settlements'],['activity','Last activity']]
function BorrowerDetails({row}){
 if(row.name!=='Everest Capital')return <div className="ref-empty">No additional borrower details for {row.name} are included in the reference snapshot.</div>
 return <div className="borrower-reference-details"><section className="borrower-identity-strip">{[['♙','Settlement account','EVRST-SETT-001'],['⇄','FIX identity (SenderCompID)','EVRST01'],['⇄','FIX target (TargetCompID)','APLPRIME01'],['♜','Settlement bank','JPMorgan Chase (JPM)'],['▣','Account currency','USD'],['◎','Institution type','Hedge Fund']].map(([icon,label,value])=><div key={label}><span aria-hidden="true">{icon}</span><div><small>{label}</small><strong>{value}</strong></div></div>)}</section><div className="borrower-reference-cards">
 <div><Link className="borrower-view-all" to="/agreements">View all →</Link><ReferenceTable title="MSLAs (5)" headers={['MSLA ID','Status','Effective date','Expiry date']} rows={borrowerMslas.map(([id,start,end])=>[<Link to={`/agreements/${id}`}>{id}</Link>,<Badge>Active</Badge>,start,end])}/></div>
 <div><Link className="borrower-view-all" to="/loans">View all →</Link><ReferenceTable title="Active loans (14)" headers={['Loan ID','Security','Quantity','Market value','Start date']} rows={designAgreements[0].loanRows.map(([id,...cells])=>[<Link to={`/loans/${id}`}>{id}</Link>,...cells])}/></div>
 <div><Link className="borrower-view-all" to="/returns">View all →</Link><ReferenceTable title="Operational exceptions (3)" headers={['Exception type','Details','Opened','Status']} rows={[
 ['Pending settlement','$12,500,000 (T+1)','Jan 28, 2025',<Badge tone="warn">Open</Badge>],
 ['Margin call (informational)','Ratio 102.0% (monitor)','Jan 27, 2025',<Badge tone="warn">Open</Badge>],
 ['Recall (aging)','2 loans, 3.2M shares','Jan 26, 2025',<Badge tone="warn">Open</Badge>],
 ]}/></div></div></div>
}
export default function BorrowersPage(){
 const {searchTerm=''}=useOutletContext()
 const navigate=useNavigate()
 const [expanded,setExpanded]=useState(designBorrowers[0].id)
 const [status,setStatus]=useState(''),[msla,setMsla]=useState(''),[activity,setActivity]=useState(''),[range,setRange]=useState('Last 90 days')
 const rows=designBorrowers.filter(row=>(!status||row.status===status)&&(!msla||row.mslas>0)&&(!activity||(activity==='With exceptions'?row.marginCalls+row.recalls+row.settlements>0:row.marginCalls+row.recalls+row.settlements===0))&&(range!=='January 2025'||row.activity.startsWith('Jan'))&&`${row.name} ${row.lei}`.toLowerCase().includes(searchTerm.toLowerCase()))
 function cell(row,key){if(key==='status')return <Badge>{row.status}</Badge>;if(key==='exposure')return <span className="finance-number">{new Intl.NumberFormat('en-US',{style:'currency',currency:'USD',maximumFractionDigits:0}).format(row.exposure)}</span>;if(key==='ratio')return `${row.ratio.toFixed(1)}%`;return row[key]}
 return <section className="ref-page borrower-reference"><header className="ref-heading"><h1>Borrowers &amp; Counterparties</h1><span className="ref-active-count">14 institutions</span><button className="ref-primary" onClick={()=>navigate('/borrowers/new')}>Add borrower</button></header><section className="ref-filters borrower-reference-filters" aria-label="Borrower filters">
 <label>Status<select aria-label="Status" value={status} onChange={event=>setStatus(event.target.value)}><option value="">All statuses</option><option>Active</option><option>Suspended</option></select></label><label>Type<select aria-label="Type"><option>All types</option></select></label><label>Jurisdiction<select aria-label="Jurisdiction"><option>All jurisdictions</option></select></label><label>MSLA status<select aria-label="MSLA status" value={msla} onChange={event=>setMsla(event.target.value)}><option value="">All</option><option>Active</option></select></label><label>Activity<select aria-label="Activity" value={activity} onChange={event=>setActivity(event.target.value)}><option value="">All activity</option><option>With exceptions</option><option>No exceptions</option></select></label><label>Date range<select aria-label="Date range" value={range} onChange={event=>setRange(event.target.value)}><option>Last 90 days</option><option>All dates</option><option>January 2025</option></select></label></section>
 <ReferenceRegister columns={columns} rows={rows} expanded={expanded} setExpanded={setExpanded} cell={cell} details={row=><BorrowerDetails row={row}/>} className="borrower-reference-table"/>
 </section>
}
