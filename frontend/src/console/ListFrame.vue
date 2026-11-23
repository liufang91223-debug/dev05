<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {csv,type Row} from './api'
export interface Column{key:string;label:string;wide?:number;link?:boolean;numeric?:boolean}
const props=defineProps<{page:string;title:string;subtitle?:string;rows:Row[];columns:Column[];stats?:[string,any,string?][];keyword?:string;statuses?:string[];dateLabel?:string;exportLabel?:string;newLabel?:string;userKey:string;busy?:boolean}>()
const emit=defineEmits<{open:[row:Row];create:[];export:[rows:Row[]]}>()
const pref='ys:'+props.userKey+':'+props.page
function remembered<T>(key:string,fallback:T):T{try{return JSON.parse(localStorage.getItem(pref+key)||'null')??fallback}catch{return fallback}}
const search=ref(remembered(':query','')),filter=ref(remembered(':status','')),dates=ref<string[]>(remembered(':dates',[]))
const applied=ref({q:search.value,s:filter.value,d:dates.value||[]}),pageNo=ref(1),size=ref(10),setting=ref(false)
const keys=ref<string[]>(remembered(':cols',props.columns.map(c=>c.key))),editing=ref<string[]>([]), sequence=ref<string[]>([])
const visible=computed(()=>keys.value.map(k=>props.columns.find(c=>c.key===k)).filter((c):c is Column=>!!c))
const filtered=computed(()=>props.rows.filter(row=>{
 const {q,s,d}=applied.value,text=props.columns.map(c=>row[c.key]??'').join(' ').toLowerCase()
 const date=String(row.filter_date||'').slice(0,10)
 return (!q||text.includes(q.toLowerCase())) && (!s||row.display_state===s) && (!d?.length||(date>=d[0]&&date<=d[1]))
}))
const displayed=computed(()=>filtered.value.slice((pageNo.value-1)*size.value,pageNo.value*size.value))
watch(()=>filtered.value.length,n=>{pageNo.value=Math.min(pageNo.value,Math.max(1,Math.ceil(n/size.value)))})
function query(){applied.value={q:search.value.trim(),s:filter.value,d:dates.value||[]};pageNo.value=1
 for(const [k,v] of Object.entries({':query':search.value,':status':filter.value,':dates':dates.value}))localStorage.setItem(pref+k,JSON.stringify(v))}
function settings(){editing.value=[...keys.value];sequence.value=[...new Set([...keys.value,...props.columns.map(c=>c.key)])];setting.value=true}
function move(i:number,offset:number){const next=[...sequence.value];[next[i],next[i+offset]]=[next[i+offset],next[i]];sequence.value=next}
function save(){if(!editing.value.length)return ElMessage.warning('请至少保留一列');keys.value=sequence.value.filter(k=>editing.value.includes(k));localStorage.setItem(pref+':cols',JSON.stringify(keys.value));setting.value=false}
function download(){csv(props.title,visible.value.map(c=>c.label),filtered.value.map(row=>visible.value.map(c=>row[c.key])))}
</script>
<template><div class="page-head"><div><h1>{{title}}</h1><p v-if="subtitle" class="subtitle">{{subtitle}}</p></div><div>
  <slot name="actions"/><el-button v-if="exportLabel" @click="download">{{exportLabel}}</el-button><el-button v-if="newLabel" type="primary" @click="emit('create')">{{newLabel}}</el-button></div></div>
  <div v-if="stats?.length" class="stats"><div v-for="[name,val,unit] in stats" :key="name" class="metric"><small>{{name}}</small><strong>{{val}}<i>{{unit}}</i></strong></div></div>
  <form class="box filters" @submit.prevent="query"><label>{{keyword||'关键词'}}<el-input v-model="search" clearable placeholder="请输入关键词"/></label>
    <label v-if="statuses?.length">状态<el-select v-model="filter" clearable placeholder="全部状态"><el-option v-for="s in statuses" :key="s" :value="s" :label="s"/></el-select></label>
    <label v-if="dateLabel">{{dateLabel}}<el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" range-separator="至" style="max-width:270px"/></label>
    <el-button type="primary" native-type="submit">查询</el-button><el-button @click="search='';filter='';dates=[];query()">重置</el-button>
  </form>
  <div class="box" v-loading="busy"><div class="bar"><strong>共 {{filtered.length}} 条记录</strong><el-button size="small" @click="settings">列设置</el-button></div>
    <el-table :data="displayed" stripe empty-text="暂无匹配记录" @row-dblclick="(r:Row)=>emit('open',r)">
      <el-table-column label="序号" width="64"><template #default="s">{{(pageNo-1)*size+s.$index+1}}</template></el-table-column>
      <el-table-column v-for="c in visible" :key="c.key" :label="c.label" :prop="c.key" :min-width="c.wide||118" :align="c.numeric?'right':'left'" show-overflow-tooltip>
        <template #default="{row}"><button v-if="c.link" class="link" @click="emit('open',row)">{{row[c.key]??'—'}}</button>
          <el-tag v-else-if="c.key==='display_state'" :type="['待审批','待审核','草稿','在途'].includes(row[c.key])?'warning':'success'" effect="light">{{row[c.key]}}</el-tag>
          <span v-else :class="{negative:String(row[c.key]).startsWith('-')}">{{row[c.key]??'—'}}</span></template>
      </el-table-column>
    </el-table>
    <div class="page-end"><small class="muted">显示第 {{filtered.length?1+(pageNo-1)*size:0}}–{{Math.min(pageNo*size,filtered.length)}} 条，共 {{filtered.length}} 条</small>
      <el-pagination v-model:current-page="pageNo" v-model:page-size="size" :page-sizes="[10,20,50,100]" :total="filtered.length" layout="sizes, prev, pager, next" small/></div></div>
  <el-dialog v-model="setting" title="列设置" width="430px"><div v-for="(k,i) in sequence" :key="k" class="bar"><el-checkbox v-model="editing" :value="k">{{columns.find(c=>c.key===k)?.label}}</el-checkbox><div>
    <el-button text :disabled="!i" @click="move(i,-1)">上移</el-button><el-button text :disabled="i===sequence.length-1" @click="move(i,1)">下移</el-button></div></div>
    <template #footer><el-button @click="editing=columns.map(c=>c.key);sequence=[...editing]">恢复默认</el-button><el-button type="primary" @click="save">保存</el-button></template></el-dialog>
</template>
