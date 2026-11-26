<script setup lang="ts">
import {api,go} from './console/api'
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

type Row = Record<string, any>
const props=defineProps<{initialPage:string; initialCard?:string}>()
const page = ref('orders')
const rows = ref<Row[]>([])
const setup = ref<Row>({})
const pending = ref(false)
const dialog = ref('')
const data = ref<Row>({})
const config = ref<Row>({})

const labels: Record<string,string> = {
  plan_name:'套餐名称',
  orders:'销售采购订单', inventory:'库存', cards:'流量卡查询与对账', inquiries:'客户订购申请',
  carriers:'运营商接口配置', approval:'审批配置', fields:'客户可见字段', templates:'打印模板', master:'基础档案与账号',
  id:'编号', order_code:'订单编码', direction:'类型', party_name:'往来单位', state:'状态', total:'金额', quantity:'数量',
  customer_name:'客户', name:'名称', code:'编码', on_hand:'在库', reserved:'锁定',
  iccid:'ICCID', kind:'卡类别', carrier_name:'运营商账号', msisdn:'卡号', imsi:'IMSI', imei:'当前设备 IMEI', activated_on:'首次激活日',
  last_reconciled_at:'最近回填', supplier_type:'供应商类型', api_category:'接口大类', api_sub_category:'接口子类',
  adapter_key:'适配器标识', api_url:'接口地址',
  erp_expiry:'ERP 到期日', carrier_expiry:'运营商资费失效日', order_id:'正式订单',
  quota_kb:'套餐额度 (KB)', used_kb:'已用量 (KB)', remaining_kb:'剩余量 (KB)',
  starts_on:'周期起始', ends_on:'周期结束（不含）', created_at:'创建时间', updated_at:'更新时间',
  credential_ref:'凭据标识', enabled:'已启用',
}
const values: Record<string,string> = {
  SALE:'销售订单', PURCHASE:'采购订单', DRAFT:'草稿', CONVERTED:'已确认转单', PENDING:'待审核', APPROVED:'已审核',
  FULFILLED:'已出入库', GROUP:'集团卡', TERMINAL:'终端卡',
}
const columns = computed(() => {
  const preferences: Record<string,string[]> = {
    orders:['id','direction','party_name','state','total','created_at'],
    inventory:['code','name','on_hand','reserved'],
    cards:['iccid','kind','customer_name','carrier_name','msisdn','imsi','imei','activated_on','erp_expiry','carrier_expiry','last_reconciled_at'],
    inquiries:['id','customer_name','name','quantity','order_id'],
    carriers:['name','credential_ref','supplier_type','api_sub_category','adapter_key','api_url','enabled'],
  }
  return preferences[page.value] || []
})
const searchText=ref(''),pageNumber=ref(1)
const matches=computed(()=>{
  const query=searchText.value.trim().toLowerCase()
  if(!query)return rows.value
  return rows.value.filter(r=>columns.value.some(k=>String(r[k]??'').toLowerCase().includes(query)))
})
const pageRows=computed(()=>matches.value.slice((pageNumber.value-1)*20,pageNumber.value*20))
watch([page,searchText],()=>{pageNumber.value=1})
const fieldOptions: Record<string,string[]> = {
  inquiries:['id','name','quantity','state','order_id','order_code','created_at'],
  orders:['id','order_code','state','total','created_at'],
  cards:['iccid','msisdn','imei','plan_name','activated_on','erp_expiry','starts_on','ends_on','quota_kb','used_kb','remaining_kb','updated_at'],
}

async function run(task: () => Promise<void>) {
  pending.value = true
  try { await task() }
  catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error((error as Error).message) }
  finally { pending.value = false }
}
async function start() {
  setup.value = await api('/erp/setup')
  await change(props.initialPage || 'orders')
  if(props.initialCard && page.value==='cards'){const r=rows.value.find(r=>r.iccid===props.initialCard);if(r)await openCard(r)}
}
async function change(tab: string) {
  page.value = tab
  rows.value = []
  if (tab === 'master') {
    setup.value = await api('/erp/setup')
    config.value = {accounts:await api('/admin/master/accounts'),categories:(await api<Row[]>('/erp/register/category')).filter(r=>r.enabled && r.category_level===3)}
  } else if (tab === 'approval') {
    config.value = { SALE:[], PURCHASE:[] }
    const steps: Row[] = await api('/admin/approval')
    steps.forEach(row => (config.value[row.direction][row.step_no-1] ||= []).push(row.account_id))
  } else if (tab === 'fields') config.value = await api('/admin/portal-fields')
  else if (tab === 'templates') config.value = Object.fromEntries((await api<Row[]>('/admin/templates')).map(row => [row.direction,row]))
  else rows.value = await api((tab === 'carriers' ? '/admin/' : '/erp/')+tab)
}
function newOrder(direction: string) {
  data.value = { direction, party:null, lines:[{sku:null,quantity:1,price:0}] }
  dialog.value = 'order'
}
async function edit(row: Row) {
  const full = await api('/erp/orders/'+row.id)
  data.value = {id:row.id,direction:row.direction,party:row.party_id,
    lines:full.lines.map((line:Row) => ({sku:line.sku_id,quantity:line.quantity,price:Number(line.unit_price)}))}
  dialog.value = 'order'
}
async function orderAction(row: Row, action: string) {
  if (action === 'edit') return edit(row)
  if (action === 'fulfill') {
    const order = await api('/erp/orders/'+row.id)
    data.value = {...row,lines:order.lines.filter((line:Row) => line.kind !== 'HARDWARE').map((line:Row) => ({...line,text:'',carrier:null,expiry:'',activatedOn:'',quota:null}))}
    dialog.value = 'fulfill'
    return
  }
  if (action === 'submit') await api('/erp/orders/'+row.id+'/submit','POST')
  else {
    const reason = action === 'reject' ? (await ElMessageBox.prompt('请输入驳回原因','驳回', { inputPattern:/\S/,inputErrorMessage:'请填写原因' })).value : ''
    await api('/erp/orders/'+row.id+'/decision','POST',{approved:action==='approve',reason,round:row.round_no,step:row.current_step})
  }
  ElMessage.success('已保存')
  await change(page.value)
}
async function openCard(row:Row) {
  const root='/erp/cards/'+row.iccid
  const [cycles,snapshots,services]=await Promise.all([api(root+'/cycles'),api(root+'/snapshots'),api(root+'/services')])
  data.value = {...row,cycles,snapshots,services,start:'',end:'',quota:row.default_quota_kb||0,kb:null,reason:'',picked:cycles[0]?.starts_on||'',cycle_months:row.cycle_months||1,rawKb:null,carryKb:null}
  dialog.value = 'card'
}
async function cardAction(action:string) {
  const row = data.value
  const path = '/erp/cards/'+row.iccid
  if (action === 'baseline') await api(path+'/baseline','PUT',{startsOn:row.picked,rawKb:row.rawKb,carryKb:row.carryKb})
  else if (action === 'cycle') await api(path+'/cycles','POST',{startsOn:row.start,endsOn:row.end,quotaKb:row.quota})
  else if (action === 'override' || action === 'clear') await api(path+'/override','PUT',{startsOn:row.picked,kb:action==='clear'?null:row.kb,reason:row.reason})
  else if (action === 'expiry') await api(path+'/expiry','PUT',{expiresOn:row.erp_expiry})
  else if (action === 'settings') await api(path+'/settings','PUT',{carrier:row.carrier_account_id,quotaKb:row.default_quota_kb,planName:row.plan_name,cycleMonths:row.cycle_months})
  else if (action === 'exchange' || action === 'return') {
    dialog.value=''
    go(action==='return'?'sales_return_form':'sales_exchange_form',undefined,'?source='+row.sale_order_id+'&iccid='+encodeURIComponent(row.iccid))
    return
  } else if (action === 'sync') await api(path+'/reconcile','POST')
  ElMessage.success('已保存')
  await change('cards')
  const refreshed = rows.value.find(r => r.iccid===row.iccid)
  if (dialog.value && refreshed) await openCard(refreshed)
}
async function save() {
  const row = data.value
  switch (dialog.value) {
    case 'order': await api('/erp/orders'+(row.id?'/'+row.id:''),row.id?'PUT':'POST',row); break
    case 'master': await api('/admin/master/'+row.resource,'POST',row); break
    case 'carrier':
      await api('/admin/carriers'+(row.id?'/'+row.id:''),row.id?'PUT':'POST',row)
      setup.value = await api('/erp/setup')
      break
    case 'fulfill':
      await api('/erp/orders/'+row.id+'/fulfill','POST',{lines:row.lines.map((line:Row)=>({
        sku:line.sku_id,expiry:line.expiry||null,cards:line.text.trim().split(/\r?\n/).filter((text:string)=>text.trim()).map((text:string)=>{const [iccid,carrier]=text.trim().split(/\s+/);return {iccid:iccid.toUpperCase(),carrier:carrier?Number(carrier):line.carrier||0,activatedOn:line.activatedOn||null,quotaKb:line.quota}})
      }))})
      break
  }
  dialog.value = ''
  ElMessage.success('已保存')
  await change(page.value)
}
function show(value:any,key:string) {
  if (value===null || value===undefined || value==='') return '—'
  if (key==='enabled') return value ? '启用' : '停用'
  return values[String(value)] || value
}
onMounted(() => run(start))
</script>

<template>
  <div class="embedded-panel">
    <main v-loading="pending">
      <section>
        <div class="heading"><h1>{{ labels[page] }}</h1>
          <div>
            <template v-if="page==='orders'">
              <el-button @click="newOrder('SALE')">新建销售订单</el-button><el-button type="primary" @click="newOrder('PURCHASE')">新建采购订单</el-button>
            </template>
            <el-button v-if="page==='carriers'" type="primary" @click="data={name:'',credential_ref:'',enabled:false,supplier_type:'CMCC',api_category:'CMCC',api_sub_category:'CMCC_CTBOSS',adapter_key:'cmcc-ctboss',api_url:''};dialog='carrier'">添加上游账号</el-button>
          </div>
        </div>
        <template v-if="page==='master'">
          <el-card class="panel" shadow="never">
            <h3>客户与供应商</h3>
            <el-button @click="data={resource:'parties',kind:'CUSTOMER'};dialog='master'">新增往来单位</el-button>
            <el-table :data="setup.parties"><el-table-column prop="name" label="名称"/><el-table-column prop="kind" label="类型"/></el-table>
          </el-card>
          <el-card class="panel" shadow="never">
            <h3>商品</h3>
            <el-button @click="data={resource:'skus',kind:'HARDWARE',price:0};dialog='master'">新增商品</el-button>
            <el-table :data="setup.skus"><el-table-column prop="code" label="编码"/><el-table-column prop="name" label="名称"/><el-table-column prop="kind" label="类别"/></el-table>
          </el-card>
          <el-card class="panel" shadow="never">
            <h3>账号</h3>
            <el-button @click="data={resource:'accounts',role:'ERP',party:null};dialog='master'">新增账号</el-button>
            <el-table :data="config.accounts"><el-table-column prop="username" label="账号"/><el-table-column prop="role" label="角色"/><el-table-column prop="party_id" label="客户编号"/></el-table>
          </el-card>
        </template>
        <template v-else-if="page==='approval'">
          <el-card v-for="direction in ['SALE','PURCHASE']" :key="direction" class="panel" shadow="never">
            <h3>{{ values[direction] }}</h3>
            <div v-for="(step,idx) in config[direction]" :key="idx" class="form-row">
              <span>节点 {{ Number(idx)+1 }}</span>
              <el-select v-model="config[direction][idx]" multiple placeholder="选择审批人">
                <el-option v-for="account in setup.accounts" :key="account.id" :label="account.username" :value="account.id" />
              </el-select>
              <el-button @click="config[direction].splice(idx,1)">移除</el-button>
            </div>
            <el-button @click="config[direction].push([])">添加节点</el-button>
            <el-button type="primary" @click="run(async()=>{await api('/admin/approval/'+direction,'PUT',config[direction]);ElMessage.success('已保存')})">保存</el-button>
          </el-card>
        </template>
        <template v-else-if="page==='fields'">
          <el-card v-for="(options,key) in fieldOptions" :key="key" class="panel" shadow="never">
            <h3>{{ labels[key] }}</h3>
            <el-checkbox-group v-if="config[key]" v-model="config[key]">
              <el-checkbox v-for="field in options" :key="field" :value="field">{{ labels[field] }}</el-checkbox>
            </el-checkbox-group>
            <el-button type="primary" @click="run(async()=>{await api('/admin/portal-fields/'+key,'PUT',config[key]);ElMessage.success('已保存')})">保存</el-button>
          </el-card>
        </template>
        <template v-else-if="page==='templates'">
          <el-card v-for="direction in ['SALE','PURCHASE']" :key="direction" class="panel" shadow="never">
            <h3>{{ values[direction] }}</h3>
            <el-form v-if="config[direction]" label-position="top">
              <el-form-item label="标题"><el-input v-model="config[direction].title"/></el-form-item>
              <el-form-item label="公司名称"><el-input v-model="config[direction].company"/></el-form-item>
              <el-form-item label="显示商品编码"><el-switch v-model="config[direction].show_code"/></el-form-item>
              <el-form-item label="显示交货日期和仓库"><el-switch v-model="config[direction].show_delivery"/></el-form-item>
              <el-form-item label="页尾说明"><el-input v-model="config[direction].footer" maxlength="250" type="textarea"/></el-form-item>
              <el-form-item label="显示单价和金额"><el-switch v-model="config[direction].show_price"/></el-form-item>
            </el-form>
            <el-button v-if="config[direction]" type="primary" @click="run(async()=>{const t=config[direction];await api('/admin/templates/'+direction,'PUT',{title:t.title,company:t.company,showPrice:t.show_price,showCode:t.show_code,showDelivery:t.show_delivery,footer:t.footer});ElMessage.success('已保存')})">保存</el-button>
          </el-card>
        </template>
        <template v-else><div class="filter-line"><el-input v-model="searchText" clearable aria-label="筛选当前列表" :placeholder="page==='cards'?'搜索 ICCID、卡号或套餐':'搜索当前列表'"/><small>共 {{matches.length}} 条</small></div>
        <el-table :data="pageRows" empty-text="暂无匹配记录" stripe>
          <el-table-column v-for="key in columns" :key="key" :label="labels[key] || key" :min-width="key==='iccid' ? 215 : 110">
            <template #default="{row}">{{ show(row[key],key) }}</template>
          </el-table-column>
          <el-table-column v-if="['orders','cards','inquiries','carriers'].includes(page)" label="操作" min-width="230" fixed="right">
            <template #default="{row}">
              <template v-if="page==='orders'">
                <el-button v-if="row.state==='DRAFT'" link type="primary" @click="run(()=>orderAction(row,'edit'))">编辑</el-button>
                <el-button v-if="row.state==='DRAFT'" link type="primary" @click="run(()=>orderAction(row,'submit'))">提交</el-button>
                <el-button v-if="row.state==='PENDING'" link type="primary" @click="run(()=>orderAction(row,'approve'))">通过</el-button>
                <el-button v-if="row.state==='PENDING'" link type="danger" @click="run(()=>orderAction(row,'reject'))">驳回</el-button>
                <el-button v-if="row.state==='APPROVED'" link type="primary" @click="run(()=>orderAction(row,'fulfill'))">{{row.direction==='SALE'?'确认出库':'审核入库'}}</el-button>
                <a :href="'/api/erp/orders/'+row.id+'/pdf'">下载 PDF</a>
              </template>
              <el-button v-if="page==='cards'" link type="primary" @click="run(()=>openCard(row))">对账与维护</el-button>
              <template v-if="page==='carriers'">
                <el-button link type="primary" @click="data={...row};dialog='carrier'">编辑</el-button>
                <el-button link @click="run(async()=>{await api('/admin/carriers/'+row.id+'/test','POST');ElMessage.success('移动认证成功')})">测试认证</el-button>
              </template>
              <el-button v-if="page==='inquiries'" link type="primary" @click="run(async()=>{await api('/erp/inquiries/'+row.id+'/convert','POST');await change(page);ElMessage.success('已生成销售订单草稿')})">确认并转单</el-button>
            </template>
          </el-table-column>
        </el-table><el-pagination v-if="matches.length>20" v-model:current-page="pageNumber" :page-size="20" :total="matches.length" layout="prev, pager, next"/></template>
      </section>
    </main>
  </div>

  <el-dialog :model-value="!!dialog" @close="dialog=''" width="min(760px, 94vw)"
    :title="({master:'新增基础档案',order:'订单草稿',fulfill:'确认出入库',inquiry:'订购申请',card:'流量卡对账与维护',carrier:'中国移动集团账号'} as Record<string,string>)[dialog]">
    <el-form v-if="dialog==='order'" label-position="top">
      <el-form-item label="往来单位">
        <el-select v-model="data.party"><el-option v-for="p in setup.parties.filter((p:Row)=>p.kind===(data.direction==='SALE'?'CUSTOMER':'SUPPLIER'))" :key="p.id" :label="p.name" :value="p.id"/></el-select>
      </el-form-item>
      <div class="form-row" v-for="(line,i) in data.lines" :key="i">
        <el-select v-model="line.sku" placeholder="商品" @change="line.price=Number(setup.skus.find((s:Row)=>s.id===line.sku)?.price || 0)">
          <el-option v-for="s in setup.skus" :key="s.id" :label="s.name" :value="s.id"/>
        </el-select>
        <el-input-number v-model="line.quantity" :min="1" :max="10000" aria-label="数量" />
        <el-input-number v-model="line.price" :min="0" :precision="2" aria-label="单价" />
        <el-button @click="data.lines.splice(i,1)">移除</el-button>
      </div>
      <el-button @click="data.lines.push({sku:null,quantity:1,price:0})">添加商品</el-button>
    </el-form>
    <el-form v-else-if="dialog==='master'" label-position="top">
      <template v-if="data.resource==='accounts'">
        <el-form-item label="用户名"><el-input v-model="data.username"/></el-form-item>
        <el-form-item label="初始密码（至少 12 位）"><el-input v-model="data.password" type="password" show-password/></el-form-item>
        <el-form-item label="角色"><el-radio-group v-model="data.role" @change="data.party=null"><el-radio value="ERP">ERP 人员</el-radio><el-radio value="CUSTOMER">客户</el-radio></el-radio-group></el-form-item>
        <el-form-item v-if="data.role==='CUSTOMER'" label="关联客户"><el-select v-model="data.party">
          <el-option v-for="p in setup.parties.filter((p:Row)=>p.kind==='CUSTOMER')" :key="p.id" :label="p.name" :value="p.id"/>
        </el-select></el-form-item>
      </template>
      <template v-else>
        <el-form-item label="名称"><el-input v-model="data.name"/></el-form-item>
        <el-form-item v-if="data.resource==='skus'" label="商品分类" required><el-select v-model="data.categoryId" filterable placeholder="选择启用的三级分类"><el-option v-for="r in config.categories" :key="r.id" :value="r.id" :label="r.category_path"/></el-select></el-form-item>
        <el-form-item v-if="data.resource==='parties'" label="类型"><el-radio-group v-model="data.kind"><el-radio value="CUSTOMER">客户</el-radio><el-radio value="SUPPLIER">供应商</el-radio></el-radio-group></el-form-item>
        <template v-else>
          <el-form-item label="商品编码"><el-input disabled placeholder="保存后自动生成"/></el-form-item>
          <el-form-item label="类别"><el-radio-group v-model="data.kind"><el-radio value="HARDWARE">普通设备</el-radio><el-radio value="GROUP">集团卡</el-radio><el-radio value="TERMINAL">终端卡</el-radio></el-radio-group></el-form-item>
          <el-form-item label="参考售价"><el-input-number v-model="data.price" :min="0" :precision="2"/></el-form-item>
        </template>
      </template>
    </el-form>

    <el-form v-else-if="dialog==='carrier'" label-position="top">
      <el-form-item label="集团账号名称"><el-input v-model="data.name" maxlength="120"/></el-form-item>
      <el-form-item label="环境凭据标识"><el-input v-model="data.credential_ref" placeholder="例如 GD_01"/></el-form-item>
      <el-form-item label="供应商类型"><el-input v-model="data.supplier_type" placeholder="CMCC"/></el-form-item>
      <el-form-item label="接口大类"><el-input v-model="data.api_category" placeholder="CMCC"/></el-form-item>
      <el-form-item label="接口子类"><el-input v-model="data.api_sub_category" placeholder="CMCC_CTBOSS"/></el-form-item>
      <el-form-item label="适配器标识"><el-input v-model="data.adapter_key" placeholder="cmcc-ctboss"/></el-form-item>
      <el-form-item label="接口地址（留空使用官方地址）"><el-input v-model="data.api_url" placeholder="https://api.iot.10086.cn"/></el-form-item>
      <el-form-item label="启用供应商回填"><el-switch v-model="data.enabled"/></el-form-item>
      <p class="subtitle">密码不写入数据库。启动服务时设置 CMCC_&lt;凭据标识&gt;_APPID 与 CMCC_&lt;凭据标识&gt;_PASSWORD。</p>
    </el-form>
    <el-form v-else-if="dialog==='fulfill'" label-position="top">
      <p v-if="data.lines.length===0">确认处理本单全部设备数量。</p>
      <div v-for="line in data.lines" :key="line.sku_id">
        <el-form-item :label="line.name+' · '+line.quantity+' 张 ICCID（每行一个）'"><el-input type="textarea" v-model="line.text"/></el-form-item>
        <el-form-item v-if="data.direction==='PURCHASE'" label="移动集团账号"><el-select v-model="line.carrier"><el-option v-for="carrier in setup.carriers" :key="carrier.id" :label="carrier.name+' #'+carrier.id" :value="carrier.id"/></el-select>
          <small>混合账号时，每行填写 ICCID 和账号编号，中间空格隔开。</small></el-form-item>
        <el-form-item v-if="data.direction==='PURCHASE'" label="首次激活日（已知时填写）"><el-date-picker v-model="line.activatedOn" value-format="YYYY-MM-DD"/></el-form-item>
        <el-form-item v-if="data.direction==='PURCHASE' && line.kind==='TERMINAL'" label="单卡默认套餐额度 KB"><el-input-number v-model="line.quota" :min="0"/></el-form-item>
        <el-form-item v-if="data.direction==='SALE'" label="ERP 到期日"><el-date-picker v-model="line.expiry" value-format="YYYY-MM-DD"/></el-form-item>
      </div>
    </el-form>
    <div v-else-if="dialog==='card'">
      <p>{{ data.iccid }} · {{ data.carrier_name }}</p>
      <div class="form-row">
        <el-select v-model="data.carrier_account_id" placeholder="移动集团账号">
          <el-option v-for="carrier in setup.carriers" :key="carrier.id" :label="carrier.name" :value="carrier.id"/>
        </el-select>
        <el-input-number v-if="data.kind==='TERMINAL'" v-model="data.default_quota_kb" :min="0" aria-label="单卡默认套餐额度 KB"/>
        <template v-if="data.kind==='TERMINAL'"><el-input v-model="data.plan_name" placeholder="客户套餐名称"/><el-input-number v-model="data.cycle_months" :min="1" :max="24" aria-label="周期月数"/>个月</template>
        <el-button @click="run(()=>cardAction('settings'))">保存卡配置</el-button>
        <el-button @click="run(()=>cardAction('sync'))">查询中国移动</el-button>
      </div>
      <el-collapse v-if="data.services.length"><el-collapse-item title="移动套餐与已开通业务"><p>{{data.services[0].plan_name||'暂无套餐'}}</p><p>APN</p><pre>{{data.services[0].apn_details||'暂无数据'}}</pre><p>通信功能</p><pre>{{data.services[0].communication_details||'暂无数据'}}</pre></el-collapse-item></el-collapse>
      <el-table :data="data.snapshots" empty-text="暂无运营商查询记录">
        <el-table-column prop="period_month" label="运营商自然月"/>
        <el-table-column prop="usage_kb" label="原始用量 KB"/>
        <el-table-column prop="fetched_at" label="查询时间"/>
        <el-table-column prop="unavailable" label="未返回项目"/>
      </el-table>
      <template v-if="data.customer_id">
        <el-button type="danger" plain @click="run(()=>cardAction('return'))">新建退货入库</el-button>
        <el-button @click="run(()=>cardAction('exchange'))">新建换货出库</el-button>
        <h3>ERP 销售有效期</h3>
        <div class="form-row"><el-date-picker v-model="data.erp_expiry" value-format="YYYY-MM-DD"/><el-button @click="run(()=>cardAction('expiry'))">保存续期</el-button></div>
        <template v-if="data.kind==='TERMINAL'"><h3>套餐周期</h3>
        <div class="form-row">
          <el-date-picker v-model="data.start" value-format="YYYY-MM-DD" placeholder="起始日"/>
          <el-date-picker v-model="data.end" value-format="YYYY-MM-DD" placeholder="结束日（不含）"/>
          <el-input-number v-model="data.quota" :min="0" aria-label="套餐额度 KB"/>
          <el-button @click="run(()=>cardAction('cycle'))">新增</el-button>
        </div>
        <el-table :data="data.cycles">
          <el-table-column prop="starts_on" label="周期起始"/>
          <el-table-column prop="ends_on" label="周期结束"/>
          <el-table-column prop="quota_kb" label="额度 KB"/>
          <el-table-column prop="carrier_kb" label="折算周期用量 KB"/>
          <el-table-column prop="carrier_updated_at" label="运营商更新时间"/>
          <el-table-column prop="override_kb" label="人工 KB"/>
          <el-table-column prop="override_updated_at" label="修正时间"/>
        </el-table>
        <div class="form-row">
          <el-select v-model="data.picked" placeholder="选择周期"><el-option v-for="cycle in data.cycles" :key="cycle.starts_on" :label="cycle.starts_on" :value="cycle.starts_on"/></el-select>
          <el-input-number v-model="data.kb" :min="0" aria-label="修正用量 KB"/>
          <el-input v-model="data.reason" placeholder="修正原因" maxlength="300"/>
          <el-button @click="run(()=>cardAction('override'))">保存修正</el-button>
          <el-button @click="run(()=>cardAction('clear'))">撤销修正</el-button>
        </div><el-collapse><el-collapse-item title="跨月或首期用量基线">
          <p>选择当前套餐周期，填写同一时刻的移动本月读数和客户周期已用量。后续查询会把读数增量加到该周期。</p>
          <div class="form-row"><label>移动本月读数 KB <el-input-number v-model="data.rawKb" :min="0"/></label><label>客户周期已用 KB <el-input-number v-model="data.carryKb" :min="0"/></label>
          <el-button @click="run(()=>cardAction('baseline'))">保存基线</el-button></div>
        </el-collapse-item></el-collapse></template>
      </template>
    </div>
    <template #footer><el-button @click="dialog=''">关闭</el-button><el-button v-if="dialog!=='card'" type="primary" :loading="pending" @click="run(save)">确认</el-button></template>
  </el-dialog>
</template>

<style scoped>
.filter-line{display:flex;align-items:center;gap:12px;margin:0 0 14px}.filter-line .el-input{max-width:320px}.filter-line small{color:#64748b}.el-pagination{justify-content:flex-end;margin-top:14px}

*{box-sizing:border-box} body{margin:0;background:#f2f5f8;color:#1b2b40;font:14px system-ui,sans-serif}
.layout{display:grid;grid-template-columns:225px minmax(0,1fr);min-height:100vh}
aside{background:#0c2437;color:#c6d7e4;padding:16px 12px}.brand{font-size:19px;font-weight:700;color:white;padding:10px 12px 24px}
aside button{display:block;width:100%;margin-bottom:4px;padding:11px 12px;text-align:left;border:0;border-radius:5px;background:none;color:inherit;cursor:pointer;font:inherit}
aside button:hover,aside button.active{background:#174461;color:white}
main header{height:58px;background:white;display:flex;align-items:center;justify-content:flex-end;padding:0 26px;border-bottom:1px solid #dfe7ed}
main section{padding:24px}.heading{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:16px}
h1{margin:0;font-size:24px}h3{margin:0 0 14px}.panel{margin-bottom:14px}.form-row{display:flex;align-items:center;gap:8px;margin:12px 0;flex-wrap:wrap}
.form-row .el-select{width:170px}.form-row .el-input{max-width:200px}a{color:#21669b;text-decoration:none}
.login{min-height:100vh;display:grid;place-items:center;background:linear-gradient(130deg,#102c43,#27617c)}
.login-card{width:min(390px,90vw);padding:12px}.login-card h1{margin-bottom:24px}
@media(max-width:720px){.layout{grid-template-columns:1fr}aside button{width:auto;display:inline-block}.brand{padding-bottom:8px}main section{padding:14px}}
</style>
