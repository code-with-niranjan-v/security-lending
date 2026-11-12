import { useId } from 'react'
export function Field({label,required=false,children,hint,wide=false}){
 return <label className={`wf-field ${wide?'wf-wide':''}`}><span>{label}{required&&<b className="wf-required"> *</b>}</span>{children}{hint&&<small>{hint}</small>}</label>
}
export function Input({label,name,data,setData,type='text',...props}){
 return <input aria-label={label??name} type={type} value={data[name]??''} onChange={event=>setData(current=>({...current,[name]:event.target.value}))} {...props}/>
}
export function Select({label,name,data,setData,options,...props}){
 return <select aria-label={label??name} value={data[name]} onChange={event=>setData(current=>({...current,[name]:event.target.value}))} {...props}>{options.map(option=><option key={option}>{option}</option>)}</select>
}
export function Card({title,children,id}){return <section className="wf-card" id={id}><h2>{title}</h2><div className="wf-card-body">{children}</div></section>}
export function Summary({title,rows}){return <Card title={title}><dl className="wf-summary">{rows.map(([label,value])=><div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl></Card>}
export function Checks({title='Pre-submit checks',items}){return <Card title={title}><ul className="wf-checks">{items.map(([label,ok,description])=><li key={label}><span className={ok?'wf-check-good':'wf-check-warn'} aria-hidden="true">{ok?'✓':'!'}</span><div><strong>{label}</strong>{description&&<small>{description}</small>}</div><span className={`wf-tag ${ok?'good':'warn'}`}>{ok?'Complete':'Review required'}</span></li>)}</ul></Card>}
export function Choices({label,options,value,onChange}){
 const group=useId()
 return <fieldset className="wf-choices"><legend>{label}</legend>{options.map(option=><label key={option} className={value===option?'active':''}><input type="radio" name={group} value={option} checked={value===option} onChange={()=>onChange(option)}/>{option}</label>)}</fieldset>
}
export function Documents({documents,onChange,multiple=false,label='Upload files',onError}){
 const id=useId()
 return <div className="wf-documents"><label className="wf-upload" htmlFor={id}>↥ {label}</label><input id={id} aria-label={label} className="wf-file-input" type="file" accept="application/pdf,.pdf" multiple={multiple} onChange={event=>{
   const files=[...event.target.files]
   if(files.some(file=>!file.name.toLowerCase().endsWith('.pdf')||file.size>25*1024*1024)){onError?.('Choose PDF files no larger than 25 MB each.');event.target.value='';return}
   onChange([...(multiple?documents.filter(document=>!document.demo):[]),...files.map(file=>({name:file.name,size:file.size,demo:false}))]);onError?.('')
   event.target.value=''
 }}/>{documents.map((document,index)=><span className="wf-document" key={`${document.name}-${index}`}><span>▤ {document.name} <small>({(document.size/1024/1024).toFixed(1)} MB{document.demo?', demo reference':''})</small></span><button type="button" aria-label={`Remove ${document.name}`} onClick={()=>onChange(documents.filter((_,i)=>i!==index))}>×</button></span>)}</div>
}
