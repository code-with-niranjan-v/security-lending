import { useState } from 'react'
import { Link, useOutletContext } from 'react-router-dom'
import ReferenceTable from '../components/reference/ReferenceTable'
import ReferenceRegister from '../components/reference/ReferenceRegister'
import { FinanceState, ReferenceMetrics, ReferenceFilters } from '../components/reference/ReferenceControls'
import { designFees, designRebates, feeAccruals } from '../data/designFinance'
import './ReferencePages.css'
import './FinanceReferencePages.css'
const money=(value,decimals=2)=>new Intl.NumberFormat('en-US',{style:'currency',currency:'USD',minimumFractionDigits:decimals,maximumFractionDigits:decimals}).format(value)
const feeColumns=[['id','Fee ID'],['loan','Loan ID'],['borrower','Borrower'],['security','Security'],['rate',<>Annual Fee<br/>Rate</>],['dayBasis','Day Basis'],['basis',<>Market Value<br/>Basis</>],['accrued',<>Accrued<br/>Amount</>],['billed','Billed Amount'],['due','Due Date'],['payment',<>Payment<br/>Request ID</>],['settlement','Settlement State'],['status','Status']]
const rebateColumns=[['id','Rebate ID'],['loan','Loan ID'],['borrower','Borrower'],['security','Security'],['rate','Rebate Rate'],['dayBasis','Day Basis'],['basis','Market Value Basis'],['accrued','Accrued Rebate'],['due','Due Date'],['payment','Payment Request ID'],['settlement','Settlement State'],['status','Status']]
const blank={borrower:'',security:'',type:'',due:''}
function cell(row,key){
 if(['settlement','status'].includes(key))return <FinanceState value={row[key]}/>
 if(key==='loan')return <Link to={`/loans/${row.loan}`}>{row.loan}</Link>
 if(key==='payment')return row.payment==='—'?'—':<Link to={`/ledger?reference=${row.payment}`}>{row.payment}</Link>
 if(['basis','accrued','billed'].includes(key))return <span className="finance-number">{money(row[key],key==='basis'?0:2)}</span>
 return row[key]
}
function FeeDetails({row}){
 if(row.id!=='FEE-009314')return <div className="ref-empty">No additional daily accruals are included for {row.id} in the reference snapshot.</div>
 return <div className="finance-details"><ReferenceTable title="▣  Daily Fee Accruals — FEE-009314" headers={['Accrual Date','Day Count','Market Value','Daily Fee Rate','Daily Accrual','Accrued Amount (Cumulative)']} rows={feeAccruals}/><ReferenceTable title="▱  Related Payment / Ledger" headers={['Entry Type','Reference ID','Amount','Entry Date (UTC)','Description','Settlement State','Status']} rows={[[ 'Payment Request',<Link to="/ledger?reference=PAY-004981">PAY-004981</Link>,'$128,750.00','Jan 28, 2025 14:11:09','Lending fee payment request for FEE-009314','—',<FinanceState value="PAYMENT_PENDING"/>]]}/></div>
}
export default function FeesRebatesDemoPage(){
 const {searchTerm=''}=useOutletContext()
 const [tab,setTab]=useState('Lending fees')
 const [expanded,setExpanded]=useState('FEE-009314')
 const [draft,setDraft]=useState(blank),[filters,setFilters]=useState(blank)
 const matches=row=>Object.entries(filters).every(([key,value])=>!value||row[key]===value)&&Object.values(row).join(' ').toLowerCase().includes(searchTerm.toLowerCase())
 const rebates=designRebates.filter(matches)
 const source=tab==='Lending fees'?designFees:tab==='Rebate payments'?designRebates.filter(row=>row.payment!=='—'):designRebates
 return <section className="ref-page finance-page fee-reference"><header className="ref-heading"><h1>Lending Fees &amp; Rebates</h1><div className="finance-tabs" role="tablist" aria-label="Fee and rebate views">{['Lending fees','Rebate accruals','Rebate payments'].map(label=><button key={label} type="button" role="tab" aria-selected={tab===label} onClick={()=>setTab(label)}>{label}</button>)}</div></header><ReferenceMetrics metrics={ [['▱','Due Fees','$128,750'],['◷','Payments Pending','$64,200'],['%','Accrued Rebate','$36,400'],['▱','Paid Rebate','$98,700']] }/>
 <ReferenceFilters fields={ [['borrower','Borrower','All borrowers'],['security','Security','All securities'],['type','Fee Type','All fee types'],['due','Due Date','All dates']] } source={[...designFees,...designRebates]} draft={draft} setDraft={setDraft} apply={()=>setFilters(draft)} clear={()=>{setDraft(blank);setFilters(blank)}}/>
 <ReferenceRegister columns={tab==='Lending fees'?feeColumns:rebateColumns} rows={source.filter(matches)} expanded={expanded} setExpanded={setExpanded} cell={cell} details={row=><FeeDetails row={row}/>} className="finance-fee-table"/>
 {tab==='Lending fees'&&<section className="finance-rebate-summary"><h2>%  Rebate Accruals (Summary)</h2><ReferenceRegister columns={rebateColumns} rows={rebates} expanded={expanded} setExpanded={setExpanded} cell={cell} details={row=><div className="ref-empty">{row.id} · {row.status} · Accrued rebate {money(row.accrued)}</div>} className="finance-rebate-table"/></section>}
 </section>
}
