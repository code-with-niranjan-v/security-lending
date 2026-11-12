// Static demo values transcribed from lender-ui-designs/inventory.png.
export const inventorySummary = [
  ['Total inventory', '38,426,200'], ['Lent', '14,218,400'],
  ['Reserved', '2,903,800'], ['Available', '21,304,000'],
]

export const inventory = [
  ['NVDA', 'US67066G1040', 'NVIDIA Corporation', 1250000, 600000, 150000, 500000, 128.18, 160225000, 48.0, 'High'],
  ['AAPL', 'US0378331005', 'Apple Inc.', 600000, 210000, 120000, 270000, 191.56, 114936000, 55.0, 'High'],
  ['MSFT', 'US5949181045', 'Microsoft Corporation', 500000, 220000, 100000, 180000, 420.94, 210470000, 64.0, 'High'],
  ['AMZN', 'US0231351067', 'Amazon.com, Inc.', 400000, 150000, 80000, 170000, 178.61, 71444000, 57.5, 'Medium'],
  ['TSLA', 'US88160R1014', 'Tesla, Inc.', 300000, 78000, 50000, 172000, 262.30, 78690000, 42.7, 'Medium'],
  ['META', 'US30303M1027', 'Meta Platforms, Inc.', 350000, 140000, 70000, 140000, 474.99, 166246500, 60.0, 'High'],
  ['GOOGL', 'US02079K3059', 'Alphabet Inc. (Class A)', 320000, 110000, 60000, 150000, 142.85, 45712000, 53.1, 'Medium'],
  ['BRK.B', 'US0846707026', 'Berkshire Hathaway Inc. (Class B)', 200000, 60000, 30000, 110000, 456.12, 91224000, 45.0, 'Low'],
  ['JPM', 'US46625H1005', 'JPMorgan Chase & Co.', 500000, 160000, 120000, 220000, 171.42, 85710000, 56.0, 'Medium'],
  ['XOM', 'US30231G1022', 'Exxon Mobil Corporation', 800000, 220000, 140000, 440000, 114.27, 91416000, 45.0, 'Medium'],
  ['UNH', 'US91324P1021', 'UnitedHealth Group Incorporated', 180000, 70000, 20000, 90000, 536.78, 96620400, 50.0, 'Low'],
  ['CVX', 'US1667641005', 'Chevron Corporation', 360000, 120000, 60000, 180000, 160.31, 57711600, 50.0, 'Medium'],
  ['KO', 'US1912161007', 'The Coca-Cola Company', 1000000, 300000, 200000, 500000, 62.14, 62140000, 50.0, 'Low'],
  ['PFE', 'US7170811035', 'Pfizer Inc.', 1200000, 280000, 220000, 700000, 29.60, 35520000, 41.7, 'Low'],
  ['T', 'US00206R1023', 'AT&T Inc.', 2000000, 480000, 300000, 1220000, 17.32, 34640000, 39.0, 'Low'],
  ['VZ', 'US92343V1044', 'Verizon Communications Inc.', 1800000, 420000, 260000, 1120000, 42.15, 75870000, 37.8, 'Low'],
].map(([symbol, isin, name, total, onLoan, reserved, available, price, marketValue, utilisation, demand]) => ({
  id: symbol, symbol, isin, name, total, onLoan, reserved, available, price, marketValue,
  utilisation, demand, assetClass: 'Equity', status: 'Active',
}))

export const nvidiaDetails = {
  loans: [
    ['LOAN-000006', 'EVRST01', '200,000', '2.35%'],
    ['LOAN-000010', 'APLPRIME01', '150,000', '2.40%'],
    ['LOAN-000011', 'MSFT01', '100,000', '2.60%'],
    ['LOAN-000013', 'AMZN01', '90,000', '2.55%'],
    ['LOAN-000014', 'TSLA01', '60,000', '2.75%'],
  ],
  reservations: [
    ['RES-004891', 'JPM01', '60,000', 'Jan 29, 2025'],
    ['RES-004892', 'GS01', '50,000', 'Jan 30, 2025'],
    ['RES-004893', 'CITI01', '40,000', 'Feb 3, 2025'],
  ],
  locates: [
    ['LOC-017221', 'MS01', '250,000', '1h 12m'],
    ['LOC-017222', 'BARC01', '100,000', '3h 27m'],
  ],
  movements: [
    ['Jan 28, 2025 13:49:21', 'Return', '+100,000', '1,250,000'],
    ['Jan 28, 2025 10:17:06', 'Lent', '−50,000', '1,150,000'],
    ['Jan 27, 2025 15:32:18', 'Reserve', '−50,000', '1,200,000'],
    ['Jan 27, 2025 09:08:51', 'Adjustment', '+25,000', '1,250,000'],
  ],
}
