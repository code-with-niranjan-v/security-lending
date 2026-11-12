export const num = (value) =>
  value == null
    ? '—'
    : Number(value).toLocaleString('en-US', { maximumFractionDigits: 2 })
export const money = (value, currency = 'USD') =>
  value == null
    ? '—'
    : new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency,
        minimumFractionDigits: 0,
        maximumFractionDigits: 2,
      }).format(value)
export function downloadLoans(rows, keys) {
  const csv = [keys, ...rows.map((r) => keys.map((k) => r[k] ?? ''))]
    .map((r) =>
      r
        .map(
          (v) =>
            '"' +
            String(v)
              .replaceAll('"', '""')
              .replace(/^[=+@-]/, "'$&") +
            '"',
        )
        .join(','),
    )
    .join('\n')
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' })),
    a = document.createElement('a')
  a.href = url
  a.download = 'loans.csv'
  a.click()
  URL.revokeObjectURL(url)
}
export const loanId = (id) => `LOAN-${String(id).padStart(6, '0')}`
export const display = (value) =>
  value
    ?.toLowerCase()
    .replaceAll('_', ' ')
    .replace(/^./, (c) => c.toUpperCase()) ?? '—'
export const date = (value) =>
  value ? new Date(value).toLocaleString('en-GB', { timeZone: 'UTC' }) : '—'
