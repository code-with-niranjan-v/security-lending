import { useState } from 'react'
export function useWorkflowDraft(kind,id,initial){
 const key=`lenddesk:workflow:v1:${kind}:${id}`
 const [data,setData]=useState(()=>{
   try {const stored=JSON.parse(localStorage.getItem(key));return stored?.version===1&&stored.data?{...structuredClone(initial),...stored.data}:structuredClone(initial)} catch {return structuredClone(initial)}
 })
 const [notice,setNotice]=useState('')
 function save(status='Draft saved locally'){
   try{localStorage.setItem(key,JSON.stringify({version:1,data,status,savedAt:new Date().toISOString()}));setNotice(`${status}. This is a local demo; no backend request was sent.`);return true}catch{setNotice('Draft could not be saved. Browser storage may be full or unavailable.');return false}
 }
 return {data,setData,notice,setNotice,save}
}
