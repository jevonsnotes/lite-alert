<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { get } from '@/http'
import { formatDateTime } from '@/utils/datetime'

type Label = { id: string; name?: string; prefix?: string }

type AuditRow = {
  ts: string
  type: string
  traceId?: string
  topicId?: string
  apiKeyId?: string
  remoteIp?: string
  actor?: string
  code?: string
  message?: string
  channel?: string
  targetId?: string
  deliveryId?: string
  _topic?: Label
  _namespace?: Label
  _apiKey?: Label
  _actor?: Label
  [key: string]: any
}

type AuditResp = {
  items: AuditRow[]
  page: number
  size: number
  total: number
  pageCount: number
  stats: {
    from: string
    to: string
    filesScanned: number
    filesMissing: number
    rawLines: number
    parseFailed: number
    matched: number
    visible: number
    canViewAll: boolean
    userId: string
  }
}

type DeliveryDetail = {
  id: string
  traceId?: string
  topicId?: string
  targetId?: string
  channel?: string
  status?: string
  attempt?: number
  nextRetryAt?: string
  lastError?: string
  createdAt?: string
  finishedAt?: string
  payload?: any
}

type SearchField = 'all' | 'topic' | 'namespace' | 'apikey' | 'actor' | 'trace' | 'ip' | 'code'
type Unit = 'DAYS' | 'MONTHS' | 'YEARS'

const SEARCH_FIELDS: { label: string; value: SearchField }[] = [
  { label: '全部',         value: 'all' },
  { label: 'Topic',        value: 'topic' },
  { label: '命名空间',      value: 'namespace' },
  { label: 'ApiKey',       value: 'apikey' },
  { label: '操作者',        value: 'actor' },
  { label: 'Trace',        value: 'trace' },
  { label: 'IP',           value: 'ip' },
  { label: '错误码 / 消息', value: 'code' }
]

const UNITS: { label: string; value: Unit }[] = [
  { label: '天', value: 'DAYS' },
  { label: '月', value: 'MONTHS' },
  { label: '年', value: 'YEARS' }
]

/** Compute "today minus N units" inclusive of today as the start date. */
function spanStart(value: number, unit: Unit): string {
  const d = new Date()
  if (unit === 'DAYS') d.setDate(d.getDate() - (value - 1))
  else if (unit === 'MONTHS') { d.setMonth(d.getMonth() - value); d.setDate(d.getDate() + 1) }
  else if (unit === 'YEARS') { d.setFullYear(d.getFullYear() - value); d.setDate(d.getDate() + 1) }
  return d.toISOString().slice(0, 10)
}

const auth = useAuthStore()

const rows = ref<AuditRow[]>([])
const stats = ref<AuditResp['stats'] | null>(null)
const totalCount = ref(0)
const pageCount = ref(0)

const page = ref(0)
const size = ref(50)
const filterType = ref('')
const search = ref('')
const searchField = ref<SearchField>('all')

// "Last N units" picker — defaults to today only.
const rangeValue = ref(1)
const rangeUnit = ref<Unit>('DAYS')

const loading = ref(false)
const deliveryLoading = ref(false)
const deliveryDialogVisible = ref(false)
const deliveryDetail = ref<DeliveryDetail | null>(null)
const countdownText = ref('')
const lastError = ref<string | null>(null)
let countdownTimer: number | null = null

let searchTimer: number | null = null

const fromDate = computed(() => spanStart(rangeValue.value, rangeUnit.value))
const toDate = computed(() => new Date().toISOString().slice(0, 10))

async function load() {
  loading.value = true
  lastError.value = null
  const params: any = {
    page: page.value,
    size: size.value,
    from: fromDate.value,
    to: toDate.value
  }
  if (filterType.value) params.type = filterType.value
  if (search.value.trim()) {
    params.q = search.value.trim()
    params.field = searchField.value
  }
  console.info('[audit] GET /api/audit', params)
  try {
    const resp = await get<AuditResp>('/audit', { params })
    rows.value = resp.items ?? []
    stats.value = resp.stats ?? null
    totalCount.value = resp.total
    pageCount.value = resp.pageCount
    console.info('[audit] ←', resp.stats)
  } catch (e: any) {
    lastError.value = `${e?.code ?? 'ERROR'}: ${e?.message ?? e}`
    console.error('[audit] failed', e)
  } finally {
    loading.value = false
  }
}

/** Any filter change resets to page 0; pagination clicks keep their value. */
function reload() {
  page.value = 0
  load()
}

function onSearchInput() {
  if (searchTimer) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(reload, 300)
}

onMounted(load)

onUnmounted(() => { if (countdownTimer) window.clearInterval(countdownTimer) })

const QUICK_TYPES = [
  { label: '全部', value: '' },
  { label: 'Webhook 受理', value: 'webhook.accepted' },
  { label: 'Webhook 拒绝', value: 'webhook.rejected' },
  { label: '通知送达', value: 'notify.sent' },
  { label: '通知失败', value: 'notify.failed' },
  { label: '登录', value: 'login.success' }
]

function tagType(t: string) {
  if (t.startsWith('notify.sent') || t.startsWith('webhook.accepted') || t.endsWith('.success')) return 'success'
  if (t.includes('rejected') || t.includes('failed') || t.includes('denied') || t.includes('locked')) return 'danger'
  if (t.includes('rate_limited') || t.includes('give_up')) return 'warning'
  return 'info'
}

function detailFields(row: any) {
  const skip = new Set([
    'ts', 'type', 'traceId', 'topicId', 'apiKeyId', 'remoteIp', 'actor',
    'code', 'message', 'channel', 'targetId', 'deliveryId',
    '_topic', '_namespace', '_apiKey', '_actor'
  ])
  const out: Record<string, any> = {}
  for (const [k, v] of Object.entries(row)) {
    if (skip.has(k)) continue
    out[k] = v
  }
  return out
}

const emptyHint = ref('')
function refreshEmptyHint() {
  if (!stats.value) { emptyHint.value = ''; return }
  const s = stats.value
  if (s.filesScanned === 0) {
    emptyHint.value = `${s.from} 至 ${s.to} 区间没有任何审计文件。换个时间范围试试。`
  } else if (s.rawLines === 0) {
    emptyHint.value = '范围内的文件都是空的（启动后还没有任何事件）。'
  } else if (s.parseFailed > 0 && s.parseFailed === s.rawLines) {
    emptyHint.value = `${s.rawLines} 行全部解析失败，可能是文件被旧版本写花了。`
  } else if (s.matched === 0) {
    emptyHint.value = `区间内有 ${s.rawLines} 条记录，但没有匹配当前的事件类型 / 搜索条件。`
  } else if (s.visible === 0) {
    emptyHint.value = `匹配的有 ${s.matched} 条，但当前用户（${s.userId}）没权限看 —— 这些事件属于别人的 Topic 或别的用户。`
  } else {
    emptyHint.value = ''
  }
}
watch(stats, refreshEmptyHint, { immediate: true })

function searchBy(field: SearchField, value?: string) {
  if (!value) return
  searchField.value = field
  search.value = value
  reload()
}

async function openDelivery(row: any) {
  if (!row.deliveryId) return
  deliveryLoading.value = true
  deliveryDialogVisible.value = true
  deliveryDetail.value = null
  countdownText.value = ''
  if (countdownTimer) window.clearInterval(countdownTimer)
  try {
    deliveryDetail.value = await get<DeliveryDetail>(`/deliveries/${encodeURIComponent(row.deliveryId)}`)
  } catch (e: any) {
    deliveryDialogVisible.value = false
    ElMessage.error(`${e?.code ?? 'ERROR'}: ${e?.message ?? e}`)
  } finally {
    deliveryLoading.value = false
  }
  startCountdown()
}

function startCountdown() {
  if (countdownTimer) window.clearInterval(countdownTimer)
  if (!deliveryDetail.value) return
  const d = deliveryDetail.value
  if (d.status !== 'RETRY_WAIT' || !d.nextRetryAt) {
    countdownText.value = ''
    return
  }
  const update = () => {
    if (!deliveryDetail.value || !deliveryDetail.value.nextRetryAt) { countdownText.value = ''; return }
    const retryMs = new Date(deliveryDetail.value.nextRetryAt).getTime()
    const diff = retryMs - Date.now()
    if (diff <= 0) { countdownText.value = '即将重试'; countdownTimer && window.clearInterval(countdownTimer); return }
    const secs = Math.floor(diff / 1000)
    const mm = Math.floor(secs / 60)
    const ss = secs % 60
    countdownText.value = `${mm}分${ss}秒后重试`
  }
  update()
  countdownTimer = window.setInterval(update, 1000)
}

function prettyPayload(payload: any) {
  if (payload == null) return ''
  if (typeof payload === 'string') {
    try { return JSON.stringify(JSON.parse(payload), null, 2) }
    catch { return payload }
  }
  return JSON.stringify(payload, null, 2)
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2 class="page-h">调用记录 / 审计日志</h2>
      <span class="muted-inline">
        当前用户：<code>{{ auth.user?.username }}</code>
      </span>
    </div>

    <div class="filters">
      <el-radio-group v-model="filterType" size="small" @change="reload">
        <el-radio-button v-for="t in QUICK_TYPES" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
      </el-radio-group>
      <div class="search-group">
        <el-select v-model="searchField" size="small" style="width: 130px"
                   @change="search.trim() && reload()">
          <el-option v-for="f in SEARCH_FIELDS" :key="f.value" :label="f.label" :value="f.value" />
        </el-select>
        <el-input v-model="search" placeholder="输入关键字（名称 / id / 错误码 ...）"
                  clearable size="small" style="width: 280px"
                  @input="onSearchInput" @clear="reload" />
      </div>
    </div>

    <div class="filters">
      <span class="muted-inline">最近</span>
      <el-input-number v-model="rangeValue" :min="1" :max="3650" size="small"
                       style="width: 100px" @change="reload" />
      <el-select v-model="rangeUnit" size="small" style="width: 80px" @change="reload">
        <el-option v-for="u in UNITS" :key="u.value" :label="u.label" :value="u.value" />
      </el-select>
      <span class="muted-inline"><code class="mono">{{ fromDate }}</code> ~ <code class="mono">{{ toDate }}</code></span>
      <el-button @click="reload" size="small" type="primary" :loading="loading">查询</el-button>
    </div>

    <div v-if="stats" class="stats-row">
      <span>区间：<code class="mono">{{ stats.from }}</code> ~ <code class="mono">{{ stats.to }}</code></span>
      <el-divider direction="vertical" />
      <span>扫描 {{ stats.filesScanned }} 个文件</span>
      <el-divider direction="vertical" v-if="stats.parseFailed > 0" />
      <span v-if="stats.parseFailed > 0" class="warn">解析失败 {{ stats.parseFailed }}</span>
      <el-divider direction="vertical" />
      <span>原始 {{ stats.rawLines }}</span>
      <el-divider direction="vertical" />
      <span>匹配 {{ stats.matched }}</span>
      <el-divider direction="vertical" />
      <span>可见 {{ stats.visible }}</span>
      <template v-if="search.trim()">
        <el-divider direction="vertical" />
        <span>搜索：
          <el-tag size="small">{{ SEARCH_FIELDS.find(f => f.value === searchField)?.label }}</el-tag>
          = <code class="mono">{{ search }}</code>
        </span>
      </template>
    </div>

    <el-alert v-if="lastError" type="error" :closable="false" :title="`查询失败：${lastError}`"
              style="margin-bottom: 12px" />

    <el-alert v-else-if="!loading && rows.length === 0 && emptyHint" type="info" :closable="false"
              show-icon :title="emptyHint" style="margin-bottom: 12px" />

    <el-table :data="rows" empty-text="本次查询无结果" max-height="640" stripe v-loading="loading">
      <el-table-column type="expand">
        <template #default="{ row }">
          <pre class="detail">{{ JSON.stringify(detailFields(row), null, 2) }}</pre>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.ts) }}</template>
      </el-table-column>
      <el-table-column label="事件" width="240">
        <template #default="{ row }">
          <el-tag :type="tagType(row.type)" size="small">{{ row.type }}</el-tag>
          <el-tag v-if="row.channel" size="small" effect="plain" style="margin-left: 4px">
            {{ row.channel }}
          </el-tag>
          <div v-if="row.code" class="reject-line">
            <code class="mono">{{ row.code }}</code>
            <span v-if="row.message" :title="row.message"> · {{ row.message }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="Topic / 资源" width="260">
        <template #default="{ row }">
          <div v-if="row._topic">
            <span v-if="row._namespace" class="muted-inline">
              <span class="name-pill" :title="`点击按命名空间搜索  ${row._namespace.id}`"
                    @click="searchBy('namespace', row._namespace.id)">{{ row._namespace.name }}</span>
              <span> / </span>
            </span>
            <span class="name-pill" :title="`点击按 Topic 搜索  ${row._topic.id}`"
                  @click="searchBy('topic', row._topic.id)">{{ row._topic.name }}</span>
            <div class="id-line muted-inline">{{ row._topic.id }}</div>
          </div>
          <div v-else-if="row._namespace">
            <span class="name-pill" :title="`点击按命名空间搜索  ${row._namespace.id}`"
                  @click="searchBy('namespace', row._namespace.id)">{{ row._namespace.name }}</span>
            <div class="id-line muted-inline">{{ row._namespace.id }}</div>
          </div>
          <div v-else-if="row._actor">
            <span class="name-pill" :title="`点击按操作者搜索  ${row._actor.id}`"
                  @click="searchBy('actor', row._actor.id)">@{{ row._actor.name }}</span>
            <div class="id-line muted-inline">{{ row._actor.id }}</div>
          </div>
          <code v-else-if="row.topicId" class="mono"
                @click="searchBy('topic', row.topicId)">{{ row.topicId }}</code>
          <code v-else-if="row.actor" class="mono"
                @click="searchBy('actor', row.actor)">{{ row.actor }}</code>
        </template>
      </el-table-column>
      <el-table-column label="ApiKey" width="200">
        <template #default="{ row }">
          <div v-if="row._apiKey">
            <span class="name-pill" :title="`点击按 ApiKey 搜索  ${row._apiKey.id}`"
                  @click="searchBy('apikey', row._apiKey.id)">{{ row._apiKey.name || '(未命名)' }}</span>
            <div class="id-line muted-inline">{{ row._apiKey.prefix }}••••</div>
          </div>
          <code v-else-if="row.apiKeyId" class="mono"
                @click="searchBy('apikey', row.apiKeyId)">{{ row.apiKeyId }}</code>
        </template>
      </el-table-column>
      <el-table-column label="Payload" width="110" align="center">
        <template #default="{ row }">
          <el-button v-if="row.deliveryId" size="small" link type="primary" @click="openDelivery(row)">
            查看
          </el-button>
          <span v-else class="muted-inline">-</span>
        </template>
      </el-table-column>
      <el-table-column label="Trace / IP">
        <template #default="{ row }">
          <code v-if="row.traceId" class="mono"
                @click="searchBy('trace', row.traceId)">{{ row.traceId }}</code>
          <div v-if="row.remoteIp" class="muted-inline ip-line"
               :title="'点击按 IP 搜索'"
               @click="searchBy('ip', row.remoteIp)">
            {{ row.remoteIp }}
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div class="paginator">
      <el-pagination
        background
        :current-page="page + 1"
        :page-size="size"
        :total="totalCount"
        :page-sizes="[20, 50, 100, 200]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="(p: number) => { page = p - 1; load() }"
        @size-change="(s: number) => { size = s; reload() }" />
    </div>

    <el-dialog v-model="deliveryDialogVisible" title="投递详情 / Payload" width="760px">
      <div v-loading="deliveryLoading">
        <template v-if="deliveryDetail">
          <div class="delivery-meta">
            <div><span>投递 ID</span><code>{{ deliveryDetail.id }}</code></div>
            <div><span>状态</span><el-tag size="small">{{ deliveryDetail.status }}</el-tag></div>
            <div><span>通道</span><code>{{ deliveryDetail.channel }}</code></div>
            <div><span>尝试次数</span><code>{{ deliveryDetail.attempt ?? 0 }}</code></div>
            <div><span>Trace</span><code>{{ deliveryDetail.traceId || '-' }}</code></div>
            <div v-if="deliveryDetail.status === 'RETRY_WAIT' && deliveryDetail.nextRetryAt"><span>下次重试</span><code>{{ countdownText || formatDateTime(deliveryDetail.nextRetryAt) }}</code></div>
          </div>
          <el-alert type="info" :closable="false" show-icon
                    title="如果当前账号没有完整 Payload 查看权限，这里会显示后端返回的脱敏内容。"
                    style="margin: 12px 0" />
          <div v-if="deliveryDetail.lastError" class="delivery-error">
            <strong>最近错误：</strong>{{ deliveryDetail.lastError }}
          </div>
          <pre class="payload-box">{{ prettyPayload(deliveryDetail.payload) }}</pre>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 16px; }
.page-h { color: var(--la-fg); margin: 0; }
.filters { display: flex; gap: 12px; margin-bottom: 12px; align-items: center; flex-wrap: wrap; }
.stats-row { display: flex; align-items: center; gap: 4px; padding: 8px 12px; margin-bottom: 12px;
             background: var(--la-bg-elevated); border: 1px solid var(--la-border);
             border-radius: 6px; font-size: 12px; color: var(--la-fg-muted); }
.warn { color: #f59e0b; }
.search-group { display: flex; align-items: center; }
.search-group :deep(.el-select .el-input__wrapper) {
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
  box-shadow: 0 0 0 1px var(--la-border);
}
.search-group :deep(.el-input .el-input__wrapper) {
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
}
.muted-inline { color: var(--la-fg-muted); font-size: 12px; }
.ip-line { margin-top: 2px; cursor: pointer; }
.ip-line:hover { color: var(--la-accent); }
.mono { font-family: ui-monospace, monospace; font-size: 12px; color: var(--la-fg);
        cursor: pointer; }
.mono:hover { color: var(--la-accent); }
.detail { background: var(--la-bg); border: 1px solid var(--la-border); border-radius: 4px;
          padding: 8px 12px; margin: 0; font-size: 12px; color: var(--la-fg); overflow: auto; }
.reject-line { font-size: 11px; color: var(--la-fg-muted); margin-top: 4px;
               max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.name-pill { color: var(--la-fg); font-weight: 500; cursor: pointer; }
.name-pill:hover { color: var(--la-accent); text-decoration: underline; }
.id-line { font-family: ui-monospace, monospace; font-size: 11px; margin-top: 2px;
           white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 240px; }
.paginator { display: flex; justify-content: flex-end; margin-top: 12px; }
.delivery-meta { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; font-size: 12px; }
.delivery-meta span { color: var(--la-fg-muted); margin-right: 8px; }
.delivery-error { color: #ef4444; font-size: 12px; margin-bottom: 12px; }
.payload-box { max-height: 360px; overflow: auto; margin: 0; padding: 12px;
  border: 1px solid var(--la-border); border-radius: 6px; background: var(--la-bg);
  color: var(--la-fg); font-size: 12px; white-space: pre-wrap; word-break: break-word; }
</style>
