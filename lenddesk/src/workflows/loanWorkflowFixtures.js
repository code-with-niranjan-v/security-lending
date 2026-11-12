// Demo reference snapshots from issue-margin-call.jpg, manage-loan.jpg and create-recall.jpg.
export const demoLoanContext = {
 borrower:'Everest Capital',security:'NVDA',agreement:'MSLA-00124',reservation:'RES-004891',outstanding:80000,price:128,original:100000,returned:20000,requiredRatio:105,
}
export const demoMarginCall = {
 ...demoLoanContext,type:'Collateral top-up',amount:452000,currency:'USD',issued:'2026-11-12T14:30',deadline:'2026-11-13T12:00',priority:'High',cause:'Market move',assignee:'James Doyle',
 currentCollateral:10300000,valuation:'12 Nov 2026 14:27 UTC',
 notes:'NVDA price moved to $128.00 (+6.7%), reducing coverage to 100.586% (below 105%). Request top-up of $452,000 in eligible collateral by 13 Nov 2026 12:00 UTC.',
 included:['Cash (USD)','U.S. Treasury (UST)'],recipients:['Operations','Risk'],channel:'FIX + portal',
}
export const topUpCollateral = [
 {type:'Cash (USD)',haircut:0,available:25000000},
 {type:'U.S. Treasury (UST)',haircut:2,available:50000000},
 {type:'Equities (IG)',haircut:5,available:20000000},
]
export const marginCallHistory = [
 ['MC-00023','Collateral top-up','$1,200,000','05 Nov 2026 14:30','06 Nov 2026 12:00','Fulfilled'],
 ['MC-00022','Collateral top-up','$750,000','29 Oct 2026 15:10','30 Oct 2026 12:00','Fulfilled'],
 ['MC-00021','Collateral top-up','$500,000','21 Oct 2026 14:05','22 Oct 2026 12:00','Fulfilled'],
]
export const demoLoanAmendment = {
 ...demoLoanContext,action:'Amend terms',fee:240,feeUnit:'bps',rebate:180,rebateUnit:'bps',maturity:'2026-11-20',currentFee:230,currentRebate:180,currentMaturity:'2026-11-18',billing:'Monthly',currentBilling:'Monthly',dayBasis:'Actual / 360',currentDayBasis:'Actual / 360',effective:'2026-11-13',reference:'SET-000248',reason:'Market demand / fee update',notes:'Increase fee to 240 bps and rebate to 180 bps due to higher demand in NVDA.',
}
export const loanCollateral = [
 {type:'Cash',description:'USD cash (TTM)',eligible:6000000,haircut:0,value:6000000},
 {type:'UST',description:'UST 2.50% 15 May 2027',eligible:5000000,haircut:5,value:4752000},
]
export const loanWorkflowEvents = [
 ['Loan initiated','System','02 Jan 2024 14:03:11'],
 ['Loan confirmed','EverestCapital','02 Jan 2024 14:07:22'],
 ['Partial return (20,000 shs)','EverestCapital','10 Jun 2025 16:24:58'],
 ['Amendment (draft)','JD (Lender)','13 Nov 2026 14:27:31'],
]
export const demoRecall = {
 ...demoLoanContext,type:'Partial',quantity:30000,reason:'Portfolio sale',issued:'2026-11-12T14:30',returnBy:'2026-11-13T16:00',notice:'1 business day',settlement:'DTC-A',owner:'Apex Prime Fund',
 message:'Please be advised that we are issuing a partial recall for 30,000 shares of NVDA (LOAN-000006) for a portfolio sale. Please return the securities by 13 Nov 2026 16:00 UTC via DTC-A.',
 notes:'Recall to source NVDA for planned sale on 16 Nov. Monitor for borrower acknowledgment and return.',
}
