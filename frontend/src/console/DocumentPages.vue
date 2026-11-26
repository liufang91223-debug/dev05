<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import ListFrame from './ListFrame.vue'
import {api,go,day,money,numCode,status,type Row} from './api'
import {board,menu,type MenuItem} from './pages'
import copy from './board-copy.json'
const props=defineProps<{item:MenuItem;routeKey:string;recordId?:string;query:URLSearchParams;user:Row}>()
const kind=props.item.source,sale=kind.startsWith('sales')||kind==='receipt',ship=['purchase_inbound','sales_outbound'].includes(kind),exchange=kind==='sales_exchange',ret=kind.endsWith('_return')||exchange,invoice=kind.endsWith('_invoice'),cash=['receipt','payment'].includes(kind),financial=invoice||cash,transfer=kind==='transfer'
const mode=props.routeKey===props.item.key?'list':props.routeKey.endsWith('_form')?'form':'detail'
const meta=(copy as Record<string,Row>)[props.item.key]||{},stateLabel=(s:string)=>s==='POSTED'?(financial?'已登记':kind.includes('inbound')?'已入库':kind.includes('outbound')?'已出库':'已完成'):status(s)
const records=ref<Row[]>([]),orders=ref<Row[]>([]),invoices=ref<Row[]>([]),allCards=ref<Row[]>([]),locations=ref<Row[]>([]),setup=ref<Row>({skus:[],carriers:[]})
const d=ref<Row>({day:day(),warehouse:null,targetWarehouse:null,sourceOrder:null,sourceDoc:null,amount:0,memo:'',lines:[],revision:0})
const busy=ref(false),cardDialog=ref(false),cardLine=ref<Row>({}),entered=ref(''),picked=ref<string[]>([]),carrier=ref<number>(),expiry=ref(''),activated=ref(''),quota=ref<number>()
const planName=ref(''),cycleMonths=ref(1)
const inbound=kind==='purchase_inbound', stockTaking=kind==='stock_taking'
const relatedKinds=kind==='purchase_inbound'?['purchase_inbound','purchase_return']:
 kind==='sales_outbound'?['sales_outbound','sales_return','sales_exchange']:
 kind==='sales_return'?['sales_return','sales_exchange']:[kind]
const sourceOrders=computed(()=>orders.value.filter(r=>r.direction===(sale?'SALE':'PURCHASE')&&r.state===(ship?'APPROVED':'FULFILLED')))
const cols=[{key:'code',label:props.item.label+'单号',link:true,wide:175},{key:'business_day',label:'单据日期'},...(relatedKinds.length>1?[{key:'doc_type',label:'业务类型'}]:[]),
 ...(ship||financial||ret?[{key:'party_name',label:sale?'客户名称':'供应商名称',wide:150},{key:'source',label:'来源订单'}]:[]),
 ...(!financial?[{key:'warehouse_name',label:transfer?'调出仓库':'仓库'},...(transfer?[{key:'target_name',label:'调入仓库'}]:[]),{key:'qty',label:stockTaking?'实盘数量':'数量'}]:[]),
 ...(financial||ship||ret?[{key:'display_amount',label:'金额',numeric:true}]:[]),{key:'display_state',label:'单据状态'},{key:'creator',label:'制单人'}]
const totalQty=(rows:Row[])=>rows.reduce((sum,i)=>sum+Number(i.quantity||0),0)
const stats=computed<[string,any,string?][]>(()=>{const monthly=records.value.filter(r=>r.filter_date?.startsWith(day().slice(0,7)));return [
 ['本月'+props.item.label+'单数',monthly.length,'笔'],[financial?'本月登记金额':'本月业务数量',financial?money(monthly.filter(r=>r.state==='POSTED').reduce((a,r)=>a+Number(r.amount),0)):monthly.filter(r=>r.state==='POSTED').reduce((a,r)=>a+r.qty,0),financial?'':'件'],['待审核',records.value.filter(r=>r.state==='PENDING').length,'笔']]})
const sku=(id:number)=>setup.value.skus.find((s:Row)=>s.id===id)||{}
const blank=()=>({sku:null,quantity:1,cards:[],expiry:null})
const chosenCards=computed(()=>allCards.value.filter(r=>r.sku_id===cardLine.value.sku && (ret?(sale?r.sale_order_id===d.value.sourceOrder&&r.customer_id:r.purchase_order_id===d.value.sourceOrder&&!r.customer_id):!r.customer_id) && r.warehouse_id===d.value.warehouse))
async function selectOrder(id:number){
 const source=await api('/erp/orders/'+id)
 d.value.sourceOrder=id;d.value.warehouse=source.order.warehouse_id||locations.value[0]?.id||null
 d.value.lines=source.lines.filter((l:Row)=>!exchange || l.kind!=='HARDWARE').map((l:Row)=>({...blank(),sku:l.sku_id,quantity:exchange?1:l.quantity,carrier:l.carrier_account_id}))
 d.value.amount=Number(orders.value.find(r=>r.id===id)?.total||0)
}
async function selectInvoice(id:number){const i=invoices.value.find(r=>r.id===id);if(i){d.value.sourceOrder=i.source_order;d.value.amount=Number(i.amount)}}
function openCards(row:Row){
 cardLine.value=row;picked.value=(row.cards||[]).map((c:Row)=>c.iccid);entered.value=(row.cards||[]).map((c:Row)=>[c.iccid,c.carrier||'',c.quotaKb??'',c.activatedOn||'',c.planName||'',c.cycleMonths||''].join(',')).join('\n')
 carrier.value=row.carrier||setup.value.carriers[0]?.id;expiry.value=row.expiry||'';activated.value='';quota.value=undefined;planName.value=row.cards?.[0]?.planName||'';cycleMonths.value=row.cards?.[0]?.cycleMonths||1;cardDialog.value=true
}
function applyCards(){try{
 const row=cardLine.value
 if(inbound){
  const seen=new Set<string>()
  const cards=entered.value.trim().split(/\r?\n/).filter(Boolean).map(line=>{const [raw,a,q,active,plan,months]=line.split(',').map(s=>s.trim());const iccid=raw.toUpperCase(),account=Number(a||carrier.value)
   if(!/^[0-9A-Z]{18,24}$/.test(iccid)||seen.has(iccid)||!setup.value.carriers.some((c:Row)=>c.id===account))throw Error('请检查 ICCID 格式、重复值和集团账号')
   const amount=q?Number(q):quota.value;if(amount!=null &&(!Number.isInteger(amount)||amount<0))throw Error('额度应为非负整数 KB')
   seen.add(iccid);return {iccid,carrier:account,quotaKb:amount??null,activatedOn:active||activated.value||null,planName:plan||planName.value,cycleMonths:months?Number(months):cycleMonths.value}})
  if(cards.length!==row.quantity)throw Error('录入卡数须与明细数量一致');row.cards=cards
 }else{if(picked.value.length!==row.quantity)throw Error('选择卡数须与明细数量一致');row.cards=picked.value.map(iccid=>({iccid,carrier:0}));row.expiry=expiry.value||null}
 cardDialog.value=false
 }catch(e){ElMessage.error((e as Error).message)}}
async function load(){
 const listOnly=mode==='list'
 const [data,all,w,s,cs]=await Promise.all([
   listOnly?Promise.all(relatedKinds.map(k=>api<Row[]>('/erp/documents?kind='+k))).then(groups=>groups.flat().sort((a,b)=>b.id-a.id)):Promise.resolve([]),
   ship||financial||ret?api<Row[]>('/erp/orders'):Promise.resolve([]),
   listOnly?Promise.resolve([]):api<Row[]>('/erp/register/warehouse'),
   listOnly?Promise.resolve({skus:[],carriers:[]}):api('/erp/setup'),
   mode==='form'&&(ship||ret)&&!inbound?api<Row[]>('/erp/cards'):Promise.resolve([])
 ])
 setup.value=s;allCards.value=cs;orders.value=all;locations.value=w.filter(r=>r.enabled)
 if(!financial&&!d.value.warehouse&&locations.value.length)d.value.warehouse=locations.value[0].id
 records.value=data.map(r=>{const ls=typeof r.lines==='string'?JSON.parse(r.lines):r.lines;const spec=menu.find(m=>m.source===r.kind)||props.item, undo=r.kind.endsWith('_return')
 return {...r,doc_type:spec.label,code:r.doc_code||numCode(spec.prefix!,r.id,r.business_day),qty:totalQty(ls)*(undo?-1:1),display_amount:(undo?'-':'')+money(r.amount),display_state:r.state==='POSTED'&&r.kind!==kind?'已完成':stateLabel(r.state),
 filter_date:String(r.business_day).slice(0,10),source:r.source_code||'—'}})
 if(cash&&!listOnly)invoices.value=(await api<Row[]>('/erp/documents?kind='+(sale?'sales_invoice':'purchase_invoice'))).filter(r=>r.state==='POSTED')
 if(props.recordId){const r=await api('/erp/documents/'+props.recordId);if(r.kind!==kind)throw Error('单据类型不符')
 d.value={...r,sourceOrder:r.source_order,sourceDoc:r.source_doc,warehouse:r.warehouse_id??(financial?null:locations.value[0]?.id??null),targetWarehouse:r.target_warehouse,day:r.business_day,amount:Number(r.amount)}}
 else if(props.query.has('source')){
  await selectOrder(Number(props.query.get('source')))
  const card=allCards.value.find(r=>r.iccid===props.query.get('iccid') && r.sale_order_id===d.value.sourceOrder)
  if(card && ret){d.value.lines=[{...blank(),sku:card.sku_id,quantity:1,cards:[{iccid:card.iccid,carrier:0}]}]}
 }
 else if(!financial&&!ship&&!ret)d.value.lines=[blank()]
}
async function run(fn:()=>Promise<void>){if(busy.value)return;busy.value=true;try{await fn()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error((e as Error).message)}finally{busy.value=false}}
async function save(submit:boolean){
 if(!financial&&!d.value.warehouse)throw Error('请选择仓库')
 if(transfer&&!d.value.targetWarehouse)throw Error('请选择调入仓库')
 const payload={kind,sourceOrder:d.value.sourceOrder,sourceDoc:d.value.sourceDoc,warehouse:d.value.warehouse,targetWarehouse:d.value.targetWarehouse,day:d.value.day,amount:d.value.amount||0,memo:d.value.memo,lines:d.value.lines,revision:d.value.revision}
 const id=d.value.id;const result=await api('/erp/documents'+(id?'/'+id:''),id?'PUT':'POST',payload)
 d.value.id=result.id;if(id)d.value.revision++
 if(submit)await api('/erp/documents/'+result.id+'/transition','POST',{action:'submit',revision:d.value.revision})
 go(board(props.item,'detail'),result.id);ElMessage.success(submit?'单据已提交':'草稿已保存')
}
async function transition(action:string){if(action==='post'||action==='receive')await ElMessageBox.confirm('确认处理当前单据并记账？','审核确认')
 await api('/erp/documents/'+d.value.id+'/transition','POST',{action,revision:d.value.revision});await load();ElMessage.success('单据已更新')}
onMounted(()=>run(load))
</script>
<template><ListFrame v-if="mode==='list'" :page="item.key" :user-key="user.username" :title="meta.title||item.label+'列表'" :subtitle="meta.subtitle" :rows="records" :columns="cols" :stats="stats" :busy="busy" keyword="单号 / 往来单位 / 仓库" :statuses="['草稿','待审核',stateLabel('POSTED'),...(relatedKinds.length>1&&stateLabel('POSTED')!=='已完成'?['已完成']:[]),...(transfer?['在途']:[])]" date-label="单据日期" :new-label="'新建'+item.label" :export-label="ship?undefined:'导出明细 CSV'" @open="r=>go(r.kind+'_detail',r.id)" @create="go(board(item,'form'))"><template #actions><el-button v-if="ship" @click="go(item.key.replace('_list','_export'))">明细导出</el-button></template></ListFrame>
<template v-else><div class="page-head"><div><h1>{{mode==='form'?(recordId?'维护':'新建')+item.label:item.label+'详情'}}</h1><p class="subtitle">{{d.id?(d.doc_code||numCode(item.prefix!,d.id,d.day)):'保存后生成单号'}} · {{stateLabel(d.state||'DRAFT')}}</p></div><div>
 <el-button @click="go(item.key)">返回列表</el-button><template v-if="mode==='detail'"><el-button v-if="d.state==='DRAFT'" type="primary" @click="go(board(item,'form'),d.id)">编辑单据</el-button>
 <el-button v-if="d.state==='DRAFT'" @click="run(()=>transition('submit'))">提交</el-button><template v-if="d.state==='PENDING'"><el-button @click="run(()=>transition('reject'))">驳回</el-button><el-button type="primary" @click="run(()=>transition('post'))">{{ship?(inbound?'审核入库':'确认出库'):'审核过账'}}</el-button></template>
 <el-button v-if="d.state==='TRANSIT'" type="primary" @click="run(()=>transition('receive'))">确认调入</el-button></template></div></div>
 <div class="box"><h3>{{item.label}}基本信息</h3><el-form class="gridform" label-position="top" :disabled="mode==='detail'">
 <el-form-item label="单据类型"><el-input :model-value="item.label" disabled/></el-form-item>
 <el-form-item label="单据日期" required><el-date-picker v-model="d.day" value-format="YYYY-MM-DD"/></el-form-item>
 <el-form-item v-if="(ship||ret||invoice)&&mode==='form'" :label="'参照'+(sale?'销售订单':'采购订单')" required><el-select v-model="d.sourceOrder" filterable @change="selectOrder"><el-option v-for="o in sourceOrders" :key="o.id" :value="o.id" :label="o.order_code+' · '+o.party_name"/></el-select></el-form-item>
 <el-form-item v-else-if="d.sourceOrder" label="来源订单"><button class="link" @click="go(sale?'sales_order_detail':'purchase_order_detail',d.sourceOrder)">{{d.source_code}}</button></el-form-item>
 <el-form-item v-if="cash" label="参照发票" required><el-select v-model="d.sourceDoc" @change="selectInvoice"><el-option v-for="i in invoices" :key="i.id" :value="i.id" :label="'#'+i.id+' '+i.party_name+' '+money(i.amount)"/></el-select></el-form-item>
 <el-form-item v-if="!financial" :label="transfer?'调出仓库':'仓库'" required><el-select v-model="d.warehouse" :disabled="ship||ret"><el-option v-for="w in locations" :key="w.id" :value="w.id" :label="w.name"/></el-select></el-form-item>
 <el-form-item v-if="transfer" label="调入仓库" required><el-select v-model="d.targetWarehouse"><el-option v-for="w in locations" :key="w.id" :value="w.id" :label="w.name"/></el-select></el-form-item>
 <el-form-item v-if="financial" :label="cash?'本次核销金额':'含税金额'" required><el-input-number v-model="d.amount" :min="0" :precision="2"/></el-form-item>
 <el-form-item class="full" label="备注"><el-input v-model="d.memo" type="textarea" maxlength="250"/></el-form-item></el-form></div>
 <div v-if="!financial" class="box"><div class="bar"><h3>商品明细</h3><el-button v-if="mode==='form'&&!ship&&!ret" @click="d.lines.push(blank())">添加明细行</el-button></div>
 <el-table :data="d.lines"><el-table-column type="index" label="序号" width="65"/>
 <el-table-column label="商品编码"><template #default="{row}">{{sku(row.sku).code||'—'}}</template></el-table-column>
 <el-table-column label="商品名称" min-width="180"><template #default="{row}"><el-select v-if="mode==='form'&&!ship&&!ret" v-model="row.sku"><el-option v-for="s in setup.skus.filter((s:Row)=>s.kind==='HARDWARE')" :key="s.id" :value="s.id" :label="s.name"/></el-select><span v-else>{{sku(row.sku).name}}</span></template></el-table-column>
 <el-table-column :label="stockTaking?'实盘数量':'数量'" min-width="125"><template #default="{row}"><el-input-number v-if="mode==='form'&&!ship" v-model="row.quantity" :min="stockTaking?0:1" :max="exchange?1:10000" :precision="0" :controls="false" style="width:100%"/><span v-else>{{row.quantity}}</span></template></el-table-column>
 <el-table-column label="ICCID / 卡号" min-width="170"><template #default="{row}"><template v-if="sku(row.sku).kind&&sku(row.sku).kind!=='HARDWARE'"><el-button v-if="mode==='form'" link type="primary" @click="openCards(row)">{{inbound?'录入 ID 卡号':sale&&ret?'选择原卡':'选择库存 ID'}}（{{row.cards?.length||0}}/{{row.quantity}}）</el-button><span v-else>{{row.cards?.map((c:Row)=>c.iccid).join('、')}}</span></template><span v-else>—</span></template></el-table-column>
 <el-table-column v-if="exchange" label="替换卡 ICCID" min-width="215"><template #default="{row}"><el-select v-if="mode==='form'" v-model="row.replacement" filterable placeholder="选择同商品在库卡"><el-option v-for="c in allCards.filter(c=>!c.customer_id && c.warehouse_id===d.warehouse && c.sku_id===row.sku)" :key="c.iccid" :value="c.iccid" :label="c.iccid"/></el-select><span v-else>{{row.replacement}}</span></template></el-table-column>
 <el-table-column v-if="mode==='form'&&!ship" label="操作" width="70"><template #default="s"><el-button link type="danger" @click="d.lines.splice(s.$index,1)">移除</el-button></template></el-table-column></el-table></div>
 <div v-if="mode==='form'" class="form-actions"><el-button @click="go(item.key)">取消</el-button><el-button :loading="busy" @click="run(()=>save(false))">保存草稿</el-button><el-button type="primary" :loading="busy" @click="run(()=>save(true))">保存并提交</el-button></div></template>
 <el-dialog v-model="cardDialog" :title="inbound?'录入 ICCID 号 / 卡号':'选择库存 ID'" width="620px" :close-on-click-modal="false"><el-form label-position="top">
 <template v-if="inbound"><el-form-item label="默认移动集团账号"><el-select v-model="carrier"><el-option v-for="a in setup.carriers" :key="a.id" :value="a.id" :label="a.name+' #'+a.id"/></el-select></el-form-item>
 <el-form-item label="默认单卡额度（KB）"><el-input-number v-model="quota" :min="0"/></el-form-item><el-form-item label="已知首次激活日"><el-date-picker v-model="activated" value-format="YYYY-MM-DD"/></el-form-item>
 <el-form-item v-if="sku(cardLine.sku).kind==='TERMINAL'" label="客户套餐 / 周期月数"><el-input v-model="planName" placeholder="套餐名称"/><el-input-number v-model="cycleMonths" :min="1" :max="24"/></el-form-item>
 <el-form-item label="每行一张卡，格式为 ICCID,集团账号编号,额度KB,首次激活日,套餐名称,周期月数。空值沿用默认设置"><el-input v-model="entered" type="textarea" :rows="6"/></el-form-item>
 <p class="subtitle">提交入库后，系统按集团账号自动回填卡号、IMSI、IMEI、激活日和资费。进度可在“仓库管理 → 供应商回填”查看。</p></template>
 <template v-else><el-form-item label="可选 ICCID"><el-select v-model="picked" filterable multiple style="width:100%"><el-option v-for="c in chosenCards" :key="c.iccid" :value="c.iccid" :label="c.iccid"/></el-select></el-form-item>
 <el-form-item v-if="kind==='sales_outbound'" label="ERP 销售有效期"><el-date-picker v-model="expiry" value-format="YYYY-MM-DD"/></el-form-item></template></el-form>
 <template #footer><el-button @click="cardDialog=false">取消</el-button><el-button type="primary" @click="applyCards">确认</el-button></template></el-dialog></template>
