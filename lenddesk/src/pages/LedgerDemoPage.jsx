import { useState } from 'react'
import { Link, useOutletContext, useSearchParams } from 'react-router-dom'
import ReferenceTable from '../components/reference/ReferenceTable'
import ReferenceRegister from '../components/reference/ReferenceRegister'
import { FinanceState, ReferenceMetrics, ReferenceFilters } from '../components/reference/ReferenceControls'
import { designLedger, ledgerAudit } from '../data/designFinance'
import './ReferencePages.css'
import './FinanceReferencePages.css'
const money=value=>new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(value)
const columns=[['timestamp','UTC Timestamp'],['id','Ledger ID'],['account','Account'],['type','Entry Type'],['debit','Debit'],['credit','Credit'],['currency','Currency'],['borrower','Borrower'],['loan','Loan ID'],['reference','Reference Type / ID'],['payment','Payment Request'],['settlement','Settlement State'],['status','Status']]
const blank={account:'',type:'',borrower:'',loan:'',settlement:''}
function JournalDetails({row}){
 if(row.id!=='JE-008771')return <div className="ref-empty">No journal lines or audit records for {row.id} are included in the reference snapshot.</div>
 return <div className="finance-details ledger-journal"><header><h2>▤  Journal Entry JE-008771</h2><FinanceState value="BALANCED"/><span>(2 lines, Δ $0.00)</span><small>Account: AAPL | Entry Type: Buy-in Cost | Settled: Jan 28, 2025 14:46:12 UTC | Payment Request: <Link to="/ledger?reference=PR-014873">PR-014873</Link></small></header><div className="ledger-detail-grid"><ReferenceTable title="▱  Journal Lines (2)" headers={['Line#','Account','Description','Debit','Credit','Currency']} rows={[[1,'AAPL','Record buy-in acquisition (BI-000732)','$418,933.33','—','USD'],[2,'AAPL','Settle buy-in acquisition (BI-000732)','—','$418,933.33','USD'],['Total','(balanced)','','$418,933.33','$418,933.33','USD']]}/><ReferenceTable title="♧  Linked Fee / Rebate / Return Records (3)" headers={['Type','Record ID','Description','Amount','Currency','State']} rows={[
 ['Fee',<Link to="/fees-rebates">FR-009441</Link>,'Buy-in slippage fee (BI-000732)','$3,200.00','USD',<FinanceState value="SETTLED"/>],
 ['Rebate',<Link to="/fees-rebates">RB-003118</Link>,'Buy-in rebate (custody)',<span className="finance-negative">($750.00)</span>,'USD',<FinanceState value="SETTLED"/>],
 ['Return',<Link to="/returns">RT-001984</Link>,'Return on buy-in (partial)',<span className="finance-negative">($2,450.00)</span>,'USD',<FinanceState value="SETTLED"/>],
 ]}/></div><ReferenceTable title="◎  Audit Trail (5)" headers={['UTC Timestamp','Event','Actor','Details']} rows={ledgerAudit.map(([time,event,actor,details])=>[time,<FinanceState value={event}/>,actor,details])}/></div>
}
export default function LedgerDemoPage(){
 const {searchTerm=''}=useOutletContext();const [params,setParams]=useSearchParams()
 const reference=params.get('reference')??''
 const [expanded,setExpanded]=useState('JE-008771')
 const [draft,setDraft]=useState(blank),[filters,setFilters]=useState(blank)
 const [range,setRange]=useState('Jan 27, 2025 – Jan 28, 2025'),[appliedRange,setAppliedRange]=useState(range)
 const rows=designLedger.filter(row=>Object.entries(filters).every(([key,value])=>!value||row[key]===value)&&Object.values(row).join(' ').toLowerCase().includes(searchTerm.toLowerCase())&&(!reference||Object.values(row).join(' ').includes(reference))&&(appliedRange!=='Jan 27, 2025'||row.timestamp.startsWith('Jan 27')))
 function cell(row,key){if(['settlement','status'].includes(key))return <FinanceState value={row[key]}/>;if(['debit','credit'].includes(key))return <span className="finance-number">{row[key]?money(row[key]):'—'}</span>;if(key==='loan')return <Link to={`/loans/${row.loan}`}>{row.loan}</Link>;if(key==='payment')return row.payment==='—'?'—':<button className="ref-id" onClick={()=>setParams({reference:row.payment})}>{row.payment}</button>;return row[key]}
 return <section className="ref-page finance-page ledger-reference"><header className="ref-heading"><h1>Settlement Ledger</h1><span className="ledger-heading-divider"/></header><ReferenceMetrics metrics={ [['▱','Total Debits','$24,106,433.09'],['▱','Total Credits','$24,106,433.09'],['⇄','Net Movement','$0.00'],['▤','Unsettled Items','38 ($1,642,091.33)']] }/>
 <ReferenceFilters fields={ [['account','Account','All accounts'],['type','Entry Type','All entry types'],['borrower','Borrower','All borrowers'],['loan','Loan','All loans'],['settlement','Settlement State','All states']] } source={designLedger} draft={draft} setDraft={setDraft} apply={()=>{setFilters(draft);setAppliedRange(range)}} clear={()=>{setDraft(blank);setFilters(blank);setRange('Jan 27, 2025 – Jan 28, 2025');setAppliedRange('Jan 27, 2025 – Jan 28, 2025');setParams({})}} extra={<label>Date Range<select aria-label="Date Range" value={range} onChange={event=>setRange(event.target.value)}><option>Jan 27, 2025 – Jan 28, 2025</option><option>Jan 27, 2025</option><option>Jan 28, 2025</option><option>All dates</option></select></label>}/>
 {reference&&<div className="finance-reference-notice">Reference: {reference} <button className="ref-clear" onClick={()=>setParams({})}>Show all entries</button></div>}
 <ReferenceRegister columns={columns} rows={rows} expanded={expanded} setExpanded={setExpanded} cell={cell} details={row=><JournalDetails row={row}/>} className="finance-ledger-table"/></section>
}
