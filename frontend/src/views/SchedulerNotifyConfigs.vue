<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, patch, del } from '@/http'
import { formatDateTime } from '@/utils/datetime'
import { View as ViewIcon } from '@element-plus/icons-vue'

type TriggerOn = 'SUCCESS' | 'FAIL' | 'ALWAYS'
type Header = { name: string; value: string }
type NotifyConfig = {
  id: string
  name: string
  method: string
  url: string
  headers?: Header[]
  bodyTemplate?: string
  triggerOn: TriggerOn
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}

const list = ref<NotifyConfig[]>([])
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)

const METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']
const TRIGGERS: { label: string; value: TriggerOn }[] = [
  { label: '失败时', value: 'FAIL' },
  { label: '成功时', value: 'SUCCESS' },
  { label: '总是', value: 'ALWAYS' }
]

// 通用变量：所有任务类型都可用
const COMMON_VARS = [
  { name: 'taskName', display: '{{taskName}}', desc: '任务名称' },
  { name: 'taskId', display: '{{taskId}}', desc: '任务 ID' },
  { name: 'status', display: '{{status}}', desc: '执行结果 SUCCESS/FAIL' },
  { name: 'protocol', display: '{{protocol}}', desc: '任务协议 API/TCP' },
  { name: 'durationMs', display: '{{durationMs}}', desc: '耗时（毫秒）' },
  { name: 'error', display: '{{error}}', desc: '错误信息' },
  { name: 'triggeredAt', display: '{{triggeredAt}}', desc: '触发时间' }
]

// API 专属变量
const API_VARS = [
  { name: 'httpStatus', display: '{{httpStatus}}', desc: 'HTTP 状态码' },
  { name: 'assertionPassed', display: '{{assertionPassed}}', desc: '断言是否通过' },
  { name: '$.response', display: '{{$.response}}', desc: '整段响应体（非 JSON 时为纯文本）' },
  { name: '$.response.xxx', display: '{{$.response.xxx}}', desc: '响应体 JSONPath，如 $.data.diff' }
]

// TCP 专属变量
const TCP_VARS = [
  { name: 'tcpTarget', display: '{{tcpTarget}}', desc: 'TCP 目标 host:port' },
  { name: 'tcpOk', display: '{{tcpOk}}', desc: '连接是否成功 true/false' }
]

// 通用过滤/转义助手（所有类型可用）
const HELPER_VARS = [
  { name: '@json', display: '{{@json($.response)}}', desc: 'JSON 转义，安全嵌入含特殊字符的值（整段响应体建议用此包裹）' },
  { name: '@base64', display: '{{@base64($.response)}}', desc: 'Base64 编码' },
  { name: '@md5', display: '{{@md5($.response)}}', desc: 'MD5 哈希' },
  { name: '@upper', display: '{{@upper($.response.code)}}', desc: '转大写' },
  { name: '@lower', display: '{{@lower($.response.code)}}', desc: '转小写' },
  { name: '@trim', display: '{{@trim($.response.code)}}', desc: '去首尾空格' },
  { name: '@substr', display: '{{@substr($.response.code|0|5)}}', desc: '截取子串 start|len' }
]

const VAR_TABS = [
  { label: '通用变量', vars: COMMON_VARS, hint: '所有任务类型可用' },
  { label: 'API 变量', vars: API_VARS, hint: '仅 API 任务有值；TCP 任务下 httpStatus/assertionPassed 为空，响应体变量无值' },
  { label: 'TCP 变量', vars: TCP_VARS, hint: '仅 TCP 任务有值；API 任务下为空' },
  { label: '助手函数', vars: HELPER_VARS, hint: '通用过滤/转义助手，所有类型可用' }
]
const activeVarTab = ref(VAR_TABS[0].label)

const varDialogVisible = ref(false)
function openVarDialog() { varDialogVisible.value = true }

const plainRevealed = ref(false)
function hasQuery(url: string) { return !!(url && url.includes('?')) }
function isMasked(url: string) { return !!(url && url.endsWith('?***')) }

// list toggle: track masked form per row to restore on second click
async function revealUrl(row: any) {
  if (!isMasked(row.url)) {
    // currently showing plaintext -> restore masked form
    if (row._maskedUrl) row.url = row._maskedUrl
    return
  }
  try {
    const masked = row.url
    const r = await get<{ url: string }>(`/scheduler/notify-configs/${row.id}/plain-url`)
    row._maskedUrl = masked
    row.url = r.url
  } catch { /* ignore */ }
}
async function revealUrlInForm() {
  if (!editingId.value) return
  if (plainRevealed.value) {
    // currently plaintext -> restore masked
    if (formMaskedUrl.value) form.url = formMaskedUrl.value
    plainRevealed.value = false
    return
  }
  try {
    formMaskedUrl.value = form.url
    const r = await get<{ url: string }>(`/scheduler/notify-configs/${editingId.value}/plain-url`)
    form.url = r.url
    plainRevealed.value = true
  } catch { /* ignore */ }
}
const formMaskedUrl = ref('')

const form = reactive({
  name: '',
  method: 'POST',
  url: '',
  headers: [] as Header[],
  bodyTemplate: '{"text":"任务 {{taskName}} {{status}}：{{error}}"}',
  triggerOn: 'FAIL' as TriggerOn,
  enabled: true
})

async function load() {
  list.value = await get<NotifyConfig[]>('/scheduler/notify-configs')
}
onMounted(load)

const query = ref('')
const filteredList = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return list.value
  return list.value.filter(c => [c.name, c.method, c.url, c.triggerOn]
    .some(v => String(v ?? '').toLowerCase().includes(q)))
})

function resetForm() {
  editingId.value = null
  plainRevealed.value = false
  formMaskedUrl.value = ''
  form.name = ''
  form.method = 'POST'
  form.url = ''
  form.headers = []
  form.bodyTemplate = '{"text":"任务 {{taskName}} {{status}}：{{error}}"}'
  form.triggerOn = 'FAIL'
  form.enabled = true
}

function openCreate() { resetForm(); dialogVisible.value = true }

function openEdit(c: any) {
  editingId.value = c.id
  plainRevealed.value = false
  formMaskedUrl.value = ''
  form.name = c.name
  form.method = c.method
  form.url = c.url
  form.headers = c.headers ? c.headers.map((h: Header) => ({ ...h })) : []
  form.bodyTemplate = c.bodyTemplate ?? ''
  form.triggerOn = c.triggerOn ?? 'FAIL'
  form.enabled = c.enabled ?? true
  dialogVisible.value = true
}

async function submit() {
  if (!form.name.trim()) { ElMessage.error('请输入名称'); return }
  if (!form.url.trim()) { ElMessage.error('请输入请求地址'); return }
  const body: any = {
    name: form.name, method: form.method, url: form.url,
    headers: form.headers.filter(h => h.name), bodyTemplate: form.bodyTemplate,
    triggerOn: form.triggerOn
  }
  if (editingId.value) {
    body.enabled = form.enabled
    await patch(`/scheduler/notify-configs/${editingId.value}`, body)
    ElMessage.success('已保存')
  } else {
    await post('/scheduler/notify-configs', body)
    ElMessage.success('已创建')
  }
  dialogVisible.value = false
  await load()
}

async function remove(c: any) {
  await ElMessageBox.confirm(`删除通知配置「${c.name}」？`, { type: 'warning' })
  await del(`/scheduler/notify-configs/${c.id}`)
  ElMessage.success('已删除')
  await load()
}

async function toggleEnabled(c: any) {
  await post(`/scheduler/notify-configs/${c.id}/${c.enabled ? 'disable' : 'enable'}`)
  ElMessage.success(c.enabled ? '已禁用' : '已恢复')
  await load()
}

function triggerLabel(t: TriggerOn) { return TRIGGERS.find(x => x.value === t)?.label ?? t }
function triggerType(t: TriggerOn) { return t === 'FAIL' ? 'danger' : t === 'SUCCESS' ? 'success' : 'info' }
function addHeader() { form.headers.push({ name: '', value: '' }) }
function removeHeader(i: number) { form.headers.splice(i, 1) }
</script>

<template>
  <div>
    <div class="header">
      <h2 class="page-h">通知配置</h2>
      <div class="actions">
        <el-input v-model="query" clearable placeholder="搜索名称 / 方法 / 地址 / 触发时机" style="width: 300px" />
        <el-button type="primary" @click="openCreate">+ 新建通知配置</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
      通知配置用于定时任务执行后主动推送通知。请求体可用变量见下方「可用变量」。
    </el-alert>

    <el-table :data="filteredList" empty-text="尚无通知配置">
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column label="请求" min-width="260">
        <template #default="{ row }">
          <span class="mono">{{ row.method }} {{ row.url }}</span>
          <el-tooltip v-if="hasQuery(row.url)" :content="isMasked(row.url) ? '查看明文' : '恢复脱敏'" placement="top">
            <el-button link type="primary" size="small" style="margin-left: 4px"
                       :icon="ViewIcon" @click="revealUrl(row)" />
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="触发时机" width="100">
        <template #default="{ row }">
          <el-tag :type="triggerType(row.triggerOn)" size="small">{{ triggerLabel(row.triggerOn) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" align="center">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="warning" @click="toggleEnabled(row)">{{ row.enabled ? '禁用' : '恢复' }}</el-button>
          <el-button size="small" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑通知配置' : '新建通知配置'" width="720px" :close-on-click-modal="false">
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="例如：钉钉告警群" />
        </el-form-item>
        <el-form-item label="HTTP 方法">
          <el-select v-model="form.method" style="width: 140px">
            <el-option v-for="m in METHODS" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="请求地址" required>
          <div style="display:flex;gap:8px;width:100%">
            <el-input v-model="form.url" :placeholder="editingId && hasQuery(form.url) ? form.url : 'https://oapi.dingtalk.com/robot/send?access_token=...'" />
            <el-tooltip v-if="editingId && hasQuery(form.url)" :content="plainRevealed ? '恢复脱敏' : '查看明文'" placement="top">
              <el-button link type="primary" :icon="ViewIcon" @click="revealUrlInForm" />
            </el-tooltip>
          </div>
        </el-form-item>
        <el-form-item label="请求头">
          <div class="kv-list">
            <div v-for="(h, i) in form.headers" :key="i" class="kv-row">
              <el-input v-model="h.name" placeholder="Header 名称" style="width: 200px" />
              <el-input v-model="h.value" placeholder="值" style="width: 280px" />
              <el-button link type="danger" @click="removeHeader(i)">删除</el-button>
            </div>
            <el-button size="small" @click="addHeader">+ 添加请求头</el-button>
          </div>
        </el-form-item>
        <el-form-item label="请求体" required>
          <div style="width: 100%">
            <div style="display:flex;justify-content:flex-end;margin-bottom:4px">
              <el-button size="small" link type="primary" @click="openVarDialog">查看可用变量</el-button>
            </div>
            <el-input v-model="form.bodyTemplate" type="textarea" :rows="5" placeholder='raw JSON，如 {"text":"任务 {{taskName}} {{status}}：{{error}}"}' />
            <div class="muted">整段响应体请用 {{ '@json' }} 包裹避免破坏 JSON：{"text":"{{ '@json' }}($.response)}"}</div>
          </div>
        </el-form-item>
        <el-form-item label="触发时机">
          <el-radio-group v-model="form.triggerOn">
            <el-radio v-for="t in TRIGGERS" :key="t.value" :value="t.value">{{ t.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="varDialogVisible" title="可用变量" width="780px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
        在请求体模板中用对应语法插入变量，渲染时替换为实际值。请按任务类型查看对应 tab；跨类型变量会在不适用的任务下渲染为空。整段响应体（尤其含 HTML/特殊字符）请用 @json 包裹以免破坏 JSON。
      </el-alert>
      <el-tabs v-model="activeVarTab">
        <el-tab-pane v-for="tab in VAR_TABS" :key="tab.label" :label="tab.label" :name="tab.label">
          <div class="muted" style="margin-bottom: 8px">{{ tab.hint }}</div>
          <el-table :data="tab.vars" size="small" border>
            <el-table-column prop="display" label="用法" width="300">
              <template #default="{ row }"><code>{{ row.display }}</code></template>
            </el-table-column>
            <el-table-column prop="desc" label="说明" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button type="primary" @click="varDialogVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header { display:flex; justify-content: space-between; align-items: center; margin-bottom: 16px; gap: 12px; }
.actions { display:flex; align-items: center; gap: 8px; }
.page-h { color: var(--la-fg); margin: 0; }
.muted { color: var(--la-fg-muted); font-size: 12px; }
.mono { font-family: ui-monospace, monospace; font-size: 12px; }
.kv-list { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.kv-row { display: flex; align-items: center; gap: 8px; }
.vars { display: flex; flex-wrap: wrap; gap: 2px; }
</style>
