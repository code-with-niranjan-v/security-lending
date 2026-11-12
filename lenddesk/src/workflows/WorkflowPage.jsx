import { useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { demoLocate,demoAgreement,demoBorrower,demoReturn,demoReservation,sourceAvailability,returnLots } from './workflowFixtures'
import { workflowErrors } from './workflowValidation'
import { useWorkflowDraft } from './useWorkflowDraft'
import LocateWorkflow from './LocateWorkflow'
import AgreementWorkflow from './AgreementWorkflow'
import BorrowerWorkflow from './BorrowerWorkflow'
import ReturnWorkflow from './ReturnWorkflow'
import ReservationWorkflow from './ReservationWorkflow'
import MarginCallWorkflow from './MarginCallWorkflow'
import LoanAmendmentWorkflow from './LoanAmendmentWorkflow'
import RecallWorkflow from './RecallWorkflow'
import { demoMarginCall,demoLoanAmendment,demoRecall } from './loanWorkflowFixtures'
import { marginProjection,amendmentProjection,recallProjection,money,shares } from './loanWorkflowCalculations'
import './WorkflowPage.css'
const config={
 margin:{id:'LOAN-000006',section:'Margin Calls',back:'/margin-calls',title:'Issue margin call',initial:demoMarginCall,component:MarginCallWorkflow,review:'Review margin call',status:'Margin call reviewed locally (demo)',hint:'Review coverage impact, collateral eligibility and borrower notification before sending.'},
 loan:{id:'LOAN-000006',section:'Loans',back:'/loans',title:'Manage loan',initial:demoLoanAmendment,component:LoanAmendmentWorkflow,review:'Review loan amendment',status:'Loan amendment pending counterparty acknowledgment (demo)',hint:'Confirm collateral coverage and review amended economics and maturity. Terms remain proposed until borrower acknowledgment.'},
 recall:{id:'LOAN-000006',section:'Recalls',back:'/recalls',title:'Create recall',initial:demoRecall,component:RecallWorkflow,review:'Review recall',status:'Recall reviewed locally (demo)',hint:'Issuing a recall does not confirm a return or release collateral. Review quantities, contractual notice and communication.'},
 locate:{id:'LOC-017221',section:'Locates',back:'/locates',title:'Manage locate',initial:demoLocate,component:LocateWorkflow,review:'Review locate approval',status:'Locate decision reviewed locally',hint:'Approval does not create a loan. Review terms, allocations and policy checks.'},
 agreement:{id:'MSLA-00201',section:'Agreements',back:'/agreements',title:'New MSLA agreement',initial:demoAgreement,component:AgreementWorkflow,review:'Submit for legal review',status:'Agreement submitted for legal review (demo)',hint:'This agreement will be submitted to Legal for review. It will not become active until approved by Legal.'},
 borrower:{id:'EVRST01',section:'Borrowers',back:'/borrowers',title:'Add borrower',initial:demoBorrower,component:BorrowerWorkflow,review:'Submit borrower for approval',status:'Borrower submitted for approval (demo)',hint:'Verify legal identity, KYC/sanctions, settlement setup and connectivity before submitting for approval.'},
 return:{id:'RET-001782',section:'Returns',back:'/returns',title:'Manage return request',initial:demoReturn,component:ReturnWorkflow,review:'Review return acceptance',status:'Return request reviewed locally',hint:'Review the request details, lots and projected collateral impact. Acceptance does not confirm receipt or release collateral.'},
 reservation:{id:'RES-004891',section:'Reservations',back:'/reservations',title:'Manage reservation',initial:demoReservation,component:ReservationWorkflow,review:'Review reservation amendment',status:'Reservation change reviewed locally',hint:'Review the reserved quantity, expiry and allocation sources. Conversion to a loan requires a separate collateral confirmation.'},
}
export default function WorkflowPage({kind}){
 const params=useParams()
 const settings=config[kind]
 const id=params.locateId??params.returnId??params.reservationId??params.loanId??settings.id
 return <WorkflowEditor key={`${kind}:${id}`} kind={kind} id={id} settings={settings}/>
}
function WorkflowEditor({kind,id,settings}){
 const navigate=useNavigate(),dialog=useRef(null),form=useRef(null)
 const {data,setData,notice,setNotice,save}=useWorkflowDraft(kind,id,settings.initial)
 const [errors,setErrors]=useState([])
 const [submitted,setSubmitted]=useState(false)
 const supported=id===settings.id
 const FormFields=settings.component
 const reviewLabel=kind==='locate'&&data.decision==='Reject'?'Review locate rejection':kind==='return'&&data.action==='Reject request'?'Review return rejection':kind==='reservation'?`Review reservation ${data.action==='Release'?'release':data.action==='Extend expiry'?'extension':'amendment'}`:settings.review
 function review(event){
   event.preventDefault()
   const nextErrors=workflowErrors(kind,data,{sourceAvailability,returnLots})
   setErrors(nextErrors);setNotice('')
   if(nextErrors.length){requestAnimationFrame(()=>document.getElementById('workflow-errors')?.focus());return}
   dialog.current.showModal()
 }
 function confirm(){
   const nextErrors=workflowErrors(kind,data,{sourceAvailability,returnLots})
   if(nextErrors.length){setErrors(nextErrors);dialog.current.close();return}
   const status=kind==='locate'?`Locate ${data.decision==='Reject'?'rejection':'approval'} reviewed (demo)`:kind==='return'?`Return ${data.action==='Reject request'?'rejection':'acceptance'} reviewed (demo)`:settings.status
   if(save(status)){setSubmitted(true);dialog.current.close()}
 }
 return <section className={`wf-page wf-${kind}`}><div className="wf-breadcrumb"><Link to={settings.back}>{settings.section}</Link><span>›</span>{!['agreement','borrower'].includes(kind)&&<><span>{id}</span><span>›</span></>}<span>{settings.title}</span></div><header className="wf-heading"><h1>{settings.title}{!['agreement','borrower'].includes(kind)?` · ${id}`:''}</h1><span className="wf-demo-tag">DEMO</span>{kind==='loan'&&<div className="wf-header-actions"><Link to={`/recalls/${id}/new`}>Create recall</Link><Link to="/returns">View returns</Link></div>}{kind==='agreement'&&<small>Agreement reference: <b>{data.reference}</b></small>}</header>
 {!supported?<div className="wf-card wf-unavailable"><h2>No demo workflow for {id}</h2><p>The supplied design contains a workflow for {settings.id}.</p><Link to={settings.back}>Back to {settings.section.toLowerCase()}</Link></div>:<form ref={form} onSubmit={review}>
 {notice&&<div className="wf-notice" role="status">{notice}</div>}
 {errors.length>0&&<div className="wf-errors" id="workflow-errors" role="alert" tabIndex={-1}><strong>Review the following before continuing:</strong><ul>{errors.map((error,index)=><li key={index}>{error}</li>)}</ul></div>}
 {submitted&&<div className="wf-notice">Local review complete. Live contract terms, legal approval, borrower onboarding, settlement, and collateral movements have not been changed.</div>}
 <FormFields data={data} setData={next=>{setSubmitted(false);setData(next)}} id={id}/>
 <footer className="wf-footer"><p>ⓘ <b>Reviewer hint:</b> {settings.hint}<small>Local demo only · No backend request or financial action.</small></p><div>{kind==='reservation'&&<button type="button" onClick={()=>setNotice('Conversion requires confirmed collateral and a separate loan request. No loan was created.')}>Convert remaining to loan</button>}<button type="button" onClick={()=>navigate(settings.back)}>Cancel</button>{kind==='return'?<button type="button" onClick={()=>setNotice('Correction requested locally (demo). No notification was sent to the borrower.')}>Request correction</button>:<button type="button" onClick={()=>save()}>Save draft</button>}<button className="wf-primary" type="submit">{reviewLabel}</button></div></footer>
 <dialog ref={dialog} className="wf-review-dialog"><h2>{reviewLabel}</h2><p>Review these terms before recording this demo decision. No backend submission, financial transaction, document upload, or external notification will occur.</p><dl className="wf-review-summary"><div><dt>Reference</dt><dd>{id}</dd></div><div><dt>Borrower</dt><dd>{data.borrower??data.name}</dd></div>{(data.security)&&<div><dt>Security</dt><dd>{data.security}</dd></div>}{data.approved!=null&&kind==='locate'&&<div><dt>Approved quantity</dt><dd>{data.decision==='Reject'?0:Number(data.approved).toLocaleString('en-US')}</dd></div>}{kind==='return'&&<div><dt>Return decision</dt><dd>{data.action} · {data.action==='Reject request'?0:Number(data.accepted).toLocaleString('en-US')} shares</dd></div>}{kind==='reservation'&&<div><dt>Reservation action</dt><dd>{data.action} · {Number(data.action==='Release'?data.consumed:data.action==='Extend expiry'?data.old:data.quantity).toLocaleString('en-US')} shares total</dd></div>}{kind==='margin'&&<><div><dt>Requested top-up</dt><dd>{money(data.amount)}</dd></div><div><dt>After top-up coverage</dt><dd>{marginProjection(data).afterCoverage.toFixed(3)}%</dd></div><div><dt>Response deadline UTC</dt><dd>{data.deadline.replace('T',' ')}</dd></div><div><dt>Recipients / channel</dt><dd>{data.recipients.join(', ')} / {data.channel}</dd></div></>}{kind==='loan'&&<><div><dt>Proposed annual fee / rebate</dt><dd>{amendmentProjection(data).fee} / {amendmentProjection(data).rebate} bps</dd></div><div><dt>Effective / maturity UTC</dt><dd>{data.effective} / {data.maturity}</dd></div><div><dt>Billing / day basis</dt><dd>{data.action==='Extend maturity'?data.currentBilling:data.billing} / {amendmentProjection(data).dayBasis}</dd></div><div><dt>Status after review</dt><dd>Pending counterparty acknowledgment (demo)</dd></div></>}{kind==='recall'&&<><div><dt>Recall type / quantity</dt><dd>{data.type} / {shares(recallProjection(data).quantity)} shares</dd></div><div><dt>Return by UTC</dt><dd>{data.returnBy.replace('T',' ')}</dd></div><div><dt>Estimated collateral release</dt><dd>{money(recallProjection(data).collateralRelease,2)} (after settlement only)</dd></div><div><dt>Borrower notification</dt><dd>{data.message}</dd></div></>}{kind==='agreement'&&<><div><dt>Negotiated fee</dt><dd>{data.fee} bps</dd></div><div><dt>Coverage</dt><dd>{data.coverage}%</dd></div></>}{kind==='borrower'&&<><div><dt>LEI</dt><dd>{data.lei}</dd></div><div><dt>Onboarding state</dt><dd>Pending review</dd></div></>}</dl><div className="wf-dialog-actions"><button type="button" onClick={()=>dialog.current.close()}>Back to editing</button><button className="wf-primary" type="button" onClick={confirm}>Confirm demo review</button></div></dialog>
 </form>}</section>
}
