
export function Badge({ children, tone = 'good' }) {
  return <span className={`ref-badge ref-${tone}`}>{children}</span>
}

export default function ReferenceTable({ title, headers, rows }) {
  return <section className="ref-detail-card"><h2>{title}</h2><table><thead><tr>{headers.map(header => <th key={header}>{header}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={index}>{row.map((cell, cellIndex) => <td key={cellIndex}>{cell}</td>)}</tr>)}{rows.length === 0 && <tr><td colSpan={headers.length} className="ref-empty">No additional demo records.</td></tr>}</tbody></table></section>
}

