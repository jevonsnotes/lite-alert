<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, del } from '@/http'
import { formatDateTime } from '@/utils/datetime'

type Namespace = {
  id: string
  name: string
  ownerId: string
  status?: 'ACTIVE' | 'DISABLED'
  description?: string
  createdAt?: string
}

type Topic = { id: string; name: string; status: string; namespaceId: string; createdAt?: string }

type TopicStats = {
  accepted: number
  sent: number
  failed: number
  pending: number
  retryWait: number
}

type NamespaceStats = {
  topicCount: number
  accepted: number
  sent: number
  failed: number
  pending: number
  retryWait: number
}

const router = useRouter()

const namespaces = ref<Namespace[]>([])
const expanded = ref<Record<string, Topic[]>>({})
const nsStats = ref<Record<string, NamespaceStats>>({})
const tStats = ref<Record<string, TopicStats>>({})

const search = ref('')
const dialogVisible = ref(false)
const newNs = ref({ name: '', description: '' })

async function loadAll() {
  namespaces.value = await get<Namespace[]>('/namespaces')
  // proactively load topics for every namespace so the search can match topic
  // names too. Cheap: namespaces are typically a handful per user.
  await Promise.all(
    namespaces.value.map(async ns => {
      if (!expanded.value[ns.id]) {
        try { expanded.value[ns.id] = await get<Topic[]>('/topics', { params: { namespaceId: ns.id } }) }
        catch { /* ignore — handled when user expands manually */ }
      }
    })
  )
  // load namespace stats
  const allNs = namespaces.value
  await Promise.all(
    allNs.map(async ns => {
      try { nsStats.value[ns.id] = await get<NamespaceStats>('/admin/stats/namespace', { params: { namespaceId: ns.id } }) }
      catch { nsStats.value[ns.id] = { topicCount: 0, accepted: 0, sent: 0, failed: 0, pending: 0, retryWait: 0 } }
    })
  )
  // load per-topic stats
  const allTopics = Object.values(expanded.value).flat()
  if (allTopics.length > 0) {
    const ids = allTopics.map(t => t.id).join(',')
    try {
      const resp = await get<Record<string, TopicStats>>('/admin/stats/topic', { params: { topicId: ids } })
      for (const [tid, s] of Object.entries(resp)) tStats.value[tid] = s
    } catch {}
  }
}
onMounted(loadAll)

/** Filter namespaces (and their topic preview) by the search box. */
const filtered = computed(() => {
  const needle = search.value.trim().toLowerCase()
  if (!needle) return namespaces.value
  return namespaces.value.filter(ns => {
    if (matchNamespace(ns, needle)) return true
    // also match if any of the namespace's topics matches
    return (expanded.value[ns.id] ?? []).some(t => matchTopic(t, needle))
  })
})

function matchNamespace(ns: Namespace, needle: string) {
  return ns.name.toLowerCase().includes(needle)
      || ns.id.toLowerCase().includes(needle)
      || (ns.description ?? '').toLowerCase().includes(needle)
}
function matchTopic(t: Topic, needle: string) {
  return t.name.toLowerCase().includes(needle)
      || t.id.toLowerCase().includes(needle)
      || t.status.toLowerCase().includes(needle)
}

/** Topics shown inside an expanded namespace row, after applying the search. */
function topicsFor(nsId: string): Topic[] {
  const all = expanded.value[nsId] ?? []
  const needle = search.value.trim().toLowerCase()
  if (!needle) return all
  // when the namespace itself matched the search we still show all its topics;
  // otherwise narrow to matching topics only.
  const ns = namespaces.value.find(n => n.id === nsId)
  if (ns && matchNamespace(ns, needle)) return all
  return all.filter(t => matchTopic(t, needle))
}

function topicStats(nsId: string) {
  const all = expanded.value[nsId] ?? []
  return {
    total: all.length,
    published: all.filter(t => t.status === 'PUBLISHED').length,
    disabled: all.filter(t => t.status === 'DISABLED').length,
    draft: all.filter(t => t.status === 'DRAFT').length
  }
}

async function ensureExpanded(row: any) {
  if (expanded.value[row.id]) return
  expanded.value[row.id] = await get<Topic[]>('/topics', { params: { namespaceId: row.id } })
  // load topic stats for newly expanded topics
  const ids = expanded.value[row.id].map(t => t.id).join(',')
  if (ids) {
    try {
      const resp = await get<Record<string, TopicStats>>('/admin/stats/topic', { params: { topicId: ids } })
      for (const [tid, s] of Object.entries(resp)) tStats.value[tid] = s
    } catch {}
  }
}

function onExpandChange(row: any, expandedRows: any) {
  if (Array.isArray(expandedRows) && expandedRows.length) ensureExpanded(row)
}

/** Refresh just one namespace's topics — cheaper than a full reload after
 *  a publish/disable click, and avoids collapsing the expand. */
async function refreshTopics(nsId: string) {
  expanded.value[nsId] = await get<Topic[]>('/topics', { params: { namespaceId: nsId } })
}

async function publishTopic(t: any, ns: any) {
  // backend will reject NONE-mode publish without confirmation in some
  // workflows; the dedicated Topic detail page surfaces the whole confirm
  // dialog. Here we just do the simple case + tell the user to go deeper
  // for advanced cases.
  await post(`/topics/${t.id}/publish`)
  ElMessage.success(`已发布：${t.name}`)
  await refreshTopics(ns.id)
}
async function disableTopic(t: any, ns: any) {
  await ElMessageBox.confirm(
    `禁用「${t.name}」后，该 Topic 的 webhook 调用将被拒绝（423）。确定？`,
    '禁用 Topic', { type: 'warning' }
  )
  await post(`/topics/${t.id}/disable`)
  ElMessage.success(`已禁用：${t.name}`)
  await refreshTopics(ns.id)
}
async function enableTopic(t: any, ns: any) {
  await post(`/topics/${t.id}/enable`)
  ElMessage.success(`已恢复：${t.name}`)
  await refreshTopics(ns.id)
}
async function deleteTopic(t: any, ns: any) {
  await ElMessageBox.confirm(
    `删除「${t.name}」？仅 DRAFT/DISABLED 状态可删除。`,
    '删除 Topic', { type: 'warning' }
  )
  await del(`/topics/${t.id}`)
  ElMessage.success(`已删除：${t.name}`)
  await refreshTopics(ns.id)
}

function newNamespace() {
  newNs.value = { name: '', description: '' }
  dialogVisible.value = true
}

async function submitNamespace() {
  await post('/namespaces', newNs.value)
  dialogVisible.value = false
  ElMessage.success('已创建')
  await loadAll()
}

async function newTopic(ns: any) {
  router.push({
    name: 'topic-detail',
    params: { id: '__new__' },
    query: { namespaceId: ns.id }
  })
}

async function disableNamespace(ns: any) {
  await ElMessageBox.confirm(
    `禁用命名空间「${ns.name}」后，该空间下所有 Topic 的 Webhook 调用都会被拒绝，但 Topic 状态不会改变。确定继续？`,
    '禁用命名空间',
    { type: 'warning' }
  )
  await post(`/namespaces/${ns.id}/disable`)
  ElMessage.success('已禁用')
  await loadAll()
}

async function enableNamespace(ns: any) {
  await post(`/namespaces/${ns.id}/enable`)
  ElMessage.success('已恢复')
  await loadAll()
}

async function removeNamespace(ns: any) {
  await ElMessageBox.confirm(
    `确定删除命名空间「${ns.name}」？其下所有 DRAFT/DISABLED Topic 会一并删除。`,
    '危险操作',
    { type: 'warning' }
  )
  await del(`/namespaces/${ns.id}`)
  ElMessage.success('已删除')
  await loadAll()
}
</script>

<template>
  <div>
    <div class="header">
      <h2 class="page-h">命名空间</h2>
      <div class="header-tools">
        <el-input v-model="search" placeholder="搜索 名称 / id / 描述 / 关联 Topic"
                  clearable size="small" style="width: 320px" />
        <el-button type="primary" @click="newNamespace">+ 新建命名空间</el-button>
      </div>
    </div>

    <el-alert v-if="search && filtered.length === 0" type="info" :closable="false"
              :title="`没有命名空间匹配 “${search}”`" style="margin-bottom: 12px" />

    <el-table class="namespace-table" :data="filtered" @expand-change="onExpandChange">
      <el-table-column type="expand" width="44">
        <template #default="{ row }">
          <div class="topic-list">
            <div class="topic-list-header">
              <span>Topic 列表（{{ topicsFor(row.id).length }}）</span>
            </div>
            <el-table :data="topicsFor(row.id)" size="small" empty-text="无匹配 Topic">
              <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
              <el-table-column label="ID" width="180" show-overflow-tooltip>
                <template #default="{ row: t }">
                  <code class="mono ellipsis">{{ t.id }}</code>
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="110">
                <template #default="{ row: t }">
                  <el-tag :type="t.status === 'PUBLISHED' ? 'success' : t.status === 'DISABLED' ? 'danger' : 'info'">
                    {{ t.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="消息统计" width="300">
                <template #default="{ row: t }">
                  <span class="msg-stats">
                    <span class="ms-item" :title="'受理: ' + (tStats[t.id]?.accepted ?? 0)">受 {{ tStats[t.id]?.accepted ?? 0 }}</span>
                    <span class="ms-item" :title="'送达: ' + (tStats[t.id]?.sent ?? 0)">送 {{ tStats[t.id]?.sent ?? 0 }}</span>
                    <span class="ms-item ms-fail" :title="'失败: ' + (tStats[t.id]?.failed ?? 0)">败 {{ tStats[t.id]?.failed ?? 0 }}</span>
                    <span class="ms-item ms-pending" :title="'积压: ' + (tStats[t.id]?.pending ?? 0)">积 {{ tStats[t.id]?.pending ?? 0 }}</span>
                    <span class="ms-item ms-retry" :title="'待重试: ' + (tStats[t.id]?.retryWait ?? 0)">重 {{ tStats[t.id]?.retryWait ?? 0 }}</span>
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="创建时间" width="160">
                <template #default="{ row: t }">{{ formatDateTime(t.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="200">
                <template #default="{ row: t }">
                  <el-button size="small" link type="primary"
                             @click="router.push({ name: 'topic-detail', params: { id: t.id } })">查看</el-button>
                  <el-button v-if="t.status === 'DRAFT'" size="small" link type="success"
                             @click="publishTopic(t, row)">发布</el-button>
                  <el-button v-if="t.status === 'PUBLISHED'" size="small" link type="warning"
                             @click="disableTopic(t, row)">禁用</el-button>
                  <el-button v-if="t.status === 'DISABLED'" size="small" link type="success"
                             @click="enableTopic(t, row)">恢复</el-button>
                  <el-button v-if="t.status !== 'PUBLISHED'" size="small" link type="danger"
                             @click="deleteTopic(t, row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="ID" width="180" show-overflow-tooltip>
        <template #default="{ row }"><code class="mono ellipsis">{{ row.id }}</code></template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag :type="row.status === 'DISABLED' ? 'danger' : 'success'">
            {{ row.status || 'ACTIVE' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Topic 概览" width="150">
        <template #default="{ row }">
          <span class="topic-summary">
            <span>总 {{ topicStats(row.id).total }}</span>
            <span>发 {{ topicStats(row.id).published }}</span>
            <span>禁 {{ topicStats(row.id).disabled }}</span>
            <span>草 {{ topicStats(row.id).draft }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="消息统计" width="320">
        <template #default="{ row }">
          <span class="msg-stats">
            <span class="ms-item" :title="'受理: ' + (nsStats[row.id]?.accepted ?? 0)">受 {{ nsStats[row.id]?.accepted ?? 0 }}</span>
            <span class="ms-item" :title="'送达: ' + (nsStats[row.id]?.sent ?? 0)">送 {{ nsStats[row.id]?.sent ?? 0 }}</span>
            <span class="ms-item ms-fail" :title="'失败: ' + (nsStats[row.id]?.failed ?? 0)">败 {{ nsStats[row.id]?.failed ?? 0 }}</span>
            <span class="ms-item ms-pending" :title="'积压: ' + (nsStats[row.id]?.pending ?? 0)">积 {{ nsStats[row.id]?.pending ?? 0 }}</span>
            <span class="ms-item ms-retry" :title="'待重试: ' + (nsStats[row.id]?.retryWait ?? 0)">重 {{ nsStats[row.id]?.retryWait ?? 0 }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="newTopic(row)">+ Topic</el-button>
          <el-button v-if="row.status !== 'DISABLED'" size="small" link type="warning" @click="disableNamespace(row)">禁用</el-button>
          <el-button v-else size="small" link type="success" @click="enableNamespace(row)">恢复</el-button>
          <el-button size="small" link type="danger" @click="removeNamespace(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新建命名空间" width="480px">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="newNs.name" placeholder="3-32 字符，字母开头，可含数字 / _ / -" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="newNs.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitNamespace">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; gap: 12px; }
.header-tools { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; justify-content: flex-end; }
.page-h { color: var(--la-fg); margin: 0; }
.namespace-table { width: 100%; }
.topic-list { padding: 8px 16px; min-width: 0; }
.topic-list-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; color: var(--la-fg-muted); }
.topic-summary { display: flex; gap: 8px; font-size: 12px; color: var(--la-fg-muted); white-space: nowrap; }
.mono { font-family: ui-monospace, monospace; font-size: 12px; color: var(--la-fg-muted); }
.ellipsis { display: inline-block; max-width: 100%; overflow: hidden; text-overflow: ellipsis; vertical-align: bottom; }
.msg-stats { display: flex; gap: 6px; font-size: 11px; white-space: nowrap; }
.ms-item { color: var(--la-fg-muted); }
.ms-fail { color: #ef4444; }
.ms-pending { color: #f59e0b; }
.ms-retry { color: #a855f7; }
:deep(.el-table__expanded-cell) { padding: 0 !important; }
:deep(.el-button + .el-button) { margin-left: 6px; }
</style>
