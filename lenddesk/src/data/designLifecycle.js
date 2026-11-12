// Static snapshot from lender-ui-designs/lifecycle.png.
export const lifecycleReturns = [
  ['RET-000040', 'LOAN-000008', 'REC-000121', 'MS01', 'AAPL', 50000, 'Jan 28, 2025 13:58:21', 'SETTLED', 'PAID', 'PAID', 'SETTLED'],
  ['RET-000041', 'LOAN-000010', 'REC-000122', 'BARC01', 'MSFT', 1000000, 'Jan 28, 2025 14:05:33', 'PENDING', 'PAYMENT_PENDING', 'PAID', 'SETTLEMENT_SUBMITTED'],
  ['RET-000042', 'LOAN-000015', 'REC-000123', 'BRC01', 'XOM', 400000, 'Jan 28, 2025 14:12:09', 'OPEN', 'OVERDUE', 'PENDING', 'OPEN'],
  ['RET-000043', 'LOAN-000021', 'REC-000124', 'GS01', 'AAPL', 300000, 'Jan 28, 2025 14:16:02', 'SETTLED', 'PAID', 'PAID', 'SETTLED'],
  ['RET-000044', 'LOAN-000022', 'REC-000125', 'JPM01', 'JPM', 750000, 'Jan 28, 2025 14:18:07', 'SETTLED', 'PAID', 'PAID', 'SETTLED'],
].map(([id, loan, recall, borrower, security, quantity, requested, collateral, fee, rebate, settlement]) => ({id, loan, recall, borrower, security, quantity, requested, collateral, fee, rebate, settlement}))

export const returnTimeline = [
  ['Return Requested', 'Jan 28, 2025 14:05:33', 'done'],
  ['Recall Settled', 'Jan 27, 2025 16:20:11', 'done'],
  ['Collateral Release Submitted', 'Jan 28, 2025 14:05:40', 'done'],
  ['Rebate Paid', 'Jan 28, 2025 14:11:09', 'done'],
  ['Settlement Submitted', 'Jan 28, 2025 14:18:27', 'current'],
  ['Settlement Completed', '—', 'future'],
  ['Return Closed', '—', 'future'],
]
