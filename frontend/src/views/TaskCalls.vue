<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get } from '@/http'
import { formatDateTime } from '@/utils/datetime'

type CallStatus = 'SUCCESS' | 'FAIL'
type Call = {
  id: string
  taskId: string
  triggeredAt?: string
  protocol?: string
  method?: string
  url?: string
  tcpTarget?: string
  tcpOk?: boolean
  httpStatus?: number
  durationMs?: number
  status: CallStatus
  assertionPassed?: boolean
  errorMessage?: string
  responseExcerpt?: string
}

const tasks = ref<{ id: string; name: string }[]>([])
const selectedTaskId = ref('')   // '' = all visible tasks
const successFilter = ref<'' | 'true' | 'false'>('')
const items = ref<Call[]>([])
const total = ref(0)
const successCount = ref(0)
const page = ref(1)
const pageSize = ref(20)
const detail = ref<Call | null>(null)
const drawerVisible = ref(false)

async function loadTasks() {
  tasks.value = await get<{ id: string; name: string }[]>('/scheduler/tasks')
}

async function loadCalls() {
  const params: Record<string, string | number> = { page: page.value, size: pageSize.value }
  if (successFilter.value) params.success = successFilter.value
  if (selectedTaskId.value) params.taskId = selectedTaskId.value
  const res = await get<{ items: Call[]; total: number; successCount: number; page: number; size: number }>(
    `/scheduler/calls`, { params }
  )
  items.value = res.items
  total.value = res.total
  successCount.value = res.successCount
}

function resetAndLoad() { page.value = 1; return loadCalls() }
function onPageChange(p: number) { page.value = p; loadCalls() }
function onSizeChange(s: number) { pageSize.value = s; page.value = 1; loadCalls() }

async function reload() {
  if (!tasks.value.length) await loadTasks()
  await loadCalls()
}
onMounted(reload)

function openDetail(c: Call) { detail.value = c; drawerVisible.value = true }
function closeDetail() { drawerVisible.value = false; detail.value = null }
function statusType(s: CallStatus) { return s === 'SUCCESS' ? 'success' : 'danger' }
function httpStatusType(code: number) {
  if (code >= 200 && code < 300) return 'success'
  if (code >= 400) return 'danger'
  return 'info'
}
function taskName(taskId: string) { return tasks.value.find(t => t.id === taskId)?.name ?? taskId }
function isTcp(row: any) { return row?.protocol === 'TCP' }
function protocolLabel(row: any) { return row?.protocol === 'TCP' ? 'TCP' : (row?.protocol || 'API') }
</script>

<template>
  <div>
    <div class="header">
      <h2 class="page-h">任务日志</h2>
      <div class="actions">
        <el-select v-model="selectedTaskId" filterable clearable placeholder="选择任务" style="width: 280px" @change="resetAndLoad">
          <el-option label="全部任务" value="" />
          <el-option v-for="t in tasks" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <el-select v-model="successFilter" placeholder="全部" style="width: 140px" @change="resetAndLoad">
          <el-option label="全部" value="" />
          <el-option label="成功" value="true" />
          <el-option label="失败" value="false" />
        </el-select>
        <el-button @click="loadCalls">刷新</el-button>
      </div>
    </div>

    <div class="summary">
      <el-tag type="info">总调用 {{ total }}</el-tag>
      <el-tag type="success">成功 {{ successCount }}</el-tag>
      <el-tag type="danger">失败 {{ total - successCount }}</el-tag>
      <el-tag>成功率 {{ total ? ((successCount / total) * 100).toFixed(1) : '0.0' }}%</el-tag>
    </div>

    <el-table :data="items" empty-text="暂无调用记录" @row-click="openDetail">
      <el-table-column v-if="!selectedTaskId" label="任务" width="160">
        <template #default="{ row }">{{ taskName(row.taskId) }}</template>
      </el-table-column>
      <el-table-column label="触发时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.triggeredAt) }}</template>
      </el-table-column>
      <el-table-column label="协议" width="80">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="isTcp(row) ? 'warning' : 'info'">{{ protocolLabel(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="目标" min-width="220">
        <template #default="{ row }">
          <span v-if="isTcp(row)" class="mono">{{ row.tcpTarget }}</span>
          <span v-else class="mono">{{ row.method }} {{ row.url }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态码/连接" width="120">
        <template #default="{ row }">
          <template v-if="isTcp(row)">
            <el-tag v-if="row.tcpOk === true" type="success" size="small" effect="plain">已连接</el-tag>
            <el-tag v-else-if="row.tcpOk === false" type="danger" size="small" effect="plain">未连接</el-tag>
            <span v-else class="muted">—</span>
          </template>
          <template v-else>
            <span v-if="row.httpStatus === null || row.httpStatus === undefined" class="muted">无响应</span>
            <el-tag v-else :type="httpStatusType(row.httpStatus)" size="small" effect="plain">{{ row.httpStatus }}</el-tag>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="100">
        <template #default="{ row }">{{ row.durationMs }} ms</template>
      </el-table-column>
      <el-table-column label="断言" width="90">
        <template #default="{ row }">
          <span v-if="row.assertionPassed === null || row.assertionPassed === undefined" class="muted">—</span>
          <el-tag v-else :type="row.assertionPassed ? 'success' : 'danger'" size="small">
            {{ row.assertionPassed ? '通过' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ row.status === 'SUCCESS' ? '成功' : '失败' }}</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>

    <el-drawer v-model="drawerVisible" title="调用详情" size="560px" :before-close="closeDetail">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="触发时间">{{ formatDateTime(detail.triggeredAt) }}</el-descriptions-item>
          <el-descriptions-item label="协议">{{ protocolLabel(detail) }}</el-descriptions-item>
          <el-descriptions-item v-if="isTcp(detail)" label="TCP 目标">{{ detail.tcpTarget }}</el-descriptions-item>
          <el-descriptions-item v-else label="请求">{{ detail.method }} {{ detail.url }}</el-descriptions-item>
          <el-descriptions-item v-if="isTcp(detail)" label="TCP 连接">
            <el-tag v-if="detail.tcpOk === true" type="success" size="small">已连接</el-tag>
            <el-tag v-else-if="detail.tcpOk === false" type="danger" size="small">未连接</el-tag>
            <span v-else class="muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item v-else label="HTTP 状态码">
            <span v-if="detail.httpStatus === null || detail.httpStatus === undefined" class="muted">无响应（超时或连接失败）</span>
            <el-tag v-else :type="httpStatusType(detail.httpStatus)" size="small" effect="plain">{{ detail.httpStatus }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="耗时">{{ detail.durationMs }} ms</el-descriptions-item>
          <el-descriptions-item v-if="!isTcp(detail)" label="断言">
            <span v-if="detail.assertionPassed === null || detail.assertionPassed === undefined">无断言（按状态码判定）</span>
            <el-tag v-else :type="detail.assertionPassed ? 'success' : 'danger'" size="small">
              {{ detail.assertionPassed ? '通过' : '失败' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="结果">
            <el-tag :type="statusType(detail.status)" size="small">{{ detail.status === 'SUCCESS' ? '成功' : '失败' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.errorMessage" label="错误信息">
            <span class="error-text">{{ detail.errorMessage }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.responseExcerpt" :label="isTcp(detail) ? '执行摘要' : '响应体摘要'">
            <pre class="excerpt">{{ detail.responseExcerpt }}</pre>
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.header { display:flex; justify-content: space-between; align-items: center; margin-bottom: 16px; gap: 12px; }
.actions { display:flex; align-items: center; gap: 8px; }
.page-h { color: var(--la-fg); margin: 0; }
.muted { color: var(--la-fg-muted); }
.mono { font-family: ui-monospace, monospace; font-size: 12px; }
.summary { display: flex; gap: 8px; margin-bottom: 16px; }
.pager { margin-top: 16px; display: flex; justify-content: flex-end; }
.error-text { color: var(--el-color-danger); word-break: break-all; }
.excerpt { background: var(--la-bg); border: 1px solid var(--la-border); padding: 12px; border-radius: 6px;
           white-space: pre-wrap; word-break: break-all; max-height: 320px; overflow: auto; font-size: 12px; }
</style>
