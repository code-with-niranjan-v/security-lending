// Static demo values from lender-ui-designs/collateral.png.
export const designCollateral = [
  ['COL-005-001', 'LOAN-000008', 'MS01', 'Security', 'AAPL', 500000, 185.25, 92625000, 12, 81510000, 75000000, 108.7, 'Settled', 'Jan 28, 2025 14:26:10'],
  ['COL-006-001', 'LOAN-000010', 'BARC01', 'Security', 'MSFT', 1000000, 415.60, 415600000, 15, 353260000, 320000000, 110.4, 'Settled', 'Jan 28, 2025 14:26:11'],
  ['COL-006-002', 'LOAN-000010', 'BARC01', 'Cash', 'USD', 50000000, 1, 50000000, 0, 50000000, 40000000, 125, 'Settled', 'Jan 28, 2025 14:26:11'],
  ['COL-007-001', 'LOAN-000015', 'BRC01', 'Security', 'XOM', 400000, 114.20, 45680000, 10, 41112000, 40000000, 102.8, 'Settled', 'Jan 28, 2025 14:25:59'],
  ['COL-008-001', 'LOAN-000018', 'CITI01', 'Security', 'JPM', 250000, 191.35, 47837500, 12.5, 41857812, 45000000, 93, 'Pending', 'Jan 28, 2025 14:25:44'],
  ['COL-009-001', 'LOAN-000021', 'GS01', 'Security', 'AAPL', 300000, 185.25, 55575000, 12, 48906000, 50000000, 97.8, 'Settled', 'Jan 28, 2025 14:25:40'],
  ['COL-010-001', 'LOAN-000022', 'JPM01', 'Cash', 'USD', 75000000, 1, 75000000, 0, 75000000, 60000000, 125, 'Settled', 'Jan 28, 2025 14:25:37'],
].map(([id, loan, borrower, type, security, quantity, price, marketValue, haircut, adjusted, required, ratio, settlement, valuation]) => ({ id, loan, borrower, type, security, quantity, price, marketValue, haircut, adjusted, required, ratio, settlement, valuation }))

export const collateralValuations = [
  ['Jan 28, 2025', '$415.60', '$415,600,000', '15.0%', '$353,260,000', '$320,000,000', '110.4%'],
  ['Jan 27, 2025', '$410.25', '$410,250,000', '15.0%', '$348,712,500', '$320,000,000', '109.0%'],
  ['Jan 24, 2025', '$408.10', '$408,100,000', '15.0%', '$346,885,000', '$320,000,000', '108.4%'],
]
export const collateralMovements = [
  ['Jan 27, 2025 15:21:09', 'Increase / Pledge', 'Pledged additional 200,000 MSFT to LOAN-000010.', '+200,000', '1,000,000', 'Settled'],
  ['Jan 24, 2025 10:03:27', 'Decrease / Release', 'Released 100,000 MSFT from LOAN-000010.', '−100,000', '800,000', 'Settled'],
  ['Jan 22, 2025 13:47:02', 'Settlement', 'Initial collateral pledge for LOAN-000010.', '+800,000', '800,000', 'Settled'],
]
