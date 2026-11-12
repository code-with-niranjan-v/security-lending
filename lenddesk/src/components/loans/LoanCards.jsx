export function LoanBadge({ children }) {
  return (
    <span
      className={`al-badge ${/reject|default|overdue|open/i.test(children) ? 'risk' : /due|pending/i.test(children) ? 'warn' : 'good'}`}
    >
      {children}
    </span>
  )
}
export function LoanCard({ title, rows }) {
  return (
    <article className="al-card">
      <h3>{title}</h3>
      <dl>
        {rows.map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>{v ?? '—'}</dd>
          </div>
        ))}
      </dl>
    </article>
  )
}
