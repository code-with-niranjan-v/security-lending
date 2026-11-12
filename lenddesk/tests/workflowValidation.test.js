import test from 'node:test'
import assert from 'node:assert/strict'
import { demoLocate,demoAgreement,demoBorrower,demoReturn,demoReservation,sourceAvailability,returnLots } from '../src/workflows/workflowFixtures.js'
import { workflowErrors,allocationErrors,validLeiFormat } from '../src/workflows/workflowValidation.js'
const context={now:Date.parse('2026-11-12T00:00:00Z'),sourceAvailability,returnLots}
const check=(kind,data)=>workflowErrors(kind,structuredClone(data),context)
test('reference fixtures satisfy local form rules without treating reviews as settlement',()=>{
 for(const [kind,data] of [['locate',demoLocate],['agreement',demoAgreement],['borrower',demoBorrower],['return',demoReturn],['reservation',demoReservation]])assert.deepEqual(check(kind,data),[],kind)
 assert.equal(demoReturn.received,'')
})
test('approval cannot exceed request, source inventory, or allocation totals',()=>{
 assert.ok(check('locate',{...demoLocate,approved:250001}).some(error=>error.includes('Approved quantity')))
 assert.ok(allocationErrors([{source:'DTC-A',quantity:200000}],200000,sourceAvailability).some(error=>error.includes('source availability')))
 assert.ok(allocationErrors([{source:'DTC-A',quantity:100000}],200000,sourceAvailability).some(error=>error.includes('must equal')))
 assert.ok(allocationErrors([{source:'DTC-A',quantity:100000},{source:'DTC-A',quantity:100000}],200000,sourceAvailability).some(error=>error.includes('only be used once')))
})
test('full and partial decisions have distinct quantity rules; rejection requires a reason',()=>{
 assert.ok(check('locate',{...demoLocate,decision:'Approve'}).some(error=>error.includes('full requested')))
 assert.ok(check('locate',{...demoLocate,approved:250000}).some(error=>error.includes('smaller than')))
 assert.ok(check('locate',{...demoLocate,decision:'Reject'}).some(error=>error.includes('reason')))
 assert.deepEqual(check('locate',{...demoLocate,decision:'Reject',reason:'Outside mandate'}),[])
})
test('reservation amendments retain consumed shares and cannot over-reserve',()=>{
 assert.ok(check('reservation',{...demoReservation,quantity:19999}).some(error=>error.includes('consumed')))
 assert.ok(check('reservation',{...demoReservation,quantity:200001}).some(error=>error.includes('consumed')))
 assert.deepEqual(check('reservation',{...demoReservation,action:'Release'}),[])
})
test('expired dates and insufficient return lots block review',()=>{
 assert.ok(check('locate',{...demoLocate,expiry:'2026-11-11T16:00'}).some(error=>error.includes('future')))
 assert.ok(check('reservation',{...demoReservation,expiry:'2026-11-11T16:00'}).some(error=>error.includes('future')))
 assert.ok(check('return',{...demoReturn,accepted:20001}).some(error=>error.includes('Accepted quantity')))
 assert.ok(check('return',{...demoReturn,lots:['DTC-796542']}).some(error=>error.includes('sufficient return lots')))
})
test('agreements need valid term order, fee floor and documents',()=>{
 assert.ok(check('agreement',{...demoAgreement,expiry:'2026-11-12T00:00'}).some(error=>error.includes('expiry')))
 assert.ok(check('agreement',{...demoAgreement,fee:20}).some(error=>error.includes('fee floor')))
 assert.ok(check('agreement',{...demoAgreement,documents:[]}).some(error=>error.includes('document')))
})
test('borrower identity, contact format and documentation are validated locally',()=>{
 assert.equal(validLeiFormat('bad'),false)
 assert.ok(check('borrower',{...demoBorrower,lei:'bad'}).some(error=>error.includes('LEI')))
 assert.ok(check('borrower',{...demoBorrower,contacts:[{type:'Operations',name:'A',phone:'123',email:'bad'}]}).some(error=>error.includes('contact')))
 assert.ok(check('borrower',{...demoBorrower,segments:[]}).some(error=>error.includes('segment')))
})
