<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, patch, del } from '@/http'
import { formatDateTime, formatRelative } from '@/utils/datetime'

type Scope = { type: 'TOPIC' | 'NAMESPACE'; id: string }
type ApiKey = {
  id: string
  name: string
  prefix: string
  scopes: Scope[]
  status: 'ACTIVE' | 'REVOKED'
  validFrom?: string
  validUntil?: string
  createdAt?: string
  lastUsedAt?: string
  usageCount?: number
  rotateCount?: number
}

type Namespace = { id: string; name: string }
type Topic = { id: string; name: string; namespaceId: string; namespaceName: string }

const list = ref<ApiKey[]>([])
const namespaces = ref<Namespace[]>([])
const topics = ref<Topic[]>([])
const query = ref('')

const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({
  name: '',
  permanent: true,
  validUntil: '',
  selectedNamespaces: [] as string[],
  selectedTopics: [] as string[]
})

const showFullKeyDialog = ref(false)
const fullKey = ref('')

const isEditing = computed(() => !!editingId.value)
const dialogTitle = computed(() => isEditing.value ? '编辑 ApiKey' : '新建 ApiKey')

async function load() {
  ;[list.value, namespaces.value, topics.value] = await Promise.all([
    get<ApiKey[]>('/apikeys'),
    get<Namespace[]>('/namespaces'),
    get<Topic[]>('/topics')
  ])
}
onMounted(load)

const filteredList = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return list.value
  return list.value.filter(k => [
    k.name,
    k.prefix,
    k.status,
    ...k.scopes.map(scopeLabel)
  ].some(v => String(v).toLowerCase().includes(q)))
})

function resetForm() {
  editingId.value = null
  form.name = ''
  form.permanent = true
  form.validUntil = ''
  form.selectedNamespaces = []
  form.selectedTopics = []
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(k: any) {
  editingId.value = k.id
  form.name = k.name
  form.permanent = !k.validUntil
  form.validUntil = k.validUntil ?? ''
  form.selectedNamespaces = k.scopes.filter((s: Scope) => s.type === 'NAMESPACE').map((s: Scope) => s.id)
  form.selectedTopics = k.scopes.filter((s: Scope) => s.type === 'TOPIC').map((s: Scope) => s.id)
  dialogVisible.value = true
}

function buildScopes() {
  const scopes: Scope[] = []
  for (const id of form.selectedNamespaces) scopes.push({ type: 'NAMESPACE', id })
  for (const id of form.selectedTopics) scopes.push({ type: 'TOPIC', id })
  return scopes
}

function validUntilIso() {
  if (form.permanent || !form.validUntil) return null
  return new Date(form.validUntil).toISOString()
}

async function submitSave() {
  const scopes = buildScopes()
  if (!scopes.length) {
    ElMessage.error('请至少选择一个授权范围')
    return
  }

  if (editingId.value) {
    const body: any = { name: form.name, scopes }
    const until = validUntilIso()
    if (form.permanent) body.clearValidUntil = true
    else if (until) body.validUntil = until
    await patch(`/apikeys/${editingId.value}`, body)
    ElMessage.success('已保存')
  } else {
    const body: any = { name: form.name, scopes }
    const until = validUntilIso()
    if (until) body.validUntil = until
    const res = await post<any>('/apikeys', body)
    fullKey.value = res.fullKey
    showFullKeyDialog.value = true
  }

  dialogVisible.value = false
  await load()
}

async function rotate(k: any) {
  await ElMessageBox.confirm(
    `轮换「${k.name}」？旧 Key 将立即失效，新的完整 Key 只会展示一次，请确认调用方可以及时替换。`,
    '轮换 ApiKey',
    { type: 'warning' }
  )
  const res = await post<any>(`/apikeys/${k.id}/rotate`)
  fullKey.value = res.fullKey
  showFullKeyDialog.value = true
  ElMessage.success('已生成新 Key')
  await load()
}

async function revoke(k: any) {
  await ElMessageBox.confirm(
    `撤销「${k.name}」？所有正在使用此 ApiKey 的调用方将立刻 401，且无法恢复。`,
    '撤销 ApiKey',
    { type: 'warning' }
  )
  await post(`/apikeys/${k.id}/revoke`)
  ElMessage.success('已撤销')
  await load()
}

async function remove(k: any) {
  await ElMessageBox.confirm(`物理删除「${k.name}」？仅对已撤销或已过期的 Key 允许。`, { type: 'warning' })
  await del(`/apikeys/${k.id}`)
  ElMessage.success('已删除')
  await load()
}

function copyFullKey() {
  navigator.clipboard.writeText(fullKey.value).then(() => ElMessage.success('已复制到剪贴板'))
}

function scopeLabel(s: Scope) {
  if (s.type === 'NAMESPACE') {
    const ns = namespaces.value.find(n => n.id === s.id)
    return ns ? `ns:${ns.name}` : `ns:${s.id}`
  }
  const t = topics.value.find(x => x.id === s.id)
  return t ? `${t.namespaceName}/${t.name}` : `topic:${s.id}`
}
</script>

<template>
  <div>
    <div class="header">
      <h2 class="page-h">ApiKey 管理</h2>
      <div class="actions">
        <el-input v-model="query" clearable placeholder="搜索名称 / 前缀 / 授权范围" style="width: 280px" />
        <el-button type="primary" @click="openCreate">+ 新建 ApiKey</el-button>
      </div>
    </div>

    <el-table :data="filteredList" empty-text="尚无 ApiKey">
      <el-table-column prop="name" label="名称" width="180" />
      <el-table-column label="前缀" width="160">
        <template #default="{ row }"><code>{{ row.prefix }}••••</code></template>
      </el-table-column>
      <el-table-column label="授权范围">
        <template #default="{ row }">
          <el-tag v-for="s in row.scopes" :key="s.type + s.id" size="small" style="margin-right: 4px">
            {{ scopeLabel(s) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="有效期" width="220">
        <template #default="{ row }">
          <span v-if="!row.validUntil">永久有效</span>
          <span v-else>至 {{ formatDateTime(row.validUntil) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="usageCount" label="调用次数" width="100" />
      <el-table-column prop="rotateCount" label="轮换次数" width="100" />
      <el-table-column label="最近使用" width="140">
        <template #default="{ row }">
          <span v-if="row.lastUsedAt" :title="formatDateTime(row.lastUsedAt)">
            {{ formatRelative(row.lastUsedAt) }}
          </span>
          <span v-else class="muted">从未</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button size="small" link type="primary" v-if="row.status === 'ACTIVE'" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="warning" v-if="row.status === 'ACTIVE'" @click="rotate(row)">轮换</el-button>
          <el-button size="small" link type="warning" v-if="row.status === 'ACTIVE'" @click="revoke(row)">撤销</el-button>
          <el-button size="small" link type="danger" v-else @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="680px">
      <el-form label-width="100px">
        <el-alert v-if="isEditing" type="info" :closable="false" show-icon style="margin-bottom: 12px">
          完整 Key 不可查看；如已丢失，请使用列表中的「轮换」生成新 Key。
        </el-alert>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="标识用途，例如：订单系统-生产" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-radio-group v-model="form.permanent">
            <el-radio :value="true">永久有效</el-radio>
            <el-radio :value="false">指定失效时间</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="!form.permanent" label="失效时间">
          <el-date-picker v-model="form.validUntil" type="datetime" placeholder="选择" />
        </el-form-item>

        <el-form-item label="按命名空间">
          <el-select v-model="form.selectedNamespaces" multiple filterable placeholder="选中命名空间则覆盖其下全部 Topic">
            <el-option v-for="ns in namespaces" :key="ns.id" :label="ns.name" :value="ns.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="按 Topic">
          <el-select v-model="form.selectedTopics" multiple filterable placeholder="选择具体 Topic">
            <el-option v-for="t in topics" :key="t.id"
                       :label="`${t.namespaceName}/${t.name}`"
                       :value="t.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitSave">{{ isEditing ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showFullKeyDialog" title="保存完整 ApiKey" width="540px" :close-on-click-modal="false" :close-on-press-escape="false">
      <el-alert type="warning" :closable="false" show-icon>
        关闭后将无法再次查看完整值，请立刻复制并妥善保存。
      </el-alert>
      <pre class="key-block">{{ fullKey }}</pre>
      <el-button type="primary" @click="copyFullKey">复制</el-button>
      <template #footer>
        <el-button @click="showFullKeyDialog = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header { display:flex; justify-content: space-between; align-items: center; margin-bottom: 16px; gap: 12px; }
.actions { display:flex; align-items: center; gap: 8px; }
.page-h { color: var(--la-fg); margin: 0; }
.muted { color: var(--la-fg-muted); }
.key-block { background: var(--la-bg); padding: 16px; border-radius: 6px; margin: 12px 0;
             color: var(--la-accent); font-family: ui-monospace, monospace; word-break: break-all;
             border: 1px solid var(--la-border); }
</style>
