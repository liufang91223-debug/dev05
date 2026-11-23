export type Row = Record<string,any>
export async function api<T=any>(path:string,method='GET',value?:unknown):Promise<T> {
  const headers:Record<string,string>={Accept:'application/json'}
  if(method!=='GET'){
    const token=document.cookie.split('; ').find(v=>v.startsWith('XSRF-TOKEN='))?.slice(11)
    if(token) headers['X-XSRF-TOKEN']=decodeURIComponent(token)
  }
  const form=value instanceof URLSearchParams
  if(value!==undefined) headers['Content-Type']=form?'application/x-www-form-urlencoded':'application/json'
  const response=await fetch('/api'+path,{method,headers,body:value===undefined?undefined:form?value:JSON.stringify(value)})
  if(!response.ok){
    const detail=await response.json().catch(()=>({}))
    throw new Error(detail.message || ({401:'账号或密码不正确，或登录已过期',403:'当前账号没有操作权限',409:'记录已变化，请刷新'} as Record<number,string>)[response.status] || '请求未完成')
  }
  return (response.headers.get('content-type')?.includes('json')?await response.json():undefined) as T
}
export const day=()=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())
export const money=(v:any)=>'¥'+Number(v??0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})
export const status=(v:any,kind='')=>({DRAFT:'草稿',PENDING:kind.includes('order')?'待审批':'待审核',APPROVED:kind.startsWith('sales')?'待发货':'待收货',FULFILLED:'已完成',POSTED:'已过账',TRANSIT:'在途',GROUP:'集团卡',TERMINAL:'终端卡',HARDWARE:'模组 / 设备'} as Record<string,string>)[v] || v
export function numCode(prefix:string,id:any,date:any=''){
  return id ? prefix+'-'+(date ? String(date).slice(0,10).replaceAll('-','')+'-':'')+String(id).padStart(4,'0'):'自动生成'
}
export function go(page:string,id?:any,query=''){
  location.hash='/'+page+(id===undefined?'':'/'+encodeURIComponent(String(id)))+query
}
export function csv(name:string, cols:string[], values:any[][]){
  const cell=(v:unknown)=>{let s=String(v??''); if(/^[=+@\-\t\r]/.test(s))s="'"+s;return '"'+s.replaceAll('"','""')+'"'}
  const output=new Blob(['\uFEFF'+[cols,...values].map(r=>r.map(cell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'})
  const url=URL.createObjectURL(output),a=document.createElement('a')
  a.href=url;a.download=name+'.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)
}
