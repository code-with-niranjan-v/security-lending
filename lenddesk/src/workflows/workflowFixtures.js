export const sourceAvailability = { 'DTC-A': 150000, 'DTC-B': 50000, 'DTC-C': 300000 }
export const demoLocate = {
  borrower: 'Everest Capital', msla: 'MSLA-00124', security: 'NVDA', requested: 250000, available: 500000,
  decision: 'Partial approve', approved: 200000, fee: 240, feeUnit: 'bps', expiry: '2026-11-13T16:00', lot: 10000,
  settlement: '2026-11-13', cycle: 'T+1', coverage: 105, collateral: ['Cash (USD)', 'UST (U.S. Treasuries)'], owner: 'JohnD',
  notes: 'Partial approve for 200,000 NVDA. Fee 240 bps (2.40% p.a.). Collateral 105% (Cash/UST). Settlement T+1 on 13 Nov 2026. Inventory sourced from DTC-A and DTC-B per allocations below.',
  reason: '', allocations: [{source:'DTC-A',quantity:150000},{source:'DTC-B',quantity:50000}],
}
export const demoAgreement = {
  lender:'Apex Prime Fund',borrower:'Everest Capital',reference:'MSLA-00201 (Draft)',type:'GMSLA',jurisdiction:'New York',law:'New York',currency:'USD',
  effective:'2026-11-13T00:00',expiry:'2027-11-13T00:00',floor:30,fee:240,rebate:180,dayCount:'Actual/360',billing:'Monthly',limit:25000000,settlement:'T+1',coverage:105,
  haircuts:[{asset:'Cash',haircut:0,max:100},{asset:'US Treasuries',haircut:2,max:70},{asset:'Equities',haircut:5,max:30}],
  recall:true,notice:'1 business day',partial:true,termination:'30 days',documents:[{name:'signedMSLA.pdf (version 1)',size:2516582,demo:true}],
}
export const demoBorrower = {
  name:'Everest Capital LLC',code:'EVRST01',lei:'5493001KJ8F4Y1B72N60',entity:'Hedge Fund',country:'US (United States)',tax:'US (United States)',address:'350 Park Avenue, 28th Floor, New York, NY 10022, United States',
  contacts:[{type:'Operations',name:'Laura Chen',email:'ops@everest.example',phone:'+1 212-555-0184'},{type:'Settlement',name:'Michael Reeves',email:'settlement@everest.example',phone:'+1 212-555-0191'},{type:'Risk',name:'Daniel Park',email:'risk@everest.example',phone:'+1 212-555-0199'}],
  custodian:'DTC (Depository Trust Company)',account:'EVRST-SETT-001',bank:'JPMorgan Chase (JPM)',currency:'USD',bankAccount:'****4821',connection:'Apex Prime – Prod (FIX-01)',sender:'EVRST01',target:'APLPRIME01',protocol:'FIX 4.4',limit:25000000,status:'Pending review',segments:['US Equity','ETF'],owner:'Jordan Davis',
  documents:[{name:'Everest_KYC.pdf',size:2516582,demo:true},{name:'Everest_Sanctions_Screening.pdf',size:1153434,demo:true}],
}
export const demoReturn = {
  borrower:'Everest Capital',security:'NVDA',loan:'LOAN-000006',requested:20000,outstanding:80000,recall:'None',
  action:'Accept request',accepted:20000,settlement:'2026-11-13T16:00',account:'DTC-A',reference:'RET-SET-001782',received:'',notes:'Return of 20,000 NVDA shares per borrower request. Settle via DTC-A on expected date.',reason:'',lots:['DTC-796542','DTC-796543'],
}
export const returnLots = [
 {id:'DTC-796542',quantity:12000,acquired:'Oct 14, 2026 14:32:10',cost:512,price:512},
 {id:'DTC-796543',quantity:8000,acquired:'Oct 15, 2026 10:05:27',cost:512,price:512},
]
export const demoReservation = {
  locate:'LOC-017221',borrower:'Everest Capital',security:'NVDA',msla:'MSLA-00124',approved:200000,old:100000,consumed:20000,fee:240,
  action:'Amend reservation',quantity:120000,expiry:'2026-11-13T16:00',valueDate:'2026-11-13',owner:'Lending Trading',reason:'Allocation adjustment',notes:'Increase reservation from 100,000 to 120,000 shares to support additional market demand from Everest Capital.',
  allocations:[{source:'DTC-A',quantity:90000},{source:'DTC-B',quantity:30000}],
}
