import { Badge } from './ReferenceTable'
export function FinanceState({ value }) {
  if(value === '—') return value
  const tone = /DUE|OVERDUE|FAILED/.test(value) ? 'risk' : /PAYMENT_PENDING|^PENDING$/.test(value) ? 'warn' : /SUBMITTED|^OPEN$|CREATED|REQUESTED/.test(value) ? 'purple' : 'good'
  return <Badge tone={tone}>{value}</Badge>
}
export function ReferenceMetrics({ metrics }) {
  return <section className="finance-metrics">{metrics.map(([icon,label,value])=><div key={label}><span aria-hidden="true">{icon}</span><div><small>{label}</small><strong>{value}</strong></div></div>)}</section>
}
export function ReferenceFilters({ fields, source, draft, setDraft, apply, clear, extra }) {
  return <form className="ref-filters finance-filters" onSubmit={event=>{event.preventDefault();apply()}}>{extra}{fields.map(([key,label,placeholder])=><label key={key}>{label}<select aria-label={label} value={draft[key]??''} onChange={event=>setDraft(current=>({...current,[key]:event.target.value}))}><option value="">{placeholder}</option>{[...new Set(source.map(row=>row[key]))].filter(value=>value!=null).map(value=><option key={value}>{value}</option>)}</select></label>)}<button className="ref-clear" type="button" onClick={clear}>Clear</button><button className="ref-primary" type="submit">Apply</button></form>
}
