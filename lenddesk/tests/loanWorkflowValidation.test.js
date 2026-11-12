import test from 'node:test'
import assert from 'node:assert/strict'
import { demoMarginCall,demoLoanAmendment,demoRecall } from '../src/workflows/loanWorkflowFixtures.js'
import { marginProjection,amendmentProjection,recallProjection,addBusinessDaysUtc } from '../src/workflows/loanWorkflowCalculations.js'
import { workflowErrors } from '../src/workflows/workflowValidation.js'
const context={now:Date.parse('2026-11-12T10:00:00Z')}
const check=(kind,data)=>workflowErrors(kind,structuredClone(data),context)
test('all three new fixture workflows pass local validation',()=>{
 for(const [kind,data] of [['margin',demoMarginCall],['loan',demoLoanAmendment],['recall',demoRecall]])assert.deepEqual(check(kind,data),[],kind)
})
test('margin amount restores 105% coverage and cannot underfund the shortfall',()=>{
 const projection=marginProjection(demoMarginCall)
 assert.equal(projection.shortfall,452000)
 assert.equal(projection.required,10752000)
 assert.equal(projection.afterCoverage,105)
 assert.ok(check('margin',{...demoMarginCall,amount:451999}).some(error=>error.includes('shortfall')))
 assert.ok(check('margin',{...demoMarginCall,amount:90000000}).some(error=>error.includes('eligible')))
})
test('margin calls require eligible collateral, recipients and an ordered future deadline',()=>{
 assert.ok(check('margin',{...demoMarginCall,included:[]}).some(error=>error.includes('Include')))
 assert.ok(check('margin',{...demoMarginCall,recipients:[]}).some(error=>error.includes('recipient')))
 assert.ok(check('margin',{...demoMarginCall,deadline:'2026-11-12T14:00'}).some(error=>error.includes('deadline')))
})
test('loan daily fee, fee unit conversion and maturity change match the reference',()=>{
 const projection=amendmentProjection(demoLoanAmendment)
 assert.equal(projection.coverage,105)
 assert.equal(projection.feeDelta,10)
 assert.equal(projection.maturityDelta,2)
 assert.ok(Math.abs(projection.dailyFee-682.6666666667)<0.0001)
 assert.equal(amendmentProjection({...demoLoanAmendment,fee:2.4,feeUnit:'%'}).fee,240)
})
test('maturity-only changes preserve current economics and must extend the current date',()=>{
 const projection=amendmentProjection({...demoLoanAmendment,action:'Extend maturity',fee:999,rebate:999})
 assert.equal(projection.fee,230)
 assert.equal(projection.rebate,180)
 assert.ok(check('loan',{...demoLoanAmendment,action:'Extend maturity',maturity:'2026-11-18'}).some(error=>error.includes('later than')))
 assert.ok(check('loan',{...demoLoanAmendment,effective:'2026-11-21'}).some(error=>error.includes('maturity')))
 assert.ok(check('loan',{...demoLoanAmendment,effective:'2026-11-11'}).some(error=>error.includes('before today')))
})
test('recall projection caps quantity and leaves collateral release indicative',()=>{
 const projection=recallProjection(demoRecall)
 assert.equal(projection.quantity,30000)
 assert.equal(projection.remaining,50000)
 assert.equal(projection.marketValue,3840000)
 assert.equal(projection.collateralRelease,4032000)
 assert.equal(recallProjection({...demoRecall,type:'Full'}).quantity,80000)
 assert.ok(check('recall',{...demoRecall,quantity:80001}).some(error=>error.includes('outstanding')))
 assert.ok(check('recall',{...demoRecall,quantity:80000}).some(error=>error.includes('Full recall')))
})
test('business-day notice skips weekends and blocks early recall deadlines',()=>{
 assert.equal(addBusinessDaysUtc('2026-11-13T14:30',1),Date.parse('2026-11-16T14:30:00Z'))
 assert.ok(check('recall',{...demoRecall,returnBy:'2026-11-13T14:00'}).some(error=>error.includes('notice')))
 assert.deepEqual(check('recall',{...demoRecall,type:'Full'}),[])
})
