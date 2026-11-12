import { Fragment, useEffect, useRef, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { lenderGet, lenderPost } from '../api/lendDeskApi'
import { useDataSource } from '../hooks/useDataSource'
import { useReferenceSort } from '../hooks/useReferenceSort'
import ReferenceTable, { Badge } from '../components/reference/ReferenceTable'
import './ReferencePages.css'
import './LocatesPage.css'

const number=value=>value==null?'—':Number(value).toLocaleString('en-US')
const locateNumber=id=>`LOC-${String(id).padStart(6,'0')}`
const label=value=>value?.toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase())??'—'
const tone=status=>/REJECTED|EXPIRED/.test(status)?'risk':status==='PENDING'?'purple':'good'
const time=value=>new Date(value).toLocaleString('en-GB',{timeZone:'UTC'})
const empty={connectionId:'',mslaId:'',securityId:'',status:'',expiry:''}
const columns=[['id','Locate ID'],['borrowerName','Borrower'],['agreementNumber','MSLA'],['securitySymbol','Security'],['requestedQuantity','Requested qty'],['approvedQuantity','Approved qty'],['reservedQuantity','Reserved qty'],['availableQuantity','Available qty'],['expiresAt','Expires UTC'],['status','Status']]

function LiveExpiry({expiresAt,asOf,receivedAt,now}){
 const remainingMs=Date.parse(expiresAt)-Date.parse(asOf)-(now-receivedAt),expired=remainingMs<=0
 const seconds=Math.floor(remainingMs/1000)
 const absolute=Math.abs(seconds),days=Math.floor(absolute/86400),hours=Math.floor(absolute/3600)%24,minutes=Math.floor(absolute/60)%60
 const countdown=days?`${days}d ${hours}h`:hours?`${hours}h ${minutes}m`:`${minutes}m`
 const color=seconds<=3600?'risk':seconds<=86400?'warn':'purple'
 return <span className={`locate-expiry ref-${color}`}><span aria-hidden="true">{seconds<=86400?'⚠':'◷'}</span><span>{time(expiresAt)} UTC<br/>{expired?'Expired ':''}({countdown}{expired?' ago':''})</span></span>
}

function LocateDecision({row,organizationId,onSuccess}){
 const [decision,setDecision]=useState(row.canApprove?'approve':'reject'),[quantity,setQuantity]=useState(Math.min(row.requestedQuantity,row.inventoryAvailableQuantity??0))
 const [reason,setReason]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('')
 const dialog=useRef(null),mounted=useRef(true)
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false}},[])
 async function confirm(){
  if(busy)return
  setBusy(true);setError('')
  try{
   await lenderPost(`/api/v1/organizations/${organizationId}/locates/${row.id}/${decision}`,decision==='approve'?{quantity:Number(quantity),reason:reason.trim()}:{reason:reason.trim()})
   if(mounted.current){dialog.current?.close();onSuccess(`${locateNumber(row.id)} ${decision==='approve'?'approved':'rejected'}. Approval does not reserve inventory or create a loan.`)}
  }catch(e){if(mounted.current)setError(`${e.message}. Refresh before retrying if the decision outcome is uncertain.`)}
  finally{if(mounted.current)setBusy(false)}
 }
 if(!row.canApprove&&!row.canReject)return <p className="ref-empty">This locate cannot currently be decided. Its status is {label(row.status)}.</p>
 return <form className="live-locate-decision" onSubmit={event=>{event.preventDefault();if(reason.trim())dialog.current.showModal()}}><h3>Locate decision</h3><label>Decision<select aria-label="Live locate decision" value={decision} onChange={event=>setDecision(event.target.value)}><option value="approve" disabled={!row.canApprove}>Approve / partially approve</option><option value="reject" disabled={!row.canReject}>Reject</option></select></label>{decision==='approve'&&<label>Approved quantity<input aria-label="Live approved quantity" type="number" min="1" step="1" max={Math.min(row.requestedQuantity,row.inventoryAvailableQuantity??0)} required value={quantity} onChange={event=>setQuantity(event.target.value)}/><small>Requested: {number(row.requestedQuantity)} · Inventory available: {number(row.inventoryAvailableQuantity)}</small></label>}<label>Reason<textarea aria-label="Live decision reason" required maxLength={500} value={reason} onChange={event=>setReason(event.target.value)}/></label><button className="ref-primary" type="submit" disabled={!reason.trim()}>Review decision</button><dialog ref={dialog} className="live-locate-review" onCancel={event=>{if(busy)event.preventDefault()}}><h2>Confirm live locate {decision==='approve'?'approval':'rejection'}</h2><p>{locateNumber(row.id)} · {row.borrowerName} · {row.securitySymbol}</p>{decision==='approve'&&<p>Approved quantity: <b>{number(quantity)}</b></p>}<p>Reason: {reason}</p><p>This sends a real decision to the lender service. Approval does not create a reservation or a loan.</p>{error&&<p role="alert">{error}</p>}<div><button type="button" disabled={busy} onClick={()=>dialog.current.close()}>Back</button><button className="ref-primary" type="button" disabled={busy} onClick={confirm}>{busy?'Submitting…':'Confirm live decision'}</button></div></dialog></form>
}

function LocateDetails({id,organizationId,refreshKey,managing,onSuccess,now}){
 const [state,setState]=useState({scope:'',data:null,error:'',receivedAt:0})
 const scope=`${organizationId}:${refreshKey}:${id}`
 useEffect(()=>{
  const controller=new AbortController()
  lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/locates/${id}/screen`,controller.signal)
   .then(data=>{if(!controller.signal.aborted)setState({scope,data,error:'',receivedAt:Date.now()})})
   .catch(error=>{if(!controller.signal.aborted)setState({scope,data:null,error:error.message,receivedAt:0})})
  return()=>controller.abort()
 },[id,organizationId,refreshKey,scope])
 if(state.scope!==scope)return <p className="ref-empty" role="status">Loading locate details…</p>
 if(state.error)return <p className="live-error" role="alert">Locate details unavailable: {state.error}</p>
 const data=state.data,row=data.locate,expiry=expiresAt=><LiveExpiry expiresAt={expiresAt} asOf={data.asOf} receivedAt={state.receivedAt} now={now}/>
 return <div className="locate-reference-details"><div className="locate-detail-grid"><ReferenceTable emptyMessage="No records available." title={`Reservations (${data.reservations.length})`} headers={['Reservation ID','Quantity','Remaining qty','Expiry countdown','Linked loans','Status']} rows={data.reservations.map(r=>[r.id,number(r.quantity),number(r.remainingQuantity),expiry(r.expiresAt),r.linkedLoans.map(l=>`${l.id} (${label(l.status)}, ${number(l.outstandingQuantity)} outstanding)`).join('; ')||'—',<Badge tone={tone(r.status)}>{label(r.status)}</Badge>])}/><section className="ref-detail-card locate-summary"><h2>Locate Summary — {locateNumber(id)}</h2><div>{[[['Borrower',row.borrowerName],['MSLA',row.agreementNumber],['Security',row.securitySymbol],['Requested qty',number(row.requestedQuantity)],['Approved qty',number(row.approvedQuantity)]],[['Reserved qty',number(row.reservedQuantity)],['Consumed qty',number(row.consumedQuantity)],['Unused approval',number(row.unallocatedQuantity)],['Available qty',number(row.availableQuantity)],['Inventory available',number(row.inventoryAvailableQuantity)],['Expires UTC',expiry(row.expiresAt)],['Status',label(row.status)]]].map((entries,index)=><dl key={index}>{entries.map(([name,value])=><div key={name}><dt>{name}</dt><dd>{value??'—'}</dd></div>)}</dl>)}</div></section></div>{managing&&<LocateDecision key={scope} row={row} organizationId={organizationId} onSuccess={onSuccess}/>}<ReferenceTable emptyMessage="No records available." title={`Activity Trail (${data.activityCount})`} headers={['Time UTC','Resource','Event','Reference']} rows={data.activity.map(a=>[time(a.createdAt),label(a.resourceType),label(a.type),a.reference])}/><p className="ref-empty">Only recorded activity is shown. Custody allocations, negotiated locate fees, owners and historical decision notes are not exposed by this API.</p></div>
}

export default function LiveLocatesPage(){
 const {searchTerm=''}=useOutletContext(),{organizationId,refreshKey,refresh}=useDataSource()
 const [draft,setDraft]=useState(empty),[filters,setFilters]=useState(empty),[expanded,setExpanded]=useState(null),[managing,setManaging]=useState(null),[notice,setNotice]=useState({org:'',message:''})
 const [state,setState]=useState({scope:'',data:null,error:'',receivedAt:0}),[now,setNow]=useState(()=>Date.now())
 const query=new URLSearchParams(Object.entries({...filters,q:searchTerm}).filter(([,value])=>value!=='')),queryText=query.toString(),scope=`${organizationId}:${refreshKey}:${queryText}`
 useEffect(()=>{const timer=setInterval(()=>setNow(Date.now()),1000);return()=>clearInterval(timer)},[])
 useEffect(()=>{
  const controller=new AbortController()
  async function load(){
   try{
    let data,rows=[],page=0
    do{data=await lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/locates/overview?${queryText}&page=${page}&size=200`,controller.signal);rows.push(...data.locates.content);page++}while(page<data.locates.totalPages)
    if(!controller.signal.aborted)setState({scope,data:{...data,rows:[...new Map(rows.map(row=>[row.id,row])).values()]},error:'',receivedAt:Date.now()})
   }catch(error){if(!controller.signal.aborted)setState({scope,data:null,error:error.message,receivedAt:0})}
  }
  const timer=setTimeout(load,150)
  return()=>{clearTimeout(timer);controller.abort()}
 },[organizationId,refreshKey,scope,queryText])
 const loading=state.scope!==scope,data=loading?null:state.data
 const options=data??(state.scope.startsWith(`${organizationId}:`)?state.data:null)
 const {sorted,sortHeading}=useReferenceSort(data?.rows??[])
 function success(message){setNotice({org:organizationId,message});setManaging(null);refresh()}
 return <section className="ref-page ref-locates live-locates-page"><header className="ref-heading"><h1>Locate Requests</h1><div className="locate-heading-stats"><span>{loading?'Loading…':data?`${data.pendingCount} Pending`:'Unavailable'}</span><span>{data?`${data.activeReservationCount} Active Reservations`:'—'}</span></div></header><form className="ref-filters locate-reference-filters" onSubmit={event=>{event.preventDefault();setFilters(draft)}}>{[['connectionId','Borrower','All borrowers',options?.borrowers],['mslaId','MSLA','All MSLAs',options?.agreements],['securityId','Security','All securities',options?.securities]].map(([key,title,placeholder,choices])=><label key={key}>{title}<select aria-label={title} value={draft[key]} onChange={event=>setDraft(current=>({...current,[key]:event.target.value}))}><option value="">{placeholder}</option>{choices?.map(option=><option key={option.id} value={option.id}>{option.label}</option>)}</select></label>)}<label>Request status<select aria-label="Request status" value={draft.status} onChange={event=>setDraft(current=>({...current,status:event.target.value}))}><option value="">All statuses</option>{options?.statuses.map(status=><option key={status}>{status}</option>)}</select></label><label>Expiry<select aria-label="Expiry" value={draft.expiry} onChange={event=>setDraft(current=>({...current,expiry:event.target.value}))}><option value="">All expiries</option><option value="EXPIRED">Expired</option><option value="URGENT">Within 1 hour</option><option value="UPCOMING">1–24 hours</option><option value="LATER">After 24 hours</option></select></label><button className="ref-clear" type="button" onClick={()=>{setDraft(empty);setFilters(empty)}}>Clear</button><button className="ref-primary" type="submit">Apply</button></form>{notice.org===organizationId&&notice.message&&<p role="status" className="live-locate-notice">{notice.message}</p>}{loading?<p className="ref-empty" role="status">Loading live locates…</p>:state.error?<p className="live-error" role="alert">Live locates unavailable: {state.error}</p>:<div className="ref-scroll"><table className="ref-main-table locate-reference-table"><thead><tr><th/>{columns.map(([key,title])=>sortHeading(key,title))}<th>Action</th></tr></thead><tbody>{sorted.map(row=><Fragment key={row.id}><tr className={expanded===row.id?'ref-selected':''}><td><button className="ref-expander" aria-label={`${expanded===row.id?'Collapse':'Expand'} ${locateNumber(row.id)}`} aria-expanded={expanded===row.id} onClick={()=>{setExpanded(current=>current===row.id?null:row.id);setManaging(null)}}>{expanded===row.id?'⌄':'›'}</button></td><td><button className="ref-id" onClick={()=>{setExpanded(current=>current===row.id?null:row.id);setManaging(null)}}>{locateNumber(row.id)}</button></td><td>{row.borrowerName??'—'}</td><td>{row.agreementNumber??'—'}</td><td>{row.securitySymbol??'—'}</td>{[row.requestedQuantity,row.approvedQuantity,row.reservedQuantity,row.availableQuantity].map((value,index)=><td className="ref-num" key={index}>{number(value)}</td>)}<td><LiveExpiry expiresAt={row.expiresAt} asOf={data.asOf} receivedAt={state.receivedAt} now={now}/></td><td><Badge tone={tone(row.status)}>{label(row.status)}</Badge></td><td><button className="locate-manage" aria-label={`Manage ${locateNumber(row.id)}`} onClick={()=>{setExpanded(row.id);setManaging(row.id)}}>Manage ⌄</button></td></tr>{expanded===row.id&&<tr className="ref-child"><td colSpan={12}><LocateDetails id={row.id} organizationId={organizationId} refreshKey={refreshKey} managing={managing===row.id} onSuccess={success} now={now}/></td></tr>}</Fragment>)}{sorted.length===0&&<tr><td colSpan={12} className="ref-empty">No locate requests match your filters.</td></tr>}</tbody></table></div>}</section>
}
