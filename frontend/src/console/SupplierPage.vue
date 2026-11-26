<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {api,type Row} from './api'

const rows=ref<Row[]>([]),summary=ref<Row>({byStatus:{}}),status=ref(''),busy=ref(false)
const labels:Record<string,string>={QUEUED:'排队中',RUNNING:'查询中',SUCCEEDED:'已完成',PARTIAL:'部分回填',
 RETRYABLE_FAILED:'等待重试',FAILED:'已失败',PERMANENT_FAILED:'永久失败'}
const tones:Record<string,string>={QUEUED:'info',RUNNING:'warning',SUCCEEDED:'success',PARTIAL:'warning',
 RETRYABLE_FAILED:'warning',FAILED:'danger',PERMANENT_FAILED:'danger'}
const options=Object.entries(labels).map(([value,label])=>({value,label}))
const stats=computed(()=>{
 const by=summary.value.byStatus||{}
 return [['回填任务',Object.values(by).reduce((a:number,v:any)=>a+Number(v||0),0),'条'],
  ['等待中',Number(by.QUEUED||0)+Number(by.RUNNING||0),'条'],
  ['已完成',Number(by.SUCCEEDED||0)+Number(by.PARTIAL||0),'条'],
  ['需要处理',Number(by.RETRYABLE_FAILED||0)+Number(by.FAILED||0)+Number(by.PERMANENT_FAILED||0),'条'],
  ['缺卡号/IMSI',Number(summary.value.missingIdentity||0),'张']] as [string,any,string][]
})
async function load(){
 busy.value=true
 try{
  const query=status.value?('?status='+encodeURIComponent(status.value)):''
  ;[rows.value,summary.value]=await Promise.all([api<Row[]>('/erp/supplier/queue'+query),api<Row>('/erp/supplier/summary')])
 }catch(e){ElMessage.error((e as Error).message)}finally{busy.value=false}
}
async function run(task:()=>Promise<void>){
 busy.value=true
 try{await task();await load()}catch(e){ElMessage.error((e as Error).message)}finally{busy.value=false}
}
function retry(row:Row){run(async()=>{await api('/erp/cards/'+row.iccid+'/retry','POST');ElMessage.success('已重新排队')})}
function retryAll(){run(async()=>{const r=await api<Row>('/erp/supplier/retry-failed','POST');ElMessage.success('已重新排队 '+r.updated+' 条')})}
function backfill(){run(async()=>{const r=await api<Row>('/erp/supplier/backfill','POST');ElMessage.success('已排队 '+r.queued+' 张卡')})}
function when(value:any){return value?String(value).replace('T',' ').slice(0,19):'—'}
onMounted(load)
</script>
<template>
  <div class="page-head"><div><h1>供应商回填</h1><p class="subtitle">采购入库后按集团账号自动拉取卡号、IMSI、IMEI、激活日和资费信息；外部查询失败可在此重试。</p></div>
    <div class="form-actions" style="position:static;border:0;padding:0;background:transparent">
      <el-button @click="load" :loading="busy">刷新</el-button>
      <el-button @click="backfill">补拉缺卡号/IMSI</el-button>
      <el-button type="primary" @click="retryAll">重试全部失败</el-button>
    </div>
  </div>
  <div class="stats"><div class="metric" v-for="[label,value,unit] in stats" :key="label"><small>{{label}}</small><strong>{{value}}<i>{{unit}}</i></strong></div></div>
  <div class="box">
    <div class="bar"><h3>回填任务</h3><el-select v-model="status" clearable placeholder="全部状态" style="width:160px" @change="load"><el-option v-for="o in options" :key="o.value" :value="o.value" :label="o.label"/></el-select></div>
    <el-table :data="rows" v-loading="busy" empty-text="暂无回填任务">
      <el-table-column prop="iccid" label="ICCID" min-width="205"/>
      <el-table-column label="采购单" width="100"><template #default="{row}">#{{row.purchase_order_id}}</template></el-table-column>
      <el-table-column prop="sku_name" label="商品" min-width="130"/>
      <el-table-column prop="carrier_name" label="供应商账号" min-width="130"/>
      <el-table-column prop="adapter_key" label="适配器" width="120"/>
      <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="tones[row.last_status]||'info'">{{labels[row.last_status]||row.last_status}}</el-tag></template></el-table-column>
      <el-table-column prop="attempts" label="尝试" width="65"/>
      <el-table-column prop="msisdn" label="卡号" min-width="130"/>
      <el-table-column prop="imsi" label="IMSI" min-width="150"/>
      <el-table-column prop="imei" label="IMEI" min-width="150"/>
      <el-table-column label="最近回填" width="165"><template #default="{row}">{{when(row.last_reconciled_at)}}</template></el-table-column>
      <el-table-column label="最后错误" min-width="220"><template #default="{row}"><span :class="{negative:row.last_error}">{{row.last_error||'—'}}</span></template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{row}"><el-button link type="primary" @click="retry(row)">重试</el-button></template></el-table-column>
    </el-table>
  </div>
</template>
