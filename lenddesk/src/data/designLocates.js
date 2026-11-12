// Static demo values from lender-ui-designs/locates.png.
// Countdown labels reproduce the reference snapshot, not the current clock.
export const designLocates = [
  ['LOC-017221', 'MS01', 'MSLA-001', 'EXRST01', 250000, 250000, 150000, 100000, 'Jan 28, 2025 13:49:21', '', 'plain', 'Active'],
  ['LOC-017222', 'BARC01', 'MSLA-003', 'BRC01', 100000, 100000, 50000, 50000, 'Jan 28, 2025 10:17:06', '1h 12m', 'risk', 'Active'],
  ['LOC-017223', 'GS01', 'MSLA-002', 'CITI01', 40000, 40000, 20000, 20000, 'Feb 3, 2025 15:21:09', '6d 0h', 'warn', 'Active'],
  ['LOC-017224', 'JPM01', 'MSLA-001', 'JPM', 120000, 120000, 120000, 0, 'Jan 29, 2025 16:00:00', '1d 1h', 'warn', 'Active'],
  ['LOC-017225', 'CITI01', 'MSLA-004', 'C', 80000, 80000, 0, 80000, 'Feb 5, 2025 18:00:00', '8d 3h', 'purple', 'Pending'],
  ['LOC-017226', 'EXRST01', 'MSLA-002', 'XOM', 200000, 200000, 0, 200000, 'Feb 6, 2025 14:00:00', '9d 0h', 'purple', 'Pending'],
  ['LOC-017227', 'APLPRIME01', 'MSLA-003', 'AAPL', 300000, 300000, 180000, 120000, 'Jan 28, 2025 15:00:00', '32m', 'risk', 'Active'],
  ['LOC-017228', 'MSFT01', 'MSLA-001', 'MSFT', 150000, 150000, 75000, 75000, 'Jan 30, 2025 20:00:00', '2d 5h', 'purple', 'Active'],
].map(([id, borrower, msla, security, requested, approved, reserved, available, expiry, countdown, expiryTone, status]) => ({ id, borrower, msla, security, requested, approved, reserved, available, expiry, countdown, expiryTone, status }))

export const locateReservations = [
  { id: 'RES-004892', quantity: 50000, remaining: 50000, countdown: '1h 12m', expiry: 'Jan 28, 2025 10:17:06', tone: 'risk', loan: 'LOAN-000010' },
  { id: 'RES-004897', quantity: 30000, remaining: 20000, countdown: '3h 27m', expiry: 'Jan 28, 2025 12:54:31', tone: 'warn', loan: 'LOAN-000015' },
  { id: 'RES-004910', quantity: 20000, remaining: 20000, countdown: '2d 19h 32m', expiry: 'Jan 31, 2025 09:00:00', tone: 'purple', loan: 'LOAN-000018' },
]
export const locateActivity = [
  ['Jan 28, 2025 09:08:51', 'Adjustment', 'Reserved qty increased by +25,000 (25,000 → 50,000). Available qty decreased by −25,000 (75,000 → 50,000).'],
  ['Jan 27, 2025 15:32:18', 'Reservation created', 'RES-004910 for 20,000 linked to LOAN-000018. Expires Jan 31, 2025 09:00:00 (2d 19h 32m).'],
  ['Jan 27, 2025 13:11:27', 'Reservation created', 'RES-004897 for 30,000 linked to LOAN-000015. Expires Jan 28, 2025 12:54:31 (3h 27m).'],
  ['Jan 27, 2025 09:08:51', 'Locate approved', 'Approved 100,000 (requested 100,000) for BRC01 under MSLA-003.'],
]
