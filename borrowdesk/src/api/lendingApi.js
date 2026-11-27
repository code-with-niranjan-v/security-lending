import{get,post,request,orgUrl,unwrapRows}from'./apiClient';
const b=orgUrl('');

async function loadLocates() {
  const records = [];
  let page = 0;
  let result;
  do {
    result = await get(`${b}/locates?page=${page++}&size=200`);
    records.push(...unwrapRows(result));
  } while (page < (result?.totalPages ?? 1));
  return records;
}

export const lendingApi={lenders:()=>get(b+'/lenders'),agreements:()=>get(b+'/agreements?page=0&size=200'),agreement:id=>get(b+'/agreements/'+id),createAgreement:x=>post(b+'/agreements',x),decideAgreement:(id,decision,payload)=>post(b+'/agreements/'+id+'/'+decision,payload),locates:loadLocates,createLocate:x=>post(b+'/locates',x),
 securities:()=>get('/api/v1/securities'),
 audit:()=>get(b+'/audit?page=0&size=200'),reservations:()=>get(b+'/reservations?page=0&size=200'),collateral:()=>get(b+'/collateral?page=0&size=200'),account:()=>get(b+'/account'),loanCollateralPreview:(body,signal)=>request(b+'/loans/collateral-preview',{method:'POST',body:JSON.stringify(body),signal}),createLoan:x=>post(b+'/loans',x),reserveLocate:(id,x)=>post(b+'/locates/'+id+'/reserve',x),releaseReservation:id=>post(b+'/reservations/'+id+'/release',{}),loans:()=>get(b+'/loans?page=0&size=200'),loan:id=>get(b+'/loans/'+id),recalls:()=>get(b+'/recalls?page=0&size=200'),marginCalls:()=>get(b+'/margin-calls?page=0&size=200'),fees:()=>get(b+'/fees?page=0&size=200'),reduceExposure:id=>post(b+'/margin-calls/'+id+'/reduce-exposure',{}),requestReturn:(id,x)=>post(b+'/loans/'+id+'/returns',x),respondToRecall:(id,x)=>post(b+'/recalls/'+id+'/returns',x),postCollateral:(id,x)=>post(b+'/loans/'+id+'/collateral',x),returns:()=>get(b+'/returns?page=0&size=200'),creditHistory:()=>get(b+'/credit-history')}