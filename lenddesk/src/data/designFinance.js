// Demo snapshots transcribed from the lender UI design images.
export const designFees = [
  ['FEE-009312','LOAN-000008','MS01','AAPL','6.00%',200000000,33333.33,'PAY-004975','PAID','PAID'],
  ['FEE-009313','LOAN-000010','BARC01','MSFT','8.00%',500000000,88888.89,'PAY-004978','SETTLEMENT_SUBMITTED','SETTLEMENT_SUBMITTED'],
  ['FEE-009314','LOAN-000012','MS01','AAPL','7.50%',415600000,128750,'PAY-004981','—','PAYMENT_PENDING'],
  ['FEE-009315','LOAN-000015','BRC01','XOM','5.50%',400000000,61111.11,'PAY-004982','PENDING','DUE'],
  ['FEE-009316','LOAN-000021','GS01','AAPL','7.00%',300000000,58333.33,'—','—','DUE'],
  ['FEE-009317','LOAN-000022','JPM01','JPM','6.50%',750000000,133854.17,'PAY-004983','PAID','PAID'],
].map(([id,loan,borrower,security,rate,basis,accrued,payment,settlement,status])=>({id,loan,borrower,security,rate,basis,accrued,billed:accrued,payment,settlement,status,dayBasis:'ACT/360',due:'Jan 28, 2025',type:'Lending fee'}))
export const designRebates = [
  ['REB-004981','LOAN-000012','MS01','AAPL','2.00%',415600000,36400,'PAY-004982','PAID','PAID'],
  ['REB-004982','LOAN-000010','BARC01','MSFT','1.50%',500000000,20833.33,'—','PENDING','DUE'],
].map(([id,loan,borrower,security,rate,basis,accrued,payment,settlement,status])=>({id,loan,borrower,security,rate,basis,accrued,payment,settlement,status,dayBasis:'ACT/360',due:'Jan 28, 2025',type:'Rebate'}))
export const feeAccruals = [
  ['Jan 26, 2025','1','$415,600,000','0.020833%','$86,583.33','$86,583.33'],
  ['Jan 27, 2025','1','$415,600,000','0.020833%','$86,583.33','$86,583.33'],
  ['Jan 28, 2025','1','$415,600,000','0.020833%','$86,583.34','$128,750.00'],
]
export const designLedger = [
  ['Jan 28, 2025 14:10:05','JE-008771','AAPL','Buy-in Cost',418933.33,418933.33,'MS01','LOAN-000012','Buy-in / BI-000732','PR-014873','SETTLED','POSTED'],
  ['Jan 28, 2025 14:05:22','JE-008772','AAPL','Close-out of Loan',415600,0,'MS01','LOAN-000012','Close-out / CO-000561','PR-014874','SETTLED','POSTED'],
  ['Jan 28, 2025 14:05:48','JE-008773','AAPL','Buy-in P&L',3333.33,0,'MS01','LOAN-000012','P&L / PL-000933','—','SETTLED','POSTED'],
  ['Jan 28, 2025 15:00:11','JE-008774','XOM','Margin Call Settle',752416.67,752416.67,'GS01','LOAN-000015','Margin / MC-004781','PR-014875','PENDING','OPEN'],
  ['Jan 28, 2025 16:00:02','JE-008775','AAPL','Recall Fee',1500,0,'GS01','LOAN-000021','Recall / RC-000221','—','SETTLED','POSTED'],
  ['Jan 28, 2025 16:05:27','JE-008776','MSFT','Rebate',0,2250,'BARC01','LOAN-000010','Rebate / RB-003119','PR-014876','SETTLED','POSTED'],
  ['Jan 28, 2025 17:00:34','JE-008777','AAPL','Buy-in Cost',200000,200000,'GS01','LOAN-000021','Buy-in / BI-000733','PR-014877','PENDING','OPEN'],
].map(([timestamp,id,account,type,debit,credit,borrower,loan,reference,payment,settlement,status])=>({timestamp,id,account,type,debit,credit,borrower,loan,reference,payment,settlement,status,currency:'USD'}))
export const ledgerAudit = [
  ['Jan 28, 2025 14:05:09','CREATED','system','Journal entry prepared from Buy-in (BI-000732); 2 lines, expected amount $418,933.33.'],
  ['Jan 28, 2025 14:10:48','POSTED','system','Journal entry posted to ledger (JE-008771).'],
  ['Jan 28, 2025 14:29:17','PAYMENT_REQUESTED','ops.jdoe','Payment request PR-014873 created for $418,933.33 (AAPL, MS01, LOAN-000012).'],
  ['Jan 28, 2025 14:46:12','SETTLED','custody','Cash settlement confirmed (settlement ref: CS-019844).'],
  ['Jan 28, 2025 14:46:12','COMPLETED','system','Journal entry completed; state set to SETTLED.'],
]
