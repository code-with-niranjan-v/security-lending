import { useState } from 'react'

export function useReferenceSort(rows) {
  const [sort, setSort] = useState(null)
  const sorted = [...rows]
  if (sort) sorted.sort((a, b) => {
    const left = a[sort.key], right = b[sort.key]
    const numeric = value => {
      const text = String(value).replace(/[$,%\s,]|bps/g, '')
      const match = text.match(/^(-?\d+(?:\.\d+)?)([KMB])?$/i)
      return match ? Number(match[1]) * ({ K: 1e3, M: 1e6, B: 1e9 }[match[2]?.toUpperCase()] ?? 1) : NaN
    }
    const x = numeric(left), y = numeric(right)
    const dateKey = ['effectiveDate', 'expiryDate', 'expiry', 'requested', 'timestamp', 'activity', 'expiresAt'].includes(sort.key)
    const result = dateKey ? new Date(left) - new Date(right) : Number.isFinite(x) && Number.isFinite(y) ? x - y : String(left).localeCompare(String(right))
    return result * sort.direction
  })
  function sortHeading(key, label) {
    return <th key={key} aria-sort={sort?.key === key ? sort.direction === 1 ? 'ascending' : 'descending' : 'none'}><button type="button" onClick={() => setSort(current => ({ key, direction: current?.key === key ? -current.direction : 1 }))}>{label}<span className="ref-sort">{sort?.key === key ? sort.direction === 1 ? '↑' : '↓' : '♢'}</span></button></th>
  }
  return { sorted, sortHeading }
}
