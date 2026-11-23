<script setup lang="ts">
import {ref,computed,onMounted} from 'vue'
import {ElMessage} from 'element-plus'
import {api,go,money,day,numCode, type Row} from './api'
defineProps<{user:Row}>()
const orders=ref<Row[]>([]), cards=ref<Row[]>([]), balance=ref<Row[]>([]), docs=ref<Row[]>([]), clients=ref<Row[]>([])
const todos=computed(()=>[...orders.value.filter(o=>o.state==='PENDING').map(o=>({label:o.direction==='SALE'?'销售订单':'采购订单',code:numCode(o.direction==='SALE'?'SO':'PO',o.id,o.business_day||o.created_at),name:o.party_name,path:o.direction==='SALE'?'sales_order_detail':'purchase_order_detail',id:o.id})),
 ...docs.value.filter(o=>o.state==='PENDING').map(o=>({label:'业务单据',code:'#'+o.id,name:o.party_name||o.warehouse_name,path:o.kind+'_detail',id:o.id}))])
const expiring=computed(()=>clients.value.filter(c=>String(c.expiry||'').startsWith(day().slice(0,7))).sort((a,b)=>String(a.expiry).localeCompare(b.expiry)))
const kpis=computed(()=>[
 ['本月内到期客户',expiring.value.length,'家'],['本月销售额',money(orders.value.filter(o=>o.direction==='SALE'&&['APPROVED','FULFILLED'].includes(o.state)&&String(o.business_day||o.created_at).startsWith(day().slice(0,7))).reduce((sum,o)=>sum+Number(o.total),0)),''],
 ['本月待开票客户',balance.value.filter(o=>Number(o.receivable)>Number(o.invoiced)).length,'家'],['待收款客户',balance.value.filter(o=>Number(o.outstanding)>0).length,'家']])
onMounted(async()=>{try{[orders.value,clients.value,balance.value,docs.value]=await Promise.all([api('/erp/orders'),api('/erp/register/customer'),api('/erp/reports/receivable'),api('/erp/documents')])}catch(e){ElMessage.error((e as Error).message)}})
</script>
<template><div class="page-head"><div><h1>你好，{{user.username}}</h1><p class="subtitle">{{day()}} · 今天有 {{todos.length}} 项待办需要关注</p></div></div>
  <div class="stats"><div v-for="[label,value,unit] in kpis" :key="label" class="metric"><small>{{label}}</small><strong>{{value}}<i>{{unit}}</i></strong></div></div>
  <div class="section-grid"><div class="box"><h3>本月内到期客户提醒</h3><p class="muted">按客户档案中的合同到期日排序</p>
    <el-empty v-if="!expiring.length" description="本月暂无到期客户"/><div v-for="r in expiring" :key="r.id" class="bar"><button class="link" @click="go('customer_detail',r.id)">{{r.name}}</button><span>{{r.expiry}}</span></div></div>
    <div class="box"><h3>单据待办</h3><el-empty v-if="!todos.length" description="暂无待处理单据"/><div v-for="t in todos.slice(0,12)" :key="t.path+t.id" class="bar"><div>{{t.label}} <button class="link" @click="go(t.path,t.id)">{{t.code}}</button><small class="muted"> {{t.name}}</small></div><el-tag type="warning">待审核</el-tag></div></div></div>
</template>
