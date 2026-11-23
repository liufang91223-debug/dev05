<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {api,navigate,type Row} from './console/api'

const props=defineProps<{tab:string;username:string}>()
defineEmits<{logout:[]}>()
const tabs:Record<string,string>={orders:'我的订单',cards:'我的流量卡',inquiries:'订购申请'}
const labels:Record<string,string>={id:'编号',order_code:'订单编码',order_id:'正式订单编号',state:'状态',total:'订单金额',created_at:'创建时间',name:'商品名称',quantity:'数量',
  iccid:'ICCID',msisdn:'卡号',imei:'设备 IMEI',plan_name:'套餐名称',activated_on:'首次激活日',erp_expiry:'到期日期',quota_kb:'套餐额度（KB）',
  used_kb:'已用量（KB）',remaining_kb:'剩余量（KB）',starts_on:'周期起始',ends_on:'周期结束（不含）',updated_at:'更新时间'}
const states:Record<string,string>={DRAFT:'草稿',CONVERTED:'已确认转单',PENDING:'待审核',APPROVED:'已审核',FULFILLED:'已出库'}
const known=computed(()=>Object.hasOwn(tabs,props.tab))
const rows=ref<Row[]>([]),catalog=ref<Row[]>([]),keyword=ref(''),page=ref(1),busy=ref(false),dialog=ref(false)
const sku=ref<number>(),quantity=ref(1)
const columns=computed(()=>Object.keys(rows.value[0]||{}))
const filtered=computed(()=>rows.value.filter(r=>columns.value.some(k=>String(r[k]??'').toLowerCase().includes(keyword.value.trim().toLowerCase())) || !keyword.value.trim()))
const pageRows=computed(()=>filtered.value.slice((page.value-1)*20,page.value*20))
watch(keyword,()=>{page.value=1})
async function run(job:()=>Promise<void>){
  if(busy.value)return
  busy.value=true
  try{await job()}catch(e){ElMessage.error((e as Error).message)}finally{busy.value=false}
}
async function load(){if(known.value)rows.value=await api('/portal/'+props.tab)}
async function openInquiry(){catalog.value=await api('/portal/catalog');sku.value=undefined;quantity.value=1;dialog.value=true}
async function submit(){
  if(!sku.value)return
  await api('/portal/inquiries','POST',{sku:sku.value,quantity:quantity.value});dialog.value=false
  ElMessage.success('订购申请已提交');await load()
}
async function refreshUsage(){await api('/portal/cards/refresh','POST');ElMessage.success('已请求更新，稍后刷新即可查看最新用量')}
onMounted(()=>run(load))
</script>
<template>
  <div class="portal-shell">
    <header><strong>云枢客户门户</strong><span>{{username}} <el-button text @click="$emit('logout')">退出</el-button></span></header>
    <nav aria-label="客户自助服务"><a v-for="(label,key) in tabs" :key="key" :href="'/portal/'+key" :aria-current="tab===key?'page':undefined"
      @click.exact.prevent="navigate('/portal/'+key)">{{label}}</a></nav>
    <main v-if="known" v-loading="busy"><div class="page-head"><h1>{{tabs[tab]}}</h1><div>
      <template v-if="tab==='cards'"><el-button @click="run(load)">刷新列表</el-button><el-button type="primary" @click="run(refreshUsage)">更新用量</el-button></template>
      <el-button v-if="tab==='inquiries'" type="primary" @click="run(openInquiry)">提交订购申请</el-button>
    </div></div>
    <div class="portal-filter"><el-input v-model="keyword" clearable aria-label="筛选列表" :placeholder="tab==='cards'?'搜索 ICCID、卡号或套餐':'搜索当前列表'"/><small>共 {{filtered.length}} 条</small></div>
    <el-table :data="pageRows" stripe empty-text="暂无匹配记录"><el-table-column v-for="k in columns" :key="k" :label="labels[k]||k" :min-width="k==='iccid'?200:120">
      <template #default="{row}">{{(k==='state'?states[row[k]]:null)??row[k]??'—'}}</template></el-table-column></el-table>
    <el-pagination v-if="filtered.length>20" v-model:current-page="page" :total="filtered.length" :page-size="20" layout="prev,pager,next"/>
    </main>
    <el-empty v-else description="客户门户页面不存在"><a href="/portal/orders">返回我的订单</a></el-empty>
    <el-dialog v-model="dialog" title="提交订购申请" width="min(440px,92vw)">
      <el-form label-position="top"><el-form-item label="商品" required><el-select v-model="sku" filterable placeholder="请选择商品">
        <el-option v-for="s in catalog" :key="s.id" :value="s.id" :label="s.name"/></el-select></el-form-item>
        <el-form-item label="数量" required><el-input-number v-model="quantity" :min="1" :max="10000" :precision="0"/></el-form-item></el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :disabled="!sku" :loading="busy" @click="run(submit)">提交申请</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>
.portal-shell{min-height:100vh;background:#f2f6fa}.portal-shell header,.portal-shell nav,.portal-shell main{padding-inline:max(20px,calc((100vw - 1200px)/2))}
.portal-shell header{display:flex;align-items:center;justify-content:space-between;min-height:64px;background:#19365c;color:white}.portal-shell header strong{font-size:20px}.portal-shell header button{color:white}
.portal-shell nav{display:flex;gap:20px;background:white;border-bottom:1px solid #dce3ec;padding-block:0}.portal-shell nav a{padding:14px 0;color:#46566d;text-decoration:none}.portal-shell nav a[aria-current]{color:#17559b;border-bottom:3px solid #17559b}
.portal-shell main{padding-top:24px;padding-bottom:40px}.portal-shell h1{margin:0;font-size:24px}.portal-filter{display:flex;gap:15px;align-items:center;margin-bottom:12px;color:#64748b}.portal-filter .el-input{max-width:290px}.portal-shell .el-pagination{margin-top:16px}
</style>
