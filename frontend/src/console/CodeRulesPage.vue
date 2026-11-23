<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, day } from './api'

interface Rule {
  rule_key: string
  label: string
  prefix: string
  date_policy: 'OPTIONAL' | 'ALWAYS' | 'NEVER'
  include_date: boolean
  serial_length: number
  initial_value: number
  revision: number
}

const rules = ref<Rule[]>([])
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const selected = ref<Rule>()
const dateEnabled = ref(false)
const length = ref<number | undefined>(3)
const initial = ref('001')
const sampleDay = ref('')
const digits = computed(() => length.value ?? 0)
const initialOK = computed(() => /^[0-9]{1,5}$/.test(initial.value) && initial.value.length <= digits.value)
const valid = computed(() => Number.isInteger(digits.value) && digits.value >= 1 && digits.value <= 5 && initialOK.value)
const preview = computed(() => valid.value && selected.value
  ? selected.value.prefix + (dateEnabled.value ? sampleDay.value : '') + initial.value.padStart(digits.value, '0') : '—')
const serial = (r: Rule) => String(r.initial_value).padStart(r.serial_length, '0')
const errorMessage = (e: unknown) => e instanceof Error ? e.message : '操作失败，请重试'

async function load() {
  loading.value = true
  try { rules.value = await api<Rule[]>('/erp/code-rules') }
  catch (e) { ElMessage.error(errorMessage(e)) }
  finally { loading.value = false }
}
function edit(rule: Rule) {
  selected.value = rule
  dateEnabled.value = Boolean(rule.include_date)
  length.value = rule.serial_length
  initial.value = serial(rule)
  sampleDay.value = day().replaceAll('-', '').slice(2)
  dialog.value = true
}
function adjustWidth() {
  if (/^[0-9]{1,5}$/.test(initial.value) && digits.value > 0) {
    initial.value = String(Number(initial.value)).padStart(digits.value, '0')
  }
}
async function save() {
  const rule = selected.value
  if (!valid.value || saving.value || !rule) return
  saving.value = true
  try {
    await api('/erp/code-rules/' + rule.rule_key, 'PUT', {
      includeDate: dateEnabled.value, serialLength: digits.value, initialValue: initial.value, revision: rule.revision,
    })
    dialog.value = false
    ElMessage.success('编码规则已保存')
    await load()
  } catch (e) {
    ElMessage.error(errorMessage(e))
    await load()
  } finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page-head"><h1>编码规则</h1></div>
  <div v-loading="loading" class="rule-list">
    <table aria-label="编码规则列表">
      <thead><tr><th>序号</th><th>单据类型</th><th>编码规则</th><th>编码前缀1</th><th>编码前缀2</th><th>流水号</th></tr></thead>
      <tbody><tr v-for="(r, index) in rules" :key="r.rule_key">
        <td>{{ index + 1 }}</td><td><button class="type-link" @click="edit(r)">{{ r.label }}</button></td>
        <td>{{ r.include_date ? '常量日期流水号' : '常量流水号' }}</td>
        <td>{{ r.prefix }}</td><td>{{ r.include_date ? '日期' : '无' }}</td><td>{{ serial(r) }}</td>
      </tr></tbody>
    </table>
    <el-empty v-if="!loading && !rules.length" description="未获取到编码规则"><el-button @click="load">重新加载</el-button></el-empty>
  </div>

  <el-dialog v-model="dialog" title="编码规则设置" width="min(1000px, 94vw)" class="rule-dialog"
      :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" destroy-on-close>
    <template v-if="selected">
      <el-form label-position="right" label-width="82px" :disabled="saving" @submit.prevent="save">
        <div class="rule-meta">
          <el-form-item label="单据类型"><el-input :model-value="selected.label" readonly/></el-form-item>
          <el-form-item label="应用规则"><el-input model-value="新增时自动生成" readonly/></el-form-item>
        </div>
        <div class="rule-sections">
          <section>
            <el-form-item label="编码前缀1"><el-input model-value="常量" disabled/></el-form-item>
            <el-form-item label="设置值"><el-input :model-value="selected.prefix" disabled/></el-form-item>
          </section>
          <section>
            <el-form-item label="编码前缀2">
              <el-select v-model="dateEnabled" :disabled="selected.date_policy !== 'OPTIONAL'">
                <el-option label="日期" :value="true"/><el-option label="无" :value="false"/>
              </el-select>
            </el-form-item>
            <el-form-item label="日期格式"><el-input :model-value="dateEnabled ? 'YYMMDD' : '无'" disabled/></el-form-item>
            <small class="period-note">{{ dateEnabled ? '按日重新起号' : '连续编号' }}</small>
          </section>
          <section>
            <el-form-item label="流水号"><el-input model-value="流水号" disabled/></el-form-item>
            <el-form-item label="长度" required>
              <el-input-number v-model="length" :min="1" :max="5" :precision="0" controls-position="right" @change="adjustWidth"/>
            </el-form-item>
            <el-form-item label="初始值" required :error="initialOK ? '' : '请输入不超过设定长度的数字'">
              <el-input v-model="initial" maxlength="5" inputmode="numeric"/>
            </el-form-item>
          </section>
        </div>
      </el-form>
      <h3 class="preview-heading">编码预览</h3>
      <div class="preview-box"><div class="formula"><span>编码规则</span><b>常量</b>
        <template v-if="dateEnabled"><i>+</i><b>日期</b></template><i>+</i><b>流水号</b>
      </div><div class="preview-result"><span>预览效果</span><output aria-live="polite">{{ preview }}</output></div>
      <small>按初始值预览，实际保存时顺序取号。</small></div>
    </template>
    <template #footer><el-button class="confirm-rule" type="primary" :disabled="!valid" :loading="saving" @click="save">确定</el-button>
      <el-button :disabled="saving" @click="dialog=false">取消</el-button></template>
  </el-dialog>
</template>

<style scoped>
.rule-list{overflow:auto;background:white;border:1px solid #dfe4ec;border-radius:6px}
.rule-list table{width:100%;min-width:620px;border-collapse:collapse;font-size:14px;color:#465160}
.rule-list th,.rule-list td{border-right:1px solid #e1e5ec;border-bottom:1px solid #e1e5ec;text-align:center;padding:10px 15px}
.rule-list th{background:#efeff0;color:#30343a;font-weight:600;padding-block:13px}
.rule-list tbody tr:nth-child(even){background:#fafafa}.rule-list tbody tr:hover{background:#f0f6ff}
.rule-list th:first-child{width:68px}.rule-list td:nth-child(2),.rule-list td:nth-child(3){text-align:left}
.rule-list tr:last-child td{border-bottom:0}.rule-list tr>*:last-child{border-right:0}
.type-link{border:0;background:transparent;color:#1972c8;font-weight:550;padding:0}.type-link:hover{text-decoration:underline}
.rule-meta,.rule-sections{display:grid;gap:16px}.rule-meta{grid-template-columns:repeat(2,minmax(0,1fr));max-width:640px}
.rule-sections{grid-template-columns:repeat(3,minmax(0,1fr))}.rule-sections section,.preview-box{border:1px solid #d9dfe8;padding:16px;min-width:0}
.rule-sections small,.preview-box small{color:#64748b}.rule-sections .el-input-number,.rule-sections .el-select{width:100%}
.rule-sections .el-form-item:last-child{margin-bottom:8px}.period-note{display:block;margin-left:82px}.preview-heading{margin:0 0 10px;font-size:14px;font-weight:600}.preview-heading{margin-top:18px}
.formula,.preview-result{display:flex;flex-wrap:wrap;align-items:center;gap:9px}.formula b{border:1px solid #d9dfe8;border-radius:4px;background:#f7f8fa;padding:5px 18px;font-weight:400}
.formula i{font-style:normal}.preview-result{margin:12px 0 5px}.preview-result output{font:600 18px ui-monospace,monospace;color:#1e293b;overflow-wrap:anywhere}
.confirm-rule{background:#247bcc;border-color:#247bcc;color:white}
@media(max-width:760px){.rule-sections{grid-template-columns:1fr}.rule-meta{grid-template-columns:1fr}.rule-sections section{padding:10px}.rule-dialog .el-form-item{margin-bottom:10px}}
</style>
