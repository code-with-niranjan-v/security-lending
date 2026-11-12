export const wholePositive = value => Number.isSafeInteger(Number(value)) && Number(value) > 0
export const allocationTotal = rows => rows.reduce((total,row)=>total+Number(row.quantity||0),0)
export function allocationErrors(rows,quantity,limits){
 const errors=[]
 if(!rows.length)errors.push('Add at least one inventory source.')
 if(new Set(rows.map(row=>row.source)).size!==rows.length)errors.push('Each inventory source can only be used once.')
 if(rows.some(row=>!wholePositive(row.quantity)))errors.push('Source allocations must be positive whole shares.')
 if(allocationTotal(rows)!==Number(quantity))errors.push('Source allocations must equal the approved or reserved quantity.')
 if(limits&&rows.some(row=>Number(row.quantity)>(limits[row.source]??0)))errors.push('An allocation exceeds its source availability.')
 return errors
}
export function validLeiFormat(value){return /^[A-Z0-9]{18}[0-9]{2}$/.test(value)}
export function validUtc(value){return value && Number.isFinite(Date.parse(`${value.length===10?value+'T00:00':value}Z`))}
export function workflowErrors(kind,data,context={}){
 const errors=[]
 const required=keys=>{for(const key of keys)if(data[key]==null||String(data[key]).trim()==='')errors.push(`${key.replace(/([A-Z])/g,' $1')} is required.`)}
 const now=context.now??Date.now()
 const future=value=>validUtc(value)&&Date.parse(`${value}Z`)>now
 if(kind==='locate'){
   required(['decision','owner','notes'])
   if(data.decision==='Reject'){required(['reason']);return errors}
   if(!wholePositive(data.approved)||Number(data.approved)>data.requested||Number(data.approved)>data.available)errors.push('Approved quantity must be within requested quantity and available inventory.')
   if(data.decision==='Approve'&&Number(data.approved)!==data.requested)errors.push('Approve requires the full requested quantity; use Partial approve for a smaller amount.')
   if(data.decision==='Partial approve'&&Number(data.approved)>=data.requested)errors.push('Partial approval must be smaller than the request.')
   if(!wholePositive(data.lot)||Number(data.lot)>Number(data.approved))errors.push('Minimum borrow lot must be a positive whole number within approved quantity.')
   if(!future(data.expiry))errors.push('Locate expiry must be in the future (UTC).')
   if(!validUtc(data.settlement))errors.push('Choose a valid settlement date.')
   if(!Number.isFinite(Number(data.fee))||Number(data.fee)<0)errors.push('Annual fee cannot be negative.')
   if(Number(data.coverage)<100)errors.push('Collateral coverage must be at least 100%.')
   if(!data.collateral.length)errors.push('Select at least one allowed collateral type.')
   errors.push(...allocationErrors(data.allocations,data.approved,context.sourceAvailability))
 }
 if(kind==='agreement'){
   required(['lender','borrower','type','jurisdiction','law','currency','dayCount','billing','settlement','floor','fee','rebate','limit','coverage'])
   if(['floor','fee','rebate','limit','coverage'].some(key=>!Number.isFinite(Number(data[key]))))errors.push('Commercial rates, credit limit and coverage must be finite numbers.')
   if(!validUtc(data.effective)||!validUtc(data.expiry)||Date.parse(data.expiry+'Z')<=Date.parse(data.effective+'Z'))errors.push('Agreement expiry must be after its effective date (UTC).')
   if(Number(data.floor)<0||Number(data.fee)<Number(data.floor)||Number(data.rebate)<0)errors.push('Negotiated fee must meet the lending fee floor; rates cannot be negative.')
   if(Number(data.limit)<=0||Number(data.coverage)<100)errors.push('A positive credit limit and at least 100% collateral coverage are required.')
   if(data.haircuts.some(row=>Number(row.haircut)<0||Number(row.haircut)>=100||Number(row.max)<=0||Number(row.max)>100))errors.push('Haircuts must be between 0 and 100%; contribution limits must be above 0 and at most 100%.')
   if(!data.documents.length)errors.push('Add an agreement document.')
 }
 if(kind==='borrower'){
   required(['name','code','lei','entity','country','tax','address','custodian','account','bank','currency','bankAccount','connection','sender','target','protocol','owner'])
   if(!validLeiFormat(data.lei))errors.push('LEI must have 20 characters: 18 letters/digits and two check digits. Registry and checksum verification is required outside this demo.')
   if(!/^[A-Z0-9_-]+$/i.test(data.code))errors.push('Borrower code may contain only letters, numbers, underscores and hyphens.')
   if(Number(data.limit)<=0)errors.push('Credit limit must be positive.')
   if(!data.segments.length)errors.push('Select at least one permitted segment.')
   if(!data.contacts.length||data.contacts.some(row=>!row.name.trim()||!row.phone.trim()||!row.type||!/^\S+@\S+\.\S+$/.test(row.email)))errors.push('Each contact needs a name, type, valid email and phone.')
   if(!data.documents.length)errors.push('Add KYC and sanctions documentation.')
 }
 if(kind==='return'){
   required(['action','notes'])
   if(data.action==='Reject request'){required(['reason']);return errors}
   required(['account','reference'])
   if(!wholePositive(data.accepted)||Number(data.accepted)>data.requested||Number(data.accepted)>data.outstanding)errors.push('Accepted quantity must be within the request and outstanding loan quantity.')
   if(!validUtc(data.settlement))errors.push('Choose a valid expected settlement date (UTC).')
   const selected=(context.returnLots??[]).filter(row=>data.lots.includes(row.id)).reduce((total,row)=>total+row.quantity,0)
   if(selected<Number(data.accepted))errors.push('Select sufficient return lots for the accepted quantity.')
 }
 if(kind==='reservation'){
   required(['action','owner','reason','notes'])
   if(data.action!=='Release'){
     if(!future(data.expiry))errors.push('Reservation expiry must be in the future (UTC).')
     if(!validUtc(data.valueDate))errors.push('Choose a valid value date.')
   }
   if(data.action==='Amend reservation'){
     if(!wholePositive(data.quantity)||Number(data.quantity)<data.consumed||Number(data.quantity)>data.approved)errors.push('New total must be at least consumed quantity and at most approved locate quantity.')
     errors.push(...allocationErrors(data.allocations,data.quantity,context.sourceAvailability))
   }
 }
 return errors
}
