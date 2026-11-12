import { topUpCollateral,loanCollateral } from './loanWorkflowFixtures.js'
export const money = (value,decimals=0) => new Intl.NumberFormat('en-US',{style:'currency',currency:'USD',minimumFractionDigits:decimals,maximumFractionDigits:decimals}).format(value)
export const shares = value => Number(value||0).toLocaleString('en-US')
export const utcDisplay = value => value ? value.replace('T',' ')+' UTC' : '—'
export function marginProjection(data){
 const marketValue=data.outstanding*data.price
 const required=marketValue*data.requiredRatio/100
 const shortfall=Math.max(0,required-data.currentCollateral)
 const selected=topUpCollateral.filter(row=>data.included.includes(row.type)).reduce((sum,row)=>sum+row.available*(1-row.haircut/100),0)
 return {marketValue,required,shortfall,selected,currentCoverage:data.currentCollateral/marketValue*100,afterCoverage:(data.currentCollateral+Number(data.amount||0))/marketValue*100}
}
export function amendmentProjection(data){
 const extending=data.action==='Extend maturity'
 const fee=extending?data.currentFee:Number(data.fee)*(data.feeUnit==='%'?100:1)
 const rebate=extending?data.currentRebate:Number(data.rebate)*(data.rebateUnit==='%'?100:1)
 const dayBasis=extending?data.currentDayBasis:data.dayBasis
 const marketValue=data.outstanding*data.price
 const collateral=loanCollateral.reduce((sum,row)=>sum+row.value,0)
 return {fee,rebate,dayBasis,marketValue,collateral,coverage:collateral/marketValue*100,dailyFee:marketValue*(fee/10000)/(dayBasis.includes('365')?365:360),feeDelta:fee-data.currentFee,maturityDelta:(Date.parse(data.maturity+'T00:00:00Z')-Date.parse(data.currentMaturity+'T00:00:00Z'))/86400000}
}
export function recallProjection(data){
 const quantity=data.type==='Full'?data.outstanding:Number(data.quantity||0)
 return {quantity,remaining:data.outstanding-quantity,marketValue:quantity*data.price,collateralRelease:quantity*data.price*data.requiredRatio/100}
}
export function addBusinessDaysUtc(value,days){
 const date=new Date(value+'Z')
 if(!Number.isFinite(date.getTime()))return NaN
 for(let added=0;added<days;){date.setUTCDate(date.getUTCDate()+1);if(![0,6].includes(date.getUTCDay()))added++}
 return date.getTime()
}
