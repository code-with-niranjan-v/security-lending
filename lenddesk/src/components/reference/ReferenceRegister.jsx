import { Fragment } from 'react'
import { useReferenceSort } from '../../hooks/useReferenceSort'
export default function ReferenceRegister({ columns, rows, expanded, setExpanded, cell, details, className = '' }) {
  const {sorted,sortHeading}=useReferenceSort(rows)
  const toggle=id=>setExpanded(current=>current===id?null:id)
  return <div className="ref-scroll"><table className={`ref-main-table ${className}`}><thead><tr><th aria-label="Expand record"/>{columns.map(([key,label])=>sortHeading(key,label))}</tr></thead><tbody>{sorted.map(row=><Fragment key={row.id}><tr className={expanded===row.id?'ref-selected':''}><td><button type="button" className="ref-expander" aria-expanded={expanded===row.id} aria-label={`${expanded===row.id?'Collapse':'Expand'} ${row.name??row.id}`} onClick={()=>toggle(row.id)}>{expanded===row.id?'⌄':'›'}</button></td>{columns.map(([key])=><td key={key}>{key==='id'||key==='name'?<button type="button" className="ref-id" onClick={()=>toggle(row.id)}>{row[key]}</button>:cell(row,key)}</td>)}</tr>{expanded===row.id&&<tr className="ref-child"><td colSpan={columns.length+1}>{details(row)}</td></tr>}</Fragment>)}{rows.length===0&&<tr><td colSpan={columns.length+1} className="ref-empty">No records match your filters.</td></tr>}</tbody></table></div>
}
