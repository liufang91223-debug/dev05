<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {api, csv, go, status, type Row} from './api'
import type {MenuItem} from './pages'
import {saveBook} from './workbook'
const props=defineProps<{item:MenuItem;routeKey:string}>()
const warehouse=props.item.area==='doc'
const sale=['SALE','sales','sales_outbound'].includes(props.item.source)
const title=props.item.label+'明细导出'
const labels:Record<string,string>=warehouse
  ? {doc_code:'单据编号',date:'单据日期',order_code:'来源订单',party_name:'往来单位',warehouse_name:'仓库',kind:'业务类型',code:'商品编码',name:'商品名称',quantity:'数量',iccid:'ICCID',msisdn:'卡号'}
  : {order_code:'订单编号',date:'订单日期',party_name:sale?'客户':'供应商',external_code:'对方单号',manager:'经办人',warehouse_name:'仓库',delivery_day:'交货日期',code:'商品编码',name:'商品名称',quantity:'数量',unit_price:'含税单价',gross:'明细含税金额',tax_rate:'税率（%）',display_state:'订单状态',memo:'明细备注'}
const fields=ref(Object.keys(labels)), rows=ref<Row[]>([]), dates=ref<string[]>([]), keyword=ref(''), format=ref('xlsx'), busy=ref(false)
const filtered=computed(()=>rows.value.filter(r=>(!dates.value?.length||(r.date>=dates.value[0]&&r.date<=dates.value[1])) &&
 [r.doc_code,r.order_code,r.party_name,r.code,r.name,r.iccid].some(v=>String(v??'').toLowerCase().includes(keyword.value.trim().toLowerCase()))))
onMounted(async()=>{
  busy.value=true
  try {
    if(!warehouse){
      const data=await api<Row[]>('/erp/reports/'+(sale?'sales':'purchase'))
      rows.value=data.map(r=>({...r,date:r.business_day,display_state:status(r.state,sale?'sales_order':'purchase_order')}))
    }else{
      const base=sale?'sales_outbound':'purchase_inbound', ret=sale?'sales_return':'purchase_return'
      const [docs,returns,setup,cards]=await Promise.all([api<Row[]>('/erp/documents?kind='+base),api<Row[]>('/erp/documents?kind='+ret),api('/erp/setup'),api<Row[]>('/erp/cards')])
      const sku=new Map<number,Row>(setup.skus.map((r:Row)=>[r.id,r])), phone=new Map(cards.map(r=>[r.iccid,r.msisdn]))
      rows.value=[...docs,...returns].filter(d=>d.state==='POSTED').sort((a,b)=>b.id-a.id).flatMap(d=>{
        const returning=d.kind===ret, sign=returning?-1:1
        const lines:Row[]=typeof d.lines==='string'?JSON.parse(d.lines):d.lines
        return lines.flatMap(l=>{
          const item=sku.get(l.sku), product={date:d.business_day,doc_code:d.doc_code,order_code:d.source_code,kind:returning?'退货':props.item.label,party_name:d.party_name,warehouse_name:d.warehouse_name,code:item?.code,name:item?.name}
          if(l.cards?.length)return l.cards.map((c:Row)=>({...product,quantity:sign,iccid:c.iccid,msisdn:phone.get(c.iccid)||''}))
          return [{...product,quantity:l.quantity*sign,iccid:'',msisdn:''}]
        })
      })
    }
  }catch(e){ElMessage.error((e as Error).message)}finally{busy.value=false}
})
async function download(){
  const selected=Object.keys(labels).filter(k=>fields.value.includes(k))
  const headings=selected.map(k=>labels[k]),data=filtered.value.map(r=>selected.map(k=>r[k]??''))
  try{if(format.value==='xlsx')await saveBook(title,[{name:'业务明细',columns:headings,rows:data}]);else csv(title,headings,data)}
  catch(e){ElMessage.error((e as Error).message)}
}
</script>
<template><div class="page-head"><h1>{{title}}</h1><el-button @click="go(item.key)">返回列表</el-button></div>
<div class="box filters"><label>业务日期<el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期"/></label>
<label>单号 / 往来单位 / 商品<el-input v-model="keyword" clearable placeholder="筛选导出范围"/></label>
<label>文件格式<el-radio-group v-model="format"><el-radio-button value="xlsx">Excel</el-radio-button><el-radio-button value="csv">CSV</el-radio-button></el-radio-group></label></div>
<div class="box"><h3>导出字段</h3><el-checkbox-group v-model="fields"><el-checkbox v-for="(label,k) in labels" :key="k" :value="k">{{label}}</el-checkbox></el-checkbox-group></div>
<div class="box" v-loading="busy"><div class="bar"><strong>预览前 10 行 · 共 {{filtered.length}} 行</strong><el-button type="primary" :disabled="busy||!fields.length||!filtered.length" @click="download">导出文件</el-button></div>
<el-table :data="filtered.slice(0,10)" empty-text="没有符合条件的明细"><el-table-column v-for="k in Object.keys(labels).filter(k=>fields.includes(k))" :key="k" :prop="k" :label="labels[k]" min-width="130" show-overflow-tooltip/></el-table></div></template>
