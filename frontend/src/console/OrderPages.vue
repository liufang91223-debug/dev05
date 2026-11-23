<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import ListFrame from './ListFrame.vue'
import {readBook,saveBook} from './workbook'
import {day,money,api,go,status,type Row} from './api'
import {board,type MenuItem} from './pages'
import copy from './board-copy.json'
const props=defineProps<{item:MenuItem;routeKey:string;recordId?:string;user:Row}>()
const sale=props.item.source==='SALE', partyLabel=sale?'客户':'供应商', pendingName=sale?'待发货':'待收货'
const mode=computed(()=>props.routeKey===props.item.key?'list':props.routeKey.endsWith('_form')?'form':props.routeKey.endsWith('_import')?'import':props.routeKey.endsWith('_export')?'export':'detail')
const rows=ref<Row[]>([]),setup=ref<Row>({parties:[],skus:[],accounts:[]}),refs=ref<Row[]>([]),settlements=ref<Row[]>([])
const entry=ref<Row>({}),lines=ref<Row[]>([]),history=ref<Row[]>([]),busy=ref(false),changes=ref(false)
const emptyLine=()=>({sku:null,quantity:1,price:0,taxRate:13,carrierAccount:null,internalNote:'',memo:''})
const head=ref<Row>({businessDay:day(),deliveryDay:'',manager:props.user.username,warehouse:1,settlement:'',externalCode:'',notes:'',discount:0})
const party=ref<number|null>(null),gross=computed(()=>lines.value.reduce((a,l)=>a+Number(l.quantity||0)*Number(l.price||0),0))
const quantity=computed(()=>lines.value.reduce((a,l)=>a+Number(l.quantity||0),0))
const partners=computed(()=>setup.value.parties.filter((p:Row)=>p.kind===(sale?'CUSTOMER':'SUPPLIER')))
const meta=(copy as Record<string,Row>)[props.item.key]||{}
const cols=[{key:'code',label:sale?'订单编号':'采购单号',link:true,wide:176},{key:'party_code',label:partyLabel+'编码'},{key:'party_name',label:partyLabel+'名称',wide:165},{key:'filter_date',label:sale?'订单日期':'采购日期'},
 {key:'display_total',label:sale?'订单总金额':'采购总金额',numeric:true},{key:'delivery_day',label:sale?'交货日期':'到货日期'},{key:'display_state',label:'订单状态'},...(sale?[{key:'manager',label:'客户经理'}]:[]),{key:'external_code',label:sale?'客户订单号':'供方单号'}]
const stats=computed<[string,any,string?][]>(()=>{const month=rows.value.filter(r=>r.filter_date?.startsWith(day().slice(0,7)));return [
 [sale?'本月订单数':'本月采购订单',month.length,'笔'],[sale?'本月销售金额':'本月采购金额',money(month.reduce((a,r)=>a+Number(r.total),0))],[sale?'待审批订单':'待收货订单',rows.value.filter(r=>r.state===(sale?'PENDING':'APPROVED')).length,'笔']]})
function enrich(o:Row){const date=String(o.business_day||o.created_at).slice(0,10);return {...o,filter_date:date,code:o.order_code,display_total:money(o.total),
 display_state:status(o.state,props.item.key)}}
async function load(){
 const [s,w,z,all]=await Promise.all([api('/erp/setup'),api('/erp/register/warehouse'),api('/erp/register/settlement'),api<Row[]>('/erp/orders')])
 setup.value=s;refs.value=w.filter((r:Row)=>r.enabled);settlements.value=z
 rows.value=all.filter(r=>r.direction===props.item.source).map(enrich)
 if(props.recordId){
  const o=await api('/erp/orders/'+props.recordId)
  if(o.order.direction!==props.item.source)throw Error('单据类型与页面不符')
  entry.value=enrich(o.order);history.value=o.history;party.value=o.order.party_id
  head.value={businessDay:entry.value.filter_date,deliveryDay:o.order.delivery_day,manager:o.order.manager||'',warehouse:o.order.warehouse_id||1,settlement:o.order.settlement||'',
   externalCode:o.order.external_code||'',notes:o.order.notes||'',discount:Number(o.order.discount||0)}
  lines.value=o.lines.map((r:Row)=>({sku:r.sku_id,quantity:r.quantity,price:Number(r.unit_price),carrierAccount:r.carrier_account_id,taxRate:Number(r.tax_rate??13),internalNote:r.internal_note||'',memo:r.memo||''}))
 }else lines.value=[emptyLine()]
}
async function run(work:()=>Promise<void>){if(busy.value)return;busy.value=true;try{await work()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error((e as Error).message)}finally{busy.value=false}}
function sku(id:number){return setup.value.skus.find((s:Row)=>s.id===id)||{}}
function back(){go(props.item.key)}
async function save(submit:boolean){
 if(!party.value||!lines.value.length||lines.value.some(l=>!l.sku||l.quantity<=0))throw Error('请选择往来单位和商品，填写明细数量')
 if(submit && !head.value.deliveryDay)throw Error('提交时须填写交货或到货日期')
 let id=props.recordId||entry.value.id
 const payload={direction:props.item.source,party:party.value,lines:lines.value,header:{...head.value,deliveryDay:head.value.deliveryDay||null}}
 if(id)await api('/erp/orders/'+id,'PUT',payload);else {id=(await api('/erp/orders','POST',payload)).id;entry.value.id=id}
 changes.value=false
 if(submit)await api('/erp/orders/'+id+'/submit','POST')
 ElMessage.success(submit?'已提交审批':'草稿已保存');go(board(props.item,'detail'),id)
}
async function decide(approve:boolean){
 const reason=approve?'':(await ElMessageBox.prompt('请输入驳回原因','驳回订单',{inputPattern:/\S/,inputErrorMessage:'必填'})).value
 await api('/erp/orders/'+props.recordId+'/decision','POST',{approved:approve,reason,round:entry.value.round_no,step:entry.value.current_step})
 await load();ElMessage.success(approve?'审批已处理':'已退回草稿')
}
async function downloadLines(){
 await saveBook(props.item.label+'明细',[{name:'订单明细',columns:['商品编码','商品名称','数量','含税单价','税率','含税金额','备注'],rows:lines.value.map(l=>[sku(l.sku).code,sku(l.sku).name,l.quantity,l.price,l.taxRate,Number((l.quantity*l.price).toFixed(2)),l.memo])}])
}
function leave(){if(!changes.value||window.confirm('当前内容尚未保存，确定离开？'))back()}
const file=ref<File>(),preview=ref<Row[]>([]),issues=ref<string[]>([])
async function readFile(){
 if(!file.value)throw Error('请选择 Excel 或 CSV 文件')
 if(file.value.size>10*1024*1024)throw Error('文件超过 10 MB')
 const [heading,...records]=await readBook(file.value)
 if(!heading)throw Error('请选择包含表头和商品行的文件')
 if(heading.join(',')!=='商品编码,数量,含税单价')throw Error('请使用下载模板的三列格式')
 const result:Row[]=[],errors:string[]=[],ids=new Set<number>()
 records.forEach((v,i)=>{const s=setup.value.skus.find((s:Row)=>s.code===v[0]),q=Number(v[1]),p=Number(v[2])
  if(v.length!==3||!s||!Number.isInteger(q)||q<1||!v[2]||!Number.isFinite(p)||p<0||ids.has(s?.id))errors.push('第 '+(i+2)+' 行的商品、数量、单价或重复商品有误')
  else {ids.add(s.id);result.push({...emptyLine(),sku:s.id,quantity:q,price:p})}})
 preview.value=result;issues.value=errors
}
function applyImport(){sessionStorage.setItem('ys-import-'+props.item.key,JSON.stringify(preview.value));go(board(props.item,'form'))}
onMounted(()=>run(async()=>{await load();if(mode.value==='form'&&!props.recordId){const k='ys-import-'+props.item.key,v=sessionStorage.getItem(k);if(v){lines.value=JSON.parse(v);sessionStorage.removeItem(k)}}}))
</script>
<template>
<ListFrame v-if="mode==='list'||mode==='export'" :page="item.key" :user-key="user.username" :title="mode==='export'?item.label+'明细导出':meta.title||item.label+'列表'" :subtitle="meta.subtitle" :rows="rows" :columns="cols" :stats="stats" :busy="busy" :keyword="'订单编号 / '+partyLabel+'名称'+(sale?' / 客户经理':'')" :statuses="['草稿','待审批',pendingName,'已完成']" :date-label="sale?'下单日期':'采购日期'" export-label="导出列表 CSV" :new-label="sale?'新建订单':'新建采购订单'" @create="go(board(item,'form'))" @open="r=>go(board(item,'detail'),r.id)">
 <template #actions><el-button @click="go(item.key.replace('_list','_export'))">明细导出</el-button><el-button @click="go(item.key.replace('_list','_import'))">明细导入</el-button></template></ListFrame>
<template v-else-if="mode==='import'"><div class="page-head"><h1>{{item.label}}明细导入</h1><el-button @click="back">返回列表</el-button></div>
 <div class="box"><el-steps :active="preview.length?2:file?1:0"><el-step title="上传"/><el-step title="校验"/><el-step title="导入草稿"/></el-steps>
 <p>上传包含商品编码、数量、含税单价的 Excel 或 CSV，创建一张新订单草稿。</p><input type="file" accept=".xlsx,.xls,.csv" @change="e=>{file=(e.target as HTMLInputElement).files?.[0];preview=[];issues=[]}"/>
 <el-button @click="saveBook('订单明细模板',[{name:'订单明细',columns:['商品编码','数量','含税单价'],rows:[]}])">下载模板</el-button><el-button @click="run(readFile)">校验</el-button>
 <p v-for="msg in issues" :key="msg" class="negative">{{msg}}</p><el-table :data="preview"><el-table-column label="商品"><template #default="{row}">{{sku(row.sku).name}}</template></el-table-column><el-table-column prop="quantity" label="数量"/><el-table-column prop="price" label="含税单价"/></el-table><el-button type="primary" :disabled="!preview.length||!!issues.length" @click="applyImport">带入订单草稿</el-button></div></template>
<template v-else><div class="page-head"><div><h1>{{mode==='form'?(recordId?'编辑':'新建')+item.label:entry.code||item.label+'详情'}}</h1><p class="subtitle">{{item.label}} · {{mode==='form'?'数据维护':entry.display_state}}</p></div><div>
 <el-button @click="leave">返回列表</el-button><el-button v-if="mode==='detail'" @click="downloadLines">导出明细</el-button><el-button v-if="mode==='detail'&&entry.state==='FULFILLED'" @click="go(sale?'sales_order_id_list':'purchase_order_id_list',undefined,'?order='+entry.id)">查看卡号</el-button><a v-if="entry.id" :href="'/api/erp/orders/'+entry.id+'/pdf'" class="pdf">下载 PDF</a>
 <el-button v-if="mode==='detail'&&entry.state==='DRAFT'" type="primary" @click="go(board(item,'form'),recordId)">编辑订单</el-button>
 <template v-if="mode==='detail'&&entry.state==='PENDING'"><el-button @click="run(()=>decide(false))">驳回</el-button><el-button type="primary" @click="run(()=>decide(true))">审批通过</el-button></template>
 <el-button v-if="mode==='detail'&&entry.state==='APPROVED'" type="primary" @click="go(sale?'sales_outbound_form':'purchase_inbound_form',undefined,'?source='+recordId)">{{sale?'生成销售出库单':'生成采购入库单'}}</el-button>
 </div></div>
 <div v-if="mode==='detail'" class="box"><el-steps :active="({DRAFT:0,PENDING:1,APPROVED:2,FULFILLED:3} as Row)[entry.state]" finish-status="success"><el-step title="创建订单"/><el-step title="等待审批"/><el-step :title="sale?'出库发货':'到货入库'"/><el-step :title="sale?'回款跟踪':'付款跟踪'"/></el-steps></div>
 <div class="box"><h3>{{mode==='form'?'订单基本信息':'订单信息'}}</h3>
 <el-form v-if="mode==='form'" class="gridform" label-position="top" @change="changes=true">
 <el-form-item :label="partyLabel+'名称'" required><el-select v-model="party" filterable><el-option v-for="p in partners" :key="p.id" :value="p.id" :label="p.name"/></el-select></el-form-item>
 <el-form-item label="订单编号"><el-input :model-value="entry.code||'保存后自动生成'" disabled/></el-form-item>
 <el-form-item :label="sale?'订单日期':'采购日期'" required><el-date-picker v-model="head.businessDay" value-format="YYYY-MM-DD"/></el-form-item>
 <el-form-item :label="sale?'交货日期':'到货日期'" required><el-date-picker v-model="head.deliveryDay" value-format="YYYY-MM-DD"/></el-form-item>
 <el-form-item :label="sale?'客户经理':'采购人员'"><el-input v-model="head.manager"/></el-form-item>
 <el-form-item :label="sale?'发货仓库':'收货仓库'"><el-select v-model="head.warehouse"><el-option v-for="w in refs" :key="w.id" :value="w.id" :label="w.name"/></el-select></el-form-item>
 <el-form-item label="结算方式"><el-select v-model="head.settlement" clearable><el-option v-for="s in settlements" :key="s.id" :value="s.name" :label="s.name"/></el-select></el-form-item>
 <el-form-item :label="sale?'客户订单号':'供方单号'"><el-input v-model="head.externalCode"/></el-form-item><el-form-item class="full" label="订单备注"><el-input v-model="head.notes" type="textarea" maxlength="250"/></el-form-item></el-form>
 <div v-else class="detail-grid"><div v-for="[label,value] in [[partyLabel+'名称',entry.party_name],['订单编号',entry.code],['订单日期',head.businessDay],['交货日期',head.deliveryDay],[sale?'客户经理':'采购人员',head.manager],['仓库',refs.find(w=>w.id===head.warehouse)?.name],['结算方式',head.settlement],['对方订单号',head.externalCode],['备注',head.notes]]" :key="label"><small>{{label}}</small>{{value||'—'}}</div></div></div>
 <div class="box"><div class="bar"><h3>订单明细 · {{lines.length}} 个品项</h3><el-button v-if="mode==='form'" @click="lines.push(emptyLine());changes=true">添加明细</el-button></div>
 <el-table :data="lines"><el-table-column type="index" label="序号" width="60"/><el-table-column label="商品编码" min-width="130"><template #default="{row}">{{sku(row.sku).code||'—'}}</template></el-table-column>
 <el-table-column label="商品名称" min-width="190"><template #default="{row}"><el-select v-if="mode==='form'" v-model="row.sku" filterable @change="row.price=Number(sku(row.sku).price);changes=true"><el-option v-for="s in setup.skus" :key="s.id" :value="s.id" :label="s.name"/></el-select><span v-else>{{sku(row.sku).name}}</span></template></el-table-column>
 <el-table-column label="商品类型" min-width="110"><template #default="{row}">{{status(sku(row.sku).kind)}}</template></el-table-column>
 <el-table-column v-for="[field,label] in [['quantity','数量'],['price','含税单价']]" :key="field" :label="label" min-width="130"><template #default="{row}"><el-input-number v-if="mode==='form'" v-model="row[field]" :min="field==='quantity'?1:0" :precision="field==='quantity'?0:2" :controls="false" style="width:100%" @change="changes=true"/><span v-else>{{row[field]}}</span></template></el-table-column>
 <el-table-column v-if="!sale" label="移动集团账号" min-width="150"><template #default="{row}"><el-select v-if="mode==='form'&&row.sku&&sku(row.sku).kind!=='HARDWARE'" v-model="row.carrierAccount" clearable><el-option v-for="a in setup.carriers" :key="a.id" :value="a.id" :label="a.name"/></el-select><span v-else>{{setup.carriers?.find((a:Row)=>a.id===row.carrierAccount)?.name||'—'}}</span></template></el-table-column>
 <el-table-column label="税率" width="90"><template #default="{row}"><el-select v-if="mode==='form'" v-model="row.taxRate"><el-option v-for="n in [0,3,6,9,13]" :key="n" :value="n" :label="n+'%'"/></el-select><span v-else>{{row.taxRate}}%</span></template></el-table-column>
 <el-table-column label="含税金额" min-width="120"><template #default="{row}">{{money(row.quantity*row.price)}}</template></el-table-column>
 <el-table-column v-for="[k,l] in [['internalNote','内部备注'],['memo','备注']]" :key="k" :label="l" min-width="135"><template #default="{row}"><el-input v-if="mode==='form'" v-model="row[k]"/><span v-else>{{row[k]||'—'}}</span></template></el-table-column>
 <el-table-column v-if="mode==='form'" fixed="right" label="操作" width="70"><template #default="s"><el-button link type="danger" @click="lines.splice(s.$index,1);changes=true">删除</el-button></template></el-table-column></el-table>
 <div class="total-line"><p>数量合计 {{quantity}}</p><p>商品金额 {{money(gross)}}</p>优惠金额 <el-input-number v-if="mode==='form'" v-model="head.discount" :min="0" :max="gross" :precision="2" size="small"/><span v-else>{{money(head.discount)}}</span><strong>含税合计 {{money(gross-head.discount)}}</strong></div></div>
 <div v-if="mode==='detail'" class="box"><h3>操作记录</h3><el-empty v-if="!history.length" description="尚无审批记录"/><div class="bar" v-for="r in history" :key="r.id"><span>{{({SUBMIT:'提交审批',APPROVE:'审批通过',REJECT:'驳回'} as Row)[r.action]}} · {{setup.accounts.find((a:Row)=>a.id===r.actor_id)?.username}} {{r.reason}}</span><small>{{r.created_at}}</small></div></div>
 <div v-else class="form-actions"><el-button @click="leave">取消</el-button><el-button :loading="busy" @click="run(()=>save(false))">保存草稿</el-button><el-button type="primary" :loading="busy" @click="run(()=>save(true))">{{setup.approvalDirections?.includes(item.source)?'提交审批':'保存并送审'}}</el-button></div>
 </template>
</template>
<style scoped>.pdf{display:inline-block;padding:8px 12px;margin-left:8px;border:1px solid #dce4e6;border-radius:5px;background:white}.el-form-item .el-date-editor{width:100%}</style>
