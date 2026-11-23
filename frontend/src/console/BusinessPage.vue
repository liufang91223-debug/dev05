<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {api,go,money,status,day,type Row} from './api'
import type {MenuItem} from './pages'
import ExportPage from './ExportPage.vue'
import OrderPages from './OrderPages.vue'
import DocumentPages from './DocumentPages.vue'
import RegisterPages from './RegisterPages.vue'
import ListFrame from './ListFrame.vue'
import {saveBook} from './workbook'
import copy from './board-copy.json'
const props=defineProps<{item:MenuItem;routeKey:string;recordId?:string;query:URLSearchParams;user:Row}>()
const area=props.item.area,source=props.item.source,rows=ref<Row[]>([]),busy=ref(false),adding=ref(false),form=ref<Row>({role:'ERP',party:null}),customers=ref<Row[]>([])
const meta=(copy as Record<string,Row>)[props.routeKey]||{}
const opened=ref(false),selected=ref<Row>({}),shown=ref(false)
const names:Record<string,string>={code:'商品编码',name:'商品名称',warehouse_name:'仓库',kind:'商品类型',on_hand:'合计库存',reserved:'锁定库存',available:'可用库存',
 party_name:'往来单位',quantity:'数量',unit_price:'含税单价',gross:'含税金额',business_day:'单据日期',delivery_day:'交货日期',order_code:'来源订单',display_state:'状态',
 receivable:'应收总额',invoiced:'已开票',paid:'已回款',outstanding:'待回款',doc_code:'单据编号',iccid:'ICCID',msisdn:'卡号',activated_on:'启用日期',erp_expiry:'失效日期',customer_name:'客户',carrier_name:'运营商集团账号',
 username:'账号',role_label:'角色',party_id:'关联客户编号',members:'成员数',scope:'可见数据',label:'角色名称',permissions:'可用功能'}
const fields=computed(()=>{
 if(area==='cards')return ['iccid','msisdn','customer_name','carrier_name','activated_on','erp_expiry','display_state']
 if(area==='users')return source==='roles'?['label','members','scope','permissions']:['username','role_label','party_id']
 if(source==='receivable')return ['name','receivable','invoiced','paid','outstanding']
 if(source==='stock')return ['code','name','warehouse_name','kind','available','reserved','on_hand']
 if(source==='sales'||source==='purchase')return ['order_code','business_day','party_name','code','name','quantity','unit_price','gross','display_state']
 return ['doc_code','business_day','party_name','warehouse_name','code','name','quantity','display_state']
})
const labels=computed(()=>fields.value.map((key,i)=>({key,label:key==='name'&&source==='receivable'?'客户名称':names[key],link:i===0,wide:key==='iccid'?205:i===0?155:120})))
const title=computed(()=>props.routeKey==='inventory_id_list'?'库存 ID 明细':props.routeKey==='purchase_order_id_list'?'采购订单卡号明细':area==='users'?props.item.label:(meta.title||props.item.label))
const stats=computed<[string,any,string?][]>(()=>{
 const sum=(k:string)=>rows.value.reduce((a,r)=>a+Number(r[k]||0),0)
 if(area==='cards')return [['流量卡 ID 总数',rows.value.length,'张'],...['生效中','即将到期','已失效'].map(s=>[s,rows.value.filter(r=>r.display_state===s).length,'张'] as [string,number,string])]
 if(source==='receivable')return [['应收总额',money(sum('receivable'))],['已回款',money(sum('paid'))],['待回款',money(sum('outstanding'))]]
 if(source==='stock')return [['库存商品行',rows.value.length,'行'],['可用库存合计',sum('available'),'件'],['锁定库存合计',sum('reserved'),'件']]
 return [[area==='users'?'记录总数':'明细行数',rows.value.length,'条']]
})
async function load(){
 if(props.routeKey.endsWith('_export')||['order','register','doc'].includes(area))return
 busy.value=true
 try{
  if(area==='cards'){
   const items=await api<Row[]>('/erp/cards');const inventoryView=props.routeKey==='inventory_id_list'
   rows.value=items.filter(r=>inventoryView?(!r.customer_id&&r.warehouse_id&&(!props.query.has('sku')||r.sku_id===Number(props.query.get('sku')))&&(!props.query.has('warehouse')||r.warehouse_id===Number(props.query.get('warehouse')))):props.routeKey==='purchase_order_id_list'?r.purchase_order_id===Number(props.query.get('order')):(r.customer_id&&(!props.query.has('order')||r.sale_order_id===Number(props.query.get('order'))))).map(r=>{
    const days=(Date.parse(r.erp_expiry)-Date.parse(day()))/86400000
    return {...r,display_state:r.customer_id?(days<0?'已失效':days<=30?'即将到期':'生效中'):r.warehouse_id?'在库':'已退供',filter_date:r.erp_expiry}})
  }else if(area==='users'){
   const users=await api<Row[]>('/admin/master/accounts'),roles:Record<string,string>={ADMIN:'系统管理员',ERP:'业务人员',CUSTOMER:'客户'}
   if(source==='roles')rows.value=Object.entries(roles).map(([role,label])=>({role,label,members:users.filter(u=>u.role===role).length,scope:role==='CUSTOMER'?'本客户':'本租户',permissions:role==='ADMIN'?'业务、配置及账号管理':role==='ERP'?'业务、订单及仓储':'门户订单与名下卡查询'}))
   else{rows.value=users.map(u=>({...u,role_label:roles[u.role]}));customers.value=await api('/erp/register/customer')}
  }else{
   let list=await api<Row[]>('/erp/reports/'+source)
   if(source==='inbound'||source==='outbound'){
    const skus:Row[]=(await api('/erp/setup')).skus
    list=list.flatMap(d=>(typeof d.lines==='string'?JSON.parse(d.lines):d.lines).map((l:Row)=>({...d,sku_id:l.sku,quantity:l.quantity,code:skus.find(s=>s.id===l.sku)?.code,name:skus.find(s=>s.id===l.sku)?.name})))
   }
   rows.value=list.map(r=>({...r,filter_date:r.business_day,doc_code:r.doc_code||'#'+r.id,kind:source==='stock'?status(r.kind):r.kind,display_state:status(r.state,source==='sales'?'sales_order':'purchase_order')}))
  }
 }catch(e){ElMessage.error((e as Error).message)}finally{busy.value=false}
}
function open(r:Row){if(area==='cards'){selected.value=r;opened.value=true}
 else if(area==='users'||source==='receivable'){selected.value=r;shown.value=true}
 else if(source==='stock')go('inventory_id_list',undefined,'?sku='+r.sku_id+'&warehouse='+r.warehouse_id)
 else if(source==='sales'||source==='purchase')go((source==='sales'?'sales':'purchase')+'_order_detail',r.order_id)
 else go(r.kind+'_detail',r.id)}
async function submit(){try{await api('/admin/master/accounts','POST',form.value);adding.value=false;await load();ElMessage.success('账号已开设')}catch(e){ElMessage.error((e as Error).message)}}
async function stockExport(){
 try{
  const cards=(await api<Row[]>('/erp/cards')).filter(r=>r.customer_id==null && r.warehouse_id!=null)
  await saveBook('库存明细',[{name:'库存商品数据',columns:labels.value.map(c=>c.label),rows:rows.value.map(r=>labels.value.map(c=>r[c.key]??''))},
   {name:'库存卡号明细',columns:['ICCID','卡号','商品编号','仓库编号','运营商集团账号','套餐名称'],rows:cards.map(r=>[r.iccid,r.msisdn,r.sku_id,r.warehouse_id,r.carrier_name,r.plan_name])}])
 }catch(e){ElMessage.error((e as Error).message)}
}
onMounted(load)
</script>
<template><ExportPage v-if="routeKey.endsWith('_export')" :item="item" :route-key="routeKey"/><OrderPages v-else-if="area==='order'" v-bind="props"/><DocumentPages v-else-if="area==='doc'" v-bind="props"/><RegisterPages v-else-if="area==='register'" v-bind="props"/>
 <template v-else><ListFrame :page="routeKey" :user-key="user.username" :title="title" :subtitle="area==='cards'?'按 ICCID、卡号或客户查询单卡明细。':source==='stock'?'按仓库核对可用、锁定和合计数量。':undefined"
 :rows="rows" :columns="labels" :stats="stats" :busy="busy" :keyword="area==='cards'?'ICCID / 卡号 / 客户':'名称 / 编码'" :date-label="area==='report'&&!['stock','receivable'].includes(source)?'业务日期':undefined"
 :export-label="area==='users'?undefined:'导出明细 CSV'" :new-label="source==='accounts'?'新增人员':undefined" @open="open" @create="form={role:'ERP',party:null};adding=true"><template #actions><el-button v-if="['sales','purchase'].includes(source)" @click="go(item.key.replace('_detail','_export'))">导出配置</el-button><el-button v-if="source==='stock'" @click="stockExport">Excel 导出（2 Sheet）</el-button></template></ListFrame>
 <el-dialog v-model="shown" :title="title+'详情'" width="510px"><div class="detail-grid"><div v-for="f in fields" :key="f"><small>{{names[f]}}</small>{{selected[f]??'—'}}</div></div></el-dialog>
 <el-dialog v-model="adding" title="开设人员账号" width="460px"><el-form label-position="top"><el-form-item label="登录账号"><el-input v-model="form.username"/></el-form-item><el-form-item label="初始密码（至少 12 位）"><el-input v-model="form.password" type="password" show-password/></el-form-item><el-form-item label="账号角色"><el-radio-group v-model="form.role" @change="form.party=null"><el-radio value="ERP">业务人员</el-radio><el-radio value="CUSTOMER">客户</el-radio></el-radio-group></el-form-item><el-form-item v-if="form.role==='CUSTOMER'" label="关联客户"><el-select v-model="form.party"><el-option v-for="p in customers" :key="p.id" :value="p.id" :label="p.name"/></el-select></el-form-item></el-form><template #footer><el-button type="primary" @click="submit">保存</el-button></template></el-dialog>
 <el-drawer v-model="opened" title="流量卡明细" size="min(650px, 90vw)" destroy-on-close><div class="detail-grid"><div v-for="f in fields" :key="f"><small>{{names[f]}}</small>{{selected[f]??'—'}}</div></div><p><el-button type="primary" @click="go('reconciliation',selected.iccid)">打开流量卡对账与维护</el-button></p></el-drawer>
 </template></template>
