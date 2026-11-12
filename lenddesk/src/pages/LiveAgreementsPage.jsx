import { useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { lenderGet } from '../api/lendDeskApi'
import { useDataSource } from '../hooks/useDataSource'
import ReferenceRegister from '../components/reference/ReferenceRegister'
import ReferenceTable, { Badge } from '../components/reference/ReferenceTable'
import './ReferencePages.css'

const label = value => value == null ? '—' : value.toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase())
const date = value => value ? new Date(value.length===10?value+'T00:00:00Z':value).toLocaleDateString('en-US',{timeZone:'UTC',month:'short',day:'numeric',year:'numeric'}) : '—'
const time = value => value ? new Date(value).toLocaleString('en-GB',{timeZone:'UTC'}) : '—'
const number = value => value==null?'—':Number(value).toLocaleString('en-US')
const money = (value,currency='INR') => value==null?'—':new Intl.NumberFormat('en-IN',{style:'currency',currency,maximumFractionDigits:2}).format(value)
const tone = value => /ACTIVE|ACCEPTED|SETTLED|PAID/.test(value??'')?'good':/REJECTED|CLOSED|EXPIRED/.test(value??'')?'risk':'warn'
const columns=[['number','Agreement ID'],['borrowerName','Borrower'],['status','Status'],['effectiveDate','Effective date'],['expiryDate','Expiry date'],['feeRateBps','Lending fee rate'],['rebateSettlementFrequency','Rebate frequency'],['coveragePercent','Collateral ratio'],['recallAllowed','Recall'],['activeLoanCount','Active loans'],['totalExposure','Exposure']]

function LiveAgreementDetails({id,organizationId,refreshKey}){
 const [tab,setTab]=useState('Collateral rules')
 const [state,setState]=useState({scope:'',detail:null,error:''})
 const scope=`${organizationId}:${id}:${refreshKey}`
 useEffect(()=>{
  const controller=new AbortController()
  lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/agreements/${id}/screen`,controller.signal)
   .then(detail=>{if(!controller.signal.aborted)setState({scope,detail,error:''})})
   .catch(error=>{if(!controller.signal.aborted)setState({scope,detail:null,error:error.message})})
  return ()=>controller.abort()
 },[organizationId,id,refreshKey,scope])
 if(state.scope!==scope)return <p className="ref-empty" role="status">Loading agreement details…</p>
 if(state.error)return <p className="live-error" role="alert">Agreement details unavailable: {state.error}</p>
 const detail=state.detail,a=detail.agreement
 const rules=<ReferenceTable emptyMessage="No records available." title={`Eligible collateral rules (${detail.collateralRules.length})`} headers={['Asset type','Security','Eligibility','Advance / Haircut','Concentration']} rows={detail.collateralRules.map(r=>[r.assetType,r.securitySymbol??'—',<Badge>{r.eligible?'Include':'Exclude'}</Badge>,`${number(r.advancePercent)}% / ${number(r.haircutPercent)}%`,r.concentrationLimitPercent==null?'Not stored':`${number(r.concentrationLimitPercent)}%`])}/>
 const loans=<ReferenceTable emptyMessage="No records available." title={`Active loans (${detail.activeLoanCount})`} headers={['Loan ID','Security','Outstanding quantity','Market value','Start date']} rows={detail.activeLoans.map(l=>[<span>{l.id}</span>,l.securitySymbol??`Security ${l.securityId}`,number(l.outstandingQuantity),<span title={l.priceAt?`Cached quote: ${time(l.priceAt)} UTC`:'Quote timestamp unavailable'}>{money(l.marketValue,a.currency)}</span>,date(l.settledAt)])}/>
 const activity=<ReferenceTable emptyMessage="No records available." title={`Recent locates / reservations (${detail.recentActivityCount})`} headers={['Time UTC','Type / ID','Security','Quantity','Status']} rows={detail.recentActivity.map(r=>[time(r.createdAt),`${label(r.type)} ${r.id}`,r.securitySymbol??`Security ${r.securityId}`,number(r.quantity),<Badge tone={tone(r.status)}>{label(r.status)}</Badge>])}/>
 return <div className="ref-agreement-details"><div className="ref-tabs" role="tablist" aria-label={`Agreement ${id} details`}>{['Terms','Collateral rules','Loans','Billing','Audit & FIX'].map(name=><button type="button" key={name} role="tab" aria-selected={tab===name} onClick={()=>setTab(name)}>{name}</button>)}</div>{tab==='Collateral rules'?<div className="ref-three-cards">{rules}{loans}{activity}</div>:tab==='Loans'?loans:tab==='Terms'?<ReferenceTable emptyMessage="No records available." title="Agreement terms" headers={['Term','Value']} rows={[
 ['Number',a.number],['Borrower',a.borrowerName??'—'],['Currency',a.currency],['Effective / expiry',`${date(a.effectiveDate)} / ${date(a.expiryDate)}`],['Credit limit',money(a.creditLimit,a.currency)],['Lending fee',`${number(a.feeRateBps)} bps`],['Rebate rate',`${number(a.rebateRateBps)} bps`],['Billing / rebate settlement',`${label(a.billingFrequency)} / ${label(a.rebateSettlementFrequency)}`],['Coverage',`${number(a.coveragePercent)}%`],['Day basis',a.dayBasis],['Recall',a.recallAllowed?'Enabled':'Disabled'],['Margin / recall notice hours',`${detail.terms.marginHours??'—'} / ${detail.terms.recallHours??'—'}`],['Jurisdiction / agreement framework','Not stored in lender service'],
 ]}/>:tab==='Billing'?<ReferenceTable emptyMessage="No records available." title={`Fee / rebate records (${detail.billingCount})`} headers={['Type / ID','Loan ID','Amount','Period','Due / payment date','Status']} rows={detail.billing.map(r=>[`${r.type} ${r.id}`,r.loanId,money(r.amount,a.currency),`${date(r.periodStart)} – ${date(r.periodEnd)}`,date(r.dueDate),<Badge tone={tone(r.status)}>{label(r.status)}</Badge>])}/>:<><ReferenceTable emptyMessage="No records available." title={`Agreement audit (${detail.auditCount})`} headers={['Time UTC','Event','Reference']} rows={detail.audit.map(r=>[time(r.createdAt),label(r.type),r.reference])}/><p className="ref-empty">FIX message details belong to the adapter and are unavailable from this API.</p></>}{detail.activeLoanCount>detail.activeLoans.length&&<p className="ref-empty">Showing the latest {detail.activeLoans.length} active loans.</p>}</div>
}
export default function LiveAgreementsPage(){
 const {searchTerm=''}=useOutletContext()
 const {organizationId,refreshKey}=useDataSource()
 const [filters,setFilters]=useState({connectionId:'',status:'',recallAllowed:'',activeOn:''})
 const [expanded,setExpanded]=useState(null)
 const [state,setState]=useState({scope:'',data:null,error:''})
 const query=new URLSearchParams(Object.entries({...filters,q:searchTerm}).filter(([,value])=>value!==''))
 const queryText=query.toString(),scope=`${organizationId}:${refreshKey}:${queryText}`
 useEffect(()=>{
  const controller=new AbortController()
  async function load(){
   try{
    let overview,rows=[],page=0
    do{
     overview=await lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/agreements/overview?${queryText}&page=${page}&size=200`,controller.signal)
     rows.push(...overview.agreements.content)
     page++
    }while(page<overview.agreements.totalPages)
    if(!controller.signal.aborted)setState({scope,data:{...overview,rows},error:''})
   }catch(error){if(!controller.signal.aborted)setState({scope,data:null,error:error.message})}
  }
  const timer=setTimeout(load,150)
  return ()=>{clearTimeout(timer);controller.abort()}
 },[organizationId,refreshKey,queryText,scope])
 const loading=state.scope!==scope
 const data=loading?null:state.data
 const update=(key,value)=>setFilters(current=>({...current,[key]:value}))
 function cell(row,key){
  if(key==='status')return <Badge tone={tone(row.status)}>{label(row.status)}</Badge>
  if(key==='effectiveDate'||key==='expiryDate')return date(row[key])
  if(key==='feeRateBps')return `${number(row[key])} bps`
  if(key==='rebateSettlementFrequency')return label(row[key])
  if(key==='coveragePercent')return `${number(row[key])}%`
  if(key==='recallAllowed')return <Badge tone={row[key]?'good':'warn'}>{row[key]?'Enabled':'Disabled'}</Badge>
  if(key==='totalExposure')return <span title={row.unpricedLoanCount?`${row.unpricedLoanCount} unpriced loans`:undefined}>{money(row[key],row.currency)}</span>
  if(key==='number')return <button type="button" className="ref-id" onClick={()=>setExpanded(current=>current===row.id?null:row.id)}>{row.number}</button>
  return row[key]??'—'
 }
 return <section className="ref-page ref-agreements live-agreements-page"><header className="ref-heading"><h1>MSLA Agreements</h1><span className="ref-active-count">{loading?'Loading…':state.error?'Unavailable':`${data?.activeAgreementCount??0} active`}</span></header><section className="ref-filters" aria-label="Live agreement filters"><label>Borrower<select aria-label="Borrower" value={filters.connectionId} onChange={event=>update('connectionId',event.target.value)}><option value="">All borrowers</option>{data?.borrowers.map(b=><option key={b.connectionId} value={b.connectionId}>{b.name} ({b.externalId})</option>)}</select></label><label>Status<select aria-label="Status" value={filters.status} onChange={event=>update('status',event.target.value)}><option value="">All statuses</option>{data?.statuses.map(status=><option key={status}>{status}</option>)}</select></label><label>Jurisdiction<select aria-label="Jurisdiction" disabled><option>Not stored</option></select></label><label>Recall enabled<select aria-label="Recall enabled" value={filters.recallAllowed} onChange={event=>update('recallAllowed',event.target.value)}><option value="">All</option><option value="true">Enabled</option><option value="false">Disabled</option></select></label><label>Active on<input aria-label="Agreement active on date" type="date" value={filters.activeOn} onChange={event=>update('activeOn',event.target.value)}/></label></section>{loading?<p className="ref-empty" role="status">Loading live agreements…</p>:state.error?<p className="live-error" role="alert">Live agreements unavailable: {state.error}</p>:<ReferenceRegister columns={columns} rows={data?.rows??[]} expanded={expanded} setExpanded={setExpanded} cell={cell} details={row=><LiveAgreementDetails id={row.id} organizationId={organizationId} refreshKey={refreshKey}/>} className="ref-agreements-table"/>}</section>
}
