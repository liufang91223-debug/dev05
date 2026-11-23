<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage} from 'element-plus'
import ListFrame from './ListFrame.vue'
import {api,go,status,money,type Row} from './api'
import {board,type MenuItem} from './pages'
import copy from './board-copy.json'
const props=defineProps<{item:MenuItem;routeKey:string;recordId?:string;user:Row}>()
const src=props.item.source, isGoods=src==='goods', partner=['supplier','customer'].includes(src)
const mode=props.routeKey===props.item.key?'list':props.routeKey===board(props.item,'detail')?'detail':'form'
const all=ref<Row[]>([]),entry=ref<Row>({enabled:true,kind:'HARDWARE',price:0}),busy=ref(false)
const related=ref<Row[]>([]), lookup=ref<Record<string,Row[]>>({})
const names:Record<string,string>={goods:'商品',customer:'客户',supplier:'供应商',category:'商品分类',unit:'计量单位',operator:'运营商',warehouse:'仓库',settlement:'结算方式'}
const manualCode=src==='category'||src==='settlement'
const noun=names[src],meta=(copy as Record<string,Row>)[props.item.key]||{}
const fieldSets:Record<string,Record<string,string>>={
 goods:{public_name:'客户门户商品名称',spec:'规格型号',category:'商品分类',unit:'基本单位',brand:'品牌',safety_stock:'安全库存',memo:'备注'},
 customer:{level:'客户等级',manager:'客户经理',contact:'联系人',phone:'联系电话',settlement:'收款条件',expiry:'合同到期日',address:'地址',memo:'备注'},
 supplier:{level:'供应商等级',manager:'采购负责人',contact:'联系人',phone:'联系电话',settlement:'付款条件',address:'地址',memo:'备注'},
 warehouse:{wh_type:'仓库类型',address:'地址',manager:'负责人',phone:'联系方式',locations:'库位',memo:'备注'},
 category:{parent:'上级分类',memo:'备注'},unit:{symbol:'单位符号',memo:'备注'},
 operator:{contact:'对接人',phone:'联系电话',memo:'备注'},settlement:{basis:'账期起算点',days:'账期天数',memo:'备注'}}
const fields=fieldSets[src]
const cols=computed(()=>[{key:'code',label:noun+'编码',link:true,wide:145},{key:'name',label:noun+'名称',wide:160},...Object.entries(fields).slice(0,4).map(([key,label])=>({key,label})),
 ...(isGoods?[{key:'display_kind',label:'商品类型'},{key:'display_price',label:'销售价'}]:[]),{key:'display_state',label:'状态'}])
const state=(r:Row)=>r.enabled?(isGoods?'在售':'启用'):(isGoods?'停售':'停用')
const stats=computed<[string,any,string?][]>(()=>[[noun+'总数',all.value.length],[isGoods?'在售商品':'已启用',all.value.filter(r=>r.enabled).length],[isGoods?'停售商品':'已停用',all.value.filter(r=>!r.enabled).length]])
async function load(){
 all.value=(await api<Row[]>('/erp/register/'+src)).map(r=>({...r,display_kind:status(r.kind),display_price:money(r.price),display_state:state(r)}))
 if(props.recordId){
  const r=all.value.find(r=>r.id===Number(props.recordId));if(!r)throw Error('档案不存在');entry.value={...r}
  if(partner)related.value=(await api<Row[]>('/erp/orders')).filter(o=>o.party_id===r.id)
 }
 for(const k of Object.keys(fields).filter(k=>['category','unit','settlement','parent'].includes(k))){
  lookup.value[k]=(await api<Row[]>('/erp/register/'+(k==='parent'?'category':k))).filter(x=>x.enabled && !(k==='parent' && x.id===entry.value.id))
 }
}
async function save(){
 busy.value=true
 try{const data=entry.value, details=Object.fromEntries(Object.keys(fields).map(k=>[k,String(data[k]??'')]))
 const body={name:data.name,code:data.code,kind:data.kind,price:isGoods?Number(data.price||0):null,enabled:data.enabled,description:data.description||'',details}
 const result=await api('/erp/register/'+src+(data.id?'/'+data.id:''),data.id?'PUT':'POST',body);ElMessage.success('档案已保存');go(board(props.item,'detail'),result.id)
 }catch(e){ElMessage.error((e as Error).message)}finally{busy.value=false}
}
onMounted(()=>load().catch(e=>ElMessage.error(e.message)))
</script>
<template><ListFrame v-if="mode==='list'" :page="item.key" :user-key="user.username" :title="meta.title||item.label" :subtitle="meta.subtitle" :columns="cols" :rows="all" :stats="stats" :statuses="isGoods?['在售','停售']:['启用','停用']" :keyword="noun+'名称 / 编码'" :new-label="'新增'+noun" :export-label="'导出'+noun" @open="r=>go(board(item,'detail'),r.id)" @create="go(board(item,'form'))"/>
<template v-else><div class="page-head"><div><h1>{{mode==='form'?(recordId?'编辑':'新增')+noun:entry.name}}</h1><p class="subtitle">{{item.label}} · {{entry.code||'数据维护'}}</p></div><div><el-button @click="go(item.key)">返回列表</el-button><el-button v-if="mode==='detail'" type="primary" @click="go(board(item,'form'),recordId)">编辑</el-button></div></div>
<div class="box"><h3>基本信息</h3><el-form v-if="mode==='form'" class="gridform" label-position="top" @submit.prevent="save">
 <el-form-item :label="noun+'名称'" required><el-input v-model="entry.name" maxlength="120"/></el-form-item>
 <el-form-item :label="noun+'编码'" :required="manualCode"><el-input v-model="entry.code" :disabled="!manualCode" :placeholder="manualCode?'请输入编码':'保存后自动生成'"/></el-form-item>
 <el-form-item label="状态"><el-switch v-model="entry.enabled" active-text="启用" inactive-text="停用"/></el-form-item>
 <template v-if="isGoods"><el-form-item label="商品类型"><el-select v-model="entry.kind"><el-option v-for="k in ['HARDWARE','TERMINAL','GROUP']" :key="k" :value="k" :label="status(k)"/></el-select></el-form-item><el-form-item label="销售价"><el-input-number v-model="entry.price" :min="0" :precision="2"/></el-form-item></template>
 <el-form-item v-for="(label,k) in fields" :key="k" :label="label" :class="{full:k==='memo'}">
 <el-date-picker v-if="k==='expiry'" v-model="entry[k]" value-format="YYYY-MM-DD"/>
 <el-select v-else-if="lookup[k]" v-model="entry[k]" clearable filterable><el-option v-for="r in lookup[k]" :key="r.id" :label="r.name" :value="r.name"/></el-select>
 <el-select v-else-if="k==='basis'" v-model="entry[k]"><el-option label="发票账期" value="发票账期"/><el-option label="货到账期" value="货到账期"/></el-select>
 <el-input v-else v-model="entry[k]" :type="k==='memo'?'textarea':'text'" maxlength="250"/></el-form-item></el-form>
 <div v-else class="detail-grid"><div v-for="c in [...cols,...Object.entries(fields).filter(([key])=>!cols.some(c=>c.key===key)).map(([key,label])=>({key,label}))]" :key="c.key"><small>{{c.label}}</small>{{entry[c.key]||'—'}}</div></div></div>
 <div v-if="mode==='detail'&&partner" class="box"><h3>关联订单</h3><el-table :data="related"><el-table-column label="订单编号"><template #default="{row}"><button class="link" @click="go(src==='customer'?'sales_order_detail':'purchase_order_detail',row.id)">{{row.order_code}}</button></template></el-table-column><el-table-column prop="business_day" label="订单日期"/><el-table-column prop="total" label="订单金额"/></el-table></div>
 <div v-if="mode==='form'" class="form-actions"><el-button @click="go(item.key)">取消</el-button><el-button type="primary" :loading="busy" @click="save">保存</el-button></div></template></template>
