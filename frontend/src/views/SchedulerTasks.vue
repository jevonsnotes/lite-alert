<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, patch, del } from '@/http'
import { formatDateTime } from '@/utils/datetime'
import {
  Edit, Promotion, VideoPause, VideoPlay, Delete, View as ViewIcon, CopyDocument
} from '@element-plus/icons-vue'

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH' | 'HEAD' | 'OPTIONS'
type BodyType = 'NONE' | 'FORM_DATA' | 'URL_ENCODED' | 'RAW'
type RawType = 'JSON' | 'XML' | 'TEXT'
type Logic = 'AND' | 'OR'
type Operator = 'EQ' | 'NE' | 'CONTAINS' | 'REGEX' | 'GT' | 'LT' | 'EXISTS'

type Header = { name: string; value: string }
type FormField = { name: string; value: string }
type Condition = {
  path: string
  operator: Operator
  expected: string
}
type Assertion = { logic: Logic; conditions: Condition[] }
type BodyConfig = {
  type: BodyType
  rawType?: RawType
  rawText?: string
  fields?: FormField[]
}
type ApiTaskConfig = {
  type?: string
  method: HttpMethod
  url: string
  headers?: Header[]
  body?: BodyConfig
  assertion?: Assertion
  timeouts?: { connect: number | null; read: number | null; write: number | null }
}
type TaskStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED'
type TcpTaskConfig = {
  type?: string
  host: string
  port: number | null
  timeouts?: { connect: number | null; read: number | null; write: number | null }
}
type TaskConfigUnion = ApiTaskConfig | TcpTaskConfig
type SchedulerTask = {
  id: string
  ownerId: string
  name: string
  description?: string
  taskType: string
  cron: string
  enabled: boolean
  status: TaskStatus
  hasPendingChanges?: boolean
  notifyConfigIds?: string[]
  draftConfig: TaskConfigUnion
  publishedConfig: TaskConfigUnion | null
  publishedAt?: string
  createdAt?: string
  updatedAt?: string
}

const list = ref<SchedulerTask[]>([])
const query = ref('')
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const editingTask = ref<any>(null)

type DiffEntry = { field: string; oldValue: string | null; newValue: string | null; changeType: 'ADDED' | 'REMOVED' | 'CHANGED' }
type DiffResult = { hasPendingChanges: boolean; diffs: DiffEntry[] }
const diffDrawerVisible = ref(false)
const diffTaskName = ref('')
const diffEntries = ref<DiffEntry[]>([])

const METHODS: HttpMethod[] = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS']
const BODY_TYPES: { label: string; value: BodyType }[] = [
  { label: 'none', value: 'NONE' },
  { label: 'form-data', value: 'FORM_DATA' },
  { label: 'x-www-form-urlencoded', value: 'URL_ENCODED' },
  { label: 'raw', value: 'RAW' }
]
const RAW_TYPES: RawType[] = ['JSON', 'XML', 'TEXT']
const OPERATORS: { label: string; value: Operator }[] = [
  { label: '等于 (=)', value: 'EQ' },
  { label: '不等于 (!=)', value: 'NE' },
  { label: '包含', value: 'CONTAINS' },
  { label: '正则', value: 'REGEX' },
  { label: '大于', value: 'GT' },
  { label: '小于', value: 'LT' },
  { label: '存在', value: 'EXISTS' }
]

const form = reactive({
  name: '',
  description: '',
  cron: '0 */5 * * * *',
  taskType: 'API',
  method: 'GET' as HttpMethod,
  url: '',
  headers: [] as Header[],
  bodyType: 'NONE' as BodyType,
  rawType: 'JSON' as RawType,
  rawText: '',
  fields: [] as FormField[],
  assertionEnabled: false,
  logic: 'AND' as Logic,
  conditions: [] as Condition[],
  notifyConfigIds: [] as string[],
  connectTimeout: 5 as number | null,
  readTimeout: 30 as number | null,
  writeTimeout: 30 as number | null,
  // TCP fields
  tcpHost: '',
  tcpPort: 3306 as number | null
})

const notifyConfigs = ref<{ id: string; name: string; triggerOn: string }[]>([])

const isEditing = computed(() => !!editingId.value)
const dialogTitle = computed(() => isEditing.value ? '编辑定时任务' : '新建定时任务')
const filteredList = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return list.value
  return list.value.filter(t => [t.name, t.cron, t.status, t.taskType].some(v => String(v).toLowerCase().includes(q)))
})

async function load() {
  const [tasks, ncs] = await Promise.all([
    get<SchedulerTask[]>('/scheduler/tasks'),
    get<{ id: string; name: string; triggerOn: string }[]>('/scheduler/notify-configs').catch(() => [])
  ])
  list.value = tasks
  notifyConfigs.value = ncs
}
onMounted(load)

function emptyConfig(): ApiTaskConfig {
  return { method: 'GET', url: '', headers: [], body: { type: 'NONE', rawType: 'JSON', rawText: '', fields: [] } }
}

function isTcp(t: any): boolean {
  return t?.taskType === 'TCP' || t?.type === 'TCP'
}

function resetForm() {
  editingId.value = null
  editingTask.value = null
  form.name = ''
  form.description = ''
  form.cron = '0 */5 * * * *'
  form.taskType = 'API'
  form.method = 'GET'
  form.url = ''
  form.headers = []
  form.bodyType = 'NONE'
  form.rawType = 'JSON'
  form.rawText = ''
  form.fields = []
  form.assertionEnabled = false
  form.logic = 'AND'
  form.conditions = []
  form.notifyConfigIds = []
  form.connectTimeout = 5
  form.readTimeout = 30
  form.writeTimeout = 30
  form.tcpHost = ''
  form.tcpPort = 3306
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(t: any) {
  editingId.value = t.id
  editingTask.value = t
  const cfg = t.draftConfig ?? emptyConfig()
  form.name = t.name
  form.description = t.description ?? ''
  form.cron = t.cron
  form.taskType = t.taskType
  form.method = (cfg.method as HttpMethod) ?? 'GET'
  form.url = cfg.url ?? ''
  form.headers = cfg.headers ? (cfg.headers as Header[]).map((h: Header) => ({ ...h })) : []
  form.bodyType = (cfg.body?.type as BodyType) ?? 'NONE'
  form.rawType = (cfg.body?.rawType as RawType) ?? 'JSON'
  form.rawText = cfg.body?.rawText ?? ''
  form.fields = cfg.body?.fields ? (cfg.body.fields as FormField[]).map((f: FormField) => ({ ...f })) : []
  form.assertionEnabled = !!cfg.assertion && (cfg.assertion.conditions?.length ?? 0) > 0
  form.logic = (cfg.assertion?.logic as Logic) ?? 'AND'
  form.conditions = cfg.assertion?.conditions ? (cfg.assertion.conditions as Condition[]).map((c: Condition) => ({ ...c })) : []
  form.notifyConfigIds = t.notifyConfigIds ? [...t.notifyConfigIds] : []
  const to = (t.draftConfig?.timeouts ?? {}) as any
  form.connectTimeout = to.connect ?? 5
  form.readTimeout = to.read ?? 30
  form.writeTimeout = to.write ?? 30
  // TCP fields
  form.tcpHost = cfg.host ?? ''
  form.tcpPort = cfg.port ?? 3306
  dialogVisible.value = true
}

/** Copy a task's config into the create dialog (new task), name defaults to "<orig>_copy". */
function openCopy(t: any) {
  openEdit(t)              // populate the form from the source task
  editingId.value = null   // switch to create mode so submit creates a new task
  editingTask.value = null
  form.name = `${t.name}_copy`
}

function buildConfig(): TaskConfigUnion {
  if (form.taskType === 'TCP') {
    return {
      type: 'TCP',
      host: form.tcpHost,
      port: form.tcpPort,
      timeouts: { connect: form.connectTimeout, read: form.readTimeout, write: form.writeTimeout }
    }
  }
  const cfg: ApiTaskConfig = { type: 'API', method: form.method, url: form.url, headers: form.headers.filter(h => h.name) }
  const body: BodyConfig = { type: form.bodyType }
  if (form.bodyType === 'RAW') { body.rawType = form.rawType; body.rawText = form.rawText }
  else if (form.bodyType === 'FORM_DATA' || form.bodyType === 'URL_ENCODED') { body.fields = form.fields.filter(f => f.name) }
  cfg.body = body
  if (form.assertionEnabled && form.conditions.length > 0) {
    cfg.assertion = { logic: form.logic, conditions: form.conditions.filter(c => c.path) }
  }
  cfg.timeouts = {
    connect: form.connectTimeout,
    read: form.readTimeout,
    write: form.writeTimeout
  }
  return cfg
}

async function submitCreate() {
  if (!form.name.trim()) { ElMessage.error('请输入任务名称'); return }
  if (!form.cron.trim()) { ElMessage.error('请输入 Cron 表达式'); return }
  await post('/scheduler/tasks', {
    name: form.name, description: form.description, taskType: form.taskType,
    cron: form.cron, config: buildConfig(), notifyConfigIds: form.notifyConfigIds
  })
  ElMessage.success('已创建（草稿状态，发布后才会开始执行）')
  dialogVisible.value = false
  await load()
}

async function submitSaveDraft() {
  if (!form.cron.trim()) { ElMessage.error('请输入 Cron 表达式'); return }
  await patch(`/scheduler/tasks/${editingId.value}`, {
    name: form.name, description: form.description, cron: form.cron, config: buildConfig(),
    notifyConfigIds: form.notifyConfigIds
  })
  ElMessage.success('草稿已保存（未发布，运行中仍使用旧配置）')
  dialogVisible.value = false
  await load()
}

async function publish(t: any) {
  let diff: DiffResult | null = null
  if (t.publishedConfig) {
    try { diff = await get<DiffResult>(`/scheduler/tasks/${t.id}/diff`) } catch { /* ignore preview failure */ }
  }
  const pending = diff?.hasPendingChanges
  if (pending) {
    // preview the diff first, let the user decide
    await openDiff(t)
    try {
      await ElMessageBox.confirm(
        `「${t.name}」有 ${diff!.diffs.length} 处未发布改动，确认发布使其立即生效？`,
        '确认发布', { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '暂不发布' }
      )
    } catch { return }
  } else {
    try {
      await ElMessageBox.confirm(
        `发布「${t.name}」？${t.publishedConfig ? '草稿与已发布无改动。' : '首次发布，将立即开始执行。'}`,
        '发布任务', { type: 'warning' }
      )
    } catch { return }
  }
  await post(`/scheduler/tasks/${t.id}/publish`)
  ElMessage.success('已发布，新配置已生效')
  await load()
}

async function openDiff(t: any) {
  diffTaskName.value = t.name
  const res = await get<DiffResult>(`/scheduler/tasks/${t.id}/diff`)
  diffEntries.value = res.diffs
  diffDrawerVisible.value = true
}

function diffChangeTypeTag(c: DiffEntry['changeType']) {
  return c === 'ADDED' ? 'success' : c === 'REMOVED' ? 'danger' : 'warning'
}
function diffChangeTypeLabel(c: DiffEntry['changeType']) {
  return c === 'ADDED' ? '新增' : c === 'REMOVED' ? '移除' : '变更'
}

async function toggleEnabled(t: any) {
  if (t.enabled) {
    await post(`/scheduler/tasks/${t.id}/disable`)
    ElMessage.success('已停用')
  } else {
    await post(`/scheduler/tasks/${t.id}/enable`)
    ElMessage.success('已启用')
  }
  await load()
}

async function remove(t: any) {
  await ElMessageBox.confirm(`删除「${t.name}」？将同时清除其调用记录。`, { type: 'warning' })
  await del(`/scheduler/tasks/${t.id}`)
  ElMessage.success('已删除')
  await load()
}

function statusTagType(s: TaskStatus) {
  return s === 'PUBLISHED' ? 'success' : s === 'DISABLED' ? 'info' : 'warning'
}
function statusLabel(s: TaskStatus) {
  return s === 'PUBLISHED' ? '已发布' : s === 'DISABLED' ? '已停用' : '草稿'
}

// header / field / condition row helpers
function addHeader() { form.headers.push({ name: '', value: '' }) }
function removeHeader(i: number) { form.headers.splice(i, 1) }
function addField() { form.fields.push({ name: '', value: '' }) }
function removeField(i: number) { form.fields.splice(i, 1) }
function addCondition() { form.conditions.push({ path: '', operator: 'EQ', expected: '' }) }
function removeCondition(i: number) { form.conditions.splice(i, 1) }
</script>

<template>
  <div>
    <div class="header">
      <div class="actions">
        <el-input v-model="query" clearable placeholder="搜索名称 / Cron / 状态" style="width: 280px" />
        <el-button type="primary" @click="openCreate">+ 新建任务</el-button>
      </div>
    </div>

    <el-table :data="filteredList" empty-text="尚无定时任务">
      <el-table-column prop="name" label="名称" width="180" />
      <el-table-column prop="taskType" label="类型" width="90" />
      <el-table-column label="Cron" width="180">
        <template #default="{ row }"><span class="mono">{{ row.cron }}</span></template>
      </el-table-column>
      <el-table-column label="目标" min-width="220">
        <template #default="{ row }">
          <span v-if="isTcp(row)" class="mono">{{ row.draftConfig?.host }}:{{ row.draftConfig?.port }}</span>
          <span v-else class="mono">{{ row.draftConfig?.method }} {{ row.draftConfig?.url }}</span>
        </template>
      </el-table-column>
      <el-table-column label="发布状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          <el-tag v-if="row.hasPendingChanges" type="warning" size="small" effect="plain" style="margin-left: 4px">
            有改动
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启停" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发布时间" width="160">
        <template #default="{ row }">
          <span v-if="row.publishedAt">{{ formatDateTime(row.publishedAt) }}</span>
          <span v-else class="muted">未发布</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" align="center">
        <template #default="{ row }">
          <el-tooltip content="编辑" placement="top">
            <el-button size="small" link type="primary" :icon="Edit" @click="openEdit(row)" />
          </el-tooltip>
          <el-tooltip content="复制" placement="top">
            <el-button size="small" link type="primary" :icon="CopyDocument" @click="openCopy(row)" />
          </el-tooltip>
          <el-tooltip v-if="row.publishedConfig" content="对比已发布" placement="top">
            <el-button size="small" link type="info" :icon="ViewIcon" @click="openDiff(row)" />
          </el-tooltip>
          <el-tooltip content="发布" placement="top">
            <el-button size="small" link type="success" :icon="Promotion" @click="publish(row)" />
          </el-tooltip>
          <el-tooltip :content="row.enabled ? '停用' : '启用'" placement="top">
            <el-button size="small" link type="warning"
                       :icon="row.enabled ? VideoPause : VideoPlay"
                       @click="toggleEnabled(row)" />
          </el-tooltip>
          <el-tooltip content="删除" placement="top">
            <el-button size="small" link type="danger" :icon="Delete" @click="remove(row)" />
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="820px" :close-on-click-modal="false">
      <el-alert v-if="isEditing" type="info" :closable="false" show-icon style="margin-bottom: 12px">
        保存只更新草稿；点「发布」后新配置才会生效，在此之前调度器继续使用旧配置。
        <el-button v-if="editingTask?.publishedConfig" link type="primary" size="small" style="margin-left: 8px"
                   @click="openDiff(editingTask)">对比已发布版本（基于已保存草稿）</el-button>
      </el-alert>
      <el-form label-width="100px">
        <el-form-item label="任务名称" required>
          <el-input v-model="form.name" placeholder="例如：订单对账探活" />
        </el-form-item>
        <el-form-item label="任务类型">
          <el-select v-model="form.taskType" style="width: 180px" :disabled="isEditing">
            <el-option label="API 任务" value="API" />
            <el-option label="TCP 任务" value="TCP" />
          </el-select>
          <div class="muted" style="margin-left: 12px">
            {{ form.taskType === 'TCP' ? 'TCP 连通性探活：连上即成功' : '主动调用 HTTP 接口并用响应断言判定' }}
          </div>
        </el-form-item>
        <el-form-item label="Cron 表达式" required>
          <el-input v-model="form.cron" placeholder="6 段式，如 0 */5 * * * *（每 5 分钟）" style="width: 360px" />
          <span class="muted">秒 分 时 日 月 周</span>
        </el-form-item>

        <!-- TCP target -->
        <template v-if="form.taskType === 'TCP'">
          <el-form-item label="目标主机" required>
            <el-input v-model="form.tcpHost" placeholder="例如 10.0.0.5 或 example.com" style="width: 320px" />
          </el-form-item>
          <el-form-item label="目标端口" required>
            <el-input-number v-model="form.tcpPort" :min="1" :max="65535" controls-position="right" />
            <span class="muted">1 - 65535</span>
          </el-form-item>
          <el-form-item label="连接超时(秒)">
            <el-input-number v-model="form.connectTimeout" :min="0" :max="600" size="small" controls-position="right" />
            <span class="muted">默认 5s；0 = 不限制</span>
          </el-form-item>
        </template>

        <!-- API request definition -->
        <template v-else>
        <el-form-item label="HTTP 方法">
          <el-select v-model="form.method" style="width: 140px">
            <el-option v-for="m in METHODS" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="请求地址" required>
          <el-input v-model="form.url" placeholder="https://example.com/api/health" />
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
          <div class="muted">如显式设置 Content-Type，将以你的值为准；否则系统按下方请求体类型自动生成。</div>
        </el-form-item>

        <el-form-item label="请求体">
          <el-radio-group v-model="form.bodyType">
            <el-radio v-for="b in BODY_TYPES" :key="b.value" :value="b.value">{{ b.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.bodyType === 'RAW'" label="Raw 类型">
          <el-radio-group v-model="form.rawType">
            <el-radio v-for="r in RAW_TYPES" :key="r" :value="r">{{ r.toLowerCase() }}</el-radio>
          </el-radio-group>
          <el-input v-model="form.rawText" type="textarea" :rows="4" placeholder="请求体原文" />
        </el-form-item>
        <el-form-item v-if="form.bodyType === 'FORM_DATA' || form.bodyType === 'URL_ENCODED'" label="表单字段">
          <div class="kv-list">
            <div v-for="(f, i) in form.fields" :key="i" class="kv-row">
              <el-input v-model="f.name" placeholder="字段名" style="width: 200px" />
              <el-input v-model="f.value" placeholder="值" style="width: 280px" />
              <el-button link type="danger" @click="removeField(i)">删除</el-button>
            </div>
            <el-button size="small" @click="addField">+ 添加字段</el-button>
          </div>
        </el-form-item>

        <el-form-item label="响应断言">
          <el-switch v-model="form.assertionEnabled" />
          <span class="muted">提取响应体值判断成功/失败，支持多个条件</span>
        </el-form-item>
        <template v-if="form.assertionEnabled">
          <el-form-item label="逻辑">
            <el-radio-group v-model="form.logic">
              <el-radio value="AND">全部满足 (AND)</el-radio>
              <el-radio value="OR">任一满足 (OR)</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="条件">
            <div class="kv-list">
              <div v-for="(c, i) in form.conditions" :key="i" class="kv-row">
                <el-input v-model="c.path" placeholder="JSONPath 如 $.code" style="width: 200px" />
                <el-select v-model="c.operator" style="width: 150px">
                  <el-option v-for="o in OPERATORS" :key="o.value" :label="o.label" :value="o.value" />
                </el-select>
                <el-input v-model="c.expected" placeholder="期望值" style="width: 200px" />
                <el-button link type="danger" @click="removeCondition(i)">删除</el-button>
              </div>
              <el-button size="small" @click="addCondition">+ 添加条件</el-button>
            </div>
          </el-form-item>
        </template>
        </template><!-- /API request definition -->

        <el-form-item label="通知配置">
          <el-select v-model="form.notifyConfigIds" multiple filterable placeholder="选择通知配置（执行后推送）" style="width: 100%">
            <el-option v-for="n in notifyConfigs" :key="n.id"
                       :label="`${n.name}（${n.triggerOn === 'FAIL' ? '失败时' : n.triggerOn === 'SUCCESS' ? '成功时' : '总是'}）`"
                       :value="n.id" />
          </el-select>
          <div class="muted">仅可选择自己拥有的通知配置；保存草稿后需发布才生效。</div>
        </el-form-item>

        <el-form-item v-if="form.taskType === 'API'" label="超时(秒)">
          <div class="kv-row">
            <el-input-number v-model="form.connectTimeout" :min="0" :max="600" size="small" controls-position="right" />
            <span class="muted">连接</span>
            <el-input-number v-model="form.readTimeout" :min="0" :max="3600" size="small" controls-position="right" />
            <span class="muted">读</span>
            <el-input-number v-model="form.writeTimeout" :min="0" :max="3600" size="small" controls-position="right" />
            <span class="muted">写</span>
          </div>
          <div class="muted">默认连接 5s、读/写 30s；0 = 不限制。保存草稿后需发布才生效。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-if="isEditing" type="primary" @click="submitSaveDraft">保存草稿</el-button>
        <el-button v-else type="primary" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="diffDrawerVisible" :title="`草稿 vs 已发布：${diffTaskName}`" size="640px">
      <el-alert v-if="diffEntries.length === 0" type="success" :closable="false" show-icon>
        草稿与已发布配置一致，无未发布改动。
      </el-alert>
      <el-table v-else :data="diffEntries" size="small">
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag :type="diffChangeTypeTag(row.changeType)" size="small">{{ diffChangeTypeLabel(row.changeType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="field" label="字段" width="200">
          <template #default="{ row }"><code>{{ row.field }}</code></template>
        </el-table-column>
        <el-table-column label="已发布 → 草稿">
          <template #default="{ row }">
            <span class="muted">{{ row.oldValue ?? '（无）' }}</span>
            <span class="arrow"> → </span>
            <strong>{{ row.newValue ?? '（无）' }}</strong>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="diffDrawerVisible = false">关闭</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.header { display:flex; justify-content: flex-end; align-items: center; margin-bottom: 16px; gap: 12px; }
.actions { display:flex; align-items: center; gap: 8px; }
.muted { color: var(--la-fg-muted); font-size: 12px; }
.mono { font-family: ui-monospace, monospace; font-size: 12px; }
.kv-list { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.kv-row { display: flex; align-items: center; gap: 8px; }
.arrow { color: var(--la-fg-muted); margin: 0 4px; }
</style>
