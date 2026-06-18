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

const router = useRouter()

const namespaces = ref<Namespace[]>([])
const expanded = ref<Record<string, Topic[]>>({})

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

async function ensureExpanded(row: Namespace) {
  if (expanded.value[row.id]) return
  expanded.value[row.id] = await get<Topic[]>('/topics', { params: { namespaceId: row.id } })
}

/** Refresh just one namespace's topics — cheaper than a full reload after
 *  a publish/disable click, and avoids collapsing the expand. */
async function refreshTopics(nsId: string) {
  expanded.value[nsId] = await get<Topic[]>('/topics', { params: { namespaceId: nsId } })
}

async function publishTopic(t: Topic, ns: Namespace) {
  // backend will reject NONE-mode publish without confirmation in some
  // workflows; the dedicated Topic detail page surfaces the whole confirm
  // dialog. Here we just do the simple case + tell the user to go deeper
  // for advanced cases.
  await post(`/topics/${t.id}/publish`)
  ElMessage.success(`已发布：${t.name}`)
  await refreshTopics(ns.id)
}
async function disableTopic(t: Topic, ns: Namespace) {
  await ElMessageBox.confirm(
    `禁用「${t.name}」后，该 Topic 的 webhook 调用将被拒绝（423）。确定？`,
    '禁用 Topic', { type: 'warning' }
  )
  await post(`/topics/${t.id}/disable`)
  ElMessage.success(`已禁用：${t.name}`)
  await refreshTopics(ns.id)
}
async function enableTopic(t: Topic, ns: Namespace) {
  await post(`/topics/${t.id}/enable`)
  ElMessage.success(`已恢复：${t.name}`)
  await refreshTopics(ns.id)
}
async function deleteTopic(t: Topic, ns: Namespace) {
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

async function newTopic(ns: Namespace) {
  router.push({
    name: 'topic-detail',
    params: { id: '__new__' },
    query: { namespaceId: ns.id }
  })
}

async function disableNamespace(ns: Namespace) {
  await ElMessageBox.confirm(
    `禁用命名空间「${ns.name}」后，该空间下所有 Topic 的 Webhook 调用都会被拒绝，但 Topic 状态不会改变。确定继续？`,
    '禁用命名空间',
    { type: 'warning' }
  )
  await post(`/namespaces/${ns.id}/disable`)
  ElMessage.success('已禁用')
  await loadAll()
}

async function enableNamespace(ns: Namespace) {
  await post(`/namespaces/${ns.id}/enable`)
  ElMessage.success('已恢复')
  await loadAll()
}

async function removeNamespace(ns: Namespace) {
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

    <el-table :data="filtered" @expand-change="(row, exp) => exp.length && ensureExpanded(row)">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="topic-list">
            <div class="topic-list-header">
              <span>Topic 列表（{{ topicsFor(row.id).length }}）</span>
              <el-button size="small" type="primary" link @click="newTopic(row)">+ 新建 Topic</el-button>
            </div>
            <el-table :data="topicsFor(row.id)" size="small" empty-text="无匹配 Topic">
              <el-table-column prop="name" label="名称" />
              <el-table-column label="ID" width="240">
                <template #default="{ row: t }">
                  <code class="mono">{{ t.id }}</code>
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="120">
                <template #default="{ row: t }">
                  <el-tag :type="t.status === 'PUBLISHED' ? 'success' : t.status === 'DISABLED' ? 'danger' : 'info'">
                    {{ t.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="创建时间" width="180">
                <template #default="{ row: t }">{{ formatDateTime(t.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="240">
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
      <el-table-column prop="name" label="名称" />
      <el-table-column label="ID" width="240">
        <template #default="{ row }"><code class="mono">{{ row.id }}</code></template>
      </el-table-column>
      <el-table-column prop="description" label="描述" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 'DISABLED' ? 'danger' : 'success'">
            {{ row.status || 'ACTIVE' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Topic" width="90" align="right">
        <template #default="{ row }">{{ topicStats(row.id).total }}</template>
      </el-table-column>
      <el-table-column label="已发布" width="90" align="right">
        <template #default="{ row }">{{ topicStats(row.id).published }}</template>
      </el-table-column>
      <el-table-column label="禁用" width="80" align="right">
        <template #default="{ row }">{{ topicStats(row.id).disabled }}</template>
      </el-table-column>
      <el-table-column label="草稿" width="80" align="right">
        <template #default="{ row }">{{ topicStats(row.id).draft }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220">
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
.header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.header-tools { display: flex; gap: 12px; align-items: center; }
.page-h { color: var(--la-fg); margin: 0; }
.topic-list { padding: 8px 24px; }
.topic-list-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; color: var(--la-fg-muted); }
.mono { font-family: ui-monospace, monospace; font-size: 12px; color: var(--la-fg-muted); }
</style>
