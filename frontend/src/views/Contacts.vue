<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, patch, del } from '@/http'

type ChannelType = 'EMAIL' | 'DINGTALK' | 'FEISHU' | 'WECOM' | 'WEBHOOK'

type Target = {
  id: string
  type: ChannelType
  label: string
  endpoint: string
  hasSecret?: boolean
  enabled: boolean
  createdAt?: string
}

const list = ref<Target[]>([])
const query = ref('')

const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const draft = reactive({
  type: 'EMAIL' as ChannelType,
  label: '',
  endpoint: '',
  secret: '',
  keepSecret: true
})

const TYPES: { value: ChannelType; label: string; tag: 'primary' | 'success' | 'warning' | 'info' | 'danger'; placeholder: string; secret?: string }[] = [
  { value: 'EMAIL', label: '邮件', tag: 'success',
    placeholder: 'user@example.com' },
  { value: 'DINGTALK', label: '钉钉群机器人', tag: 'primary',
    placeholder: 'https://oapi.dingtalk.com/robot/send?access_token=...',
    secret: '机器人「加签」密钥（可选，建议）' },
  { value: 'FEISHU', label: '飞书 / Lark', tag: 'warning',
    placeholder: 'https://open.feishu.cn/open-apis/bot/v2/hook/...' },
  { value: 'WECOM', label: '企业微信群机器人', tag: 'info',
    placeholder: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...' },
  { value: 'WEBHOOK', label: 'Webhook', tag: 'danger',
    placeholder: 'https://your-service.com/webhook',
    secret: 'Authorization Header（可选，例如 Bearer xxx）' }
]

const isEditing = computed(() => !!editingId.value)
const dialogTitle = computed(() => isEditing.value ? '编辑通知目标' : '添加通知目标')
const currentType = computed(() => TYPES.find(t => t.value === draft.type)!)

const filteredList = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return list.value
  return list.value.filter(t => [
    typeLabel(t.type),
    t.type,
    t.label,
    t.endpoint,
    masked(t)
  ].some(v => String(v).toLowerCase().includes(q)))
})

async function load() {
  list.value = await get<Target[]>('/contacts')
}
onMounted(load)

function resetDraft() {
  editingId.value = null
  draft.type = 'EMAIL'
  draft.label = ''
  draft.endpoint = ''
  draft.secret = ''
  draft.keepSecret = true
}

function openCreate() {
  resetDraft()
  dialogVisible.value = true
}

function openEdit(t: any) {
  editingId.value = t.id
  draft.type = t.type
  draft.label = t.label
  draft.endpoint = t.endpoint
  draft.secret = ''
  draft.keepSecret = true
  dialogVisible.value = true
}

async function submit() {
  if (editingId.value) {
    const body: any = {
      label: draft.label,
      endpoint: draft.endpoint
    }
    if (!draft.keepSecret || draft.secret) body.secret = draft.secret
    await patch(`/contacts/${editingId.value}`, body)
    ElMessage.success('已保存')
  } else {
    await post('/contacts', {
      type: draft.type,
      label: draft.label,
      endpoint: draft.endpoint,
      secret: draft.secret || null
    })
    ElMessage.success('已添加')
  }
  dialogVisible.value = false
  await load()
}

async function toggle(c: any) {
  await patch(`/contacts/${c.id}`, { enabled: !c.enabled })
  await load()
}

async function remove(c: any) {
  await ElMessageBox.confirm(`删除「${c.label || c.endpoint}」？`, { type: 'warning' })
  await del(`/contacts/${c.id}`)
  ElMessage.success('已删除')
  await load()
}

function masked(t: any) {
  if (!t.endpoint) return ''
  if (t.type === 'EMAIL') {
    const at = t.endpoint.indexOf('@')
    if (at <= 1) return t.endpoint
    return t.endpoint[0] + '***' + t.endpoint.substring(at - 1)
  }
  try {
    const url = new URL(t.endpoint)
    const tail = t.endpoint.slice(-6)
    return url.host + url.pathname.split('/')[1] + ' ... ' + tail
  } catch {
    return t.endpoint.slice(0, 24) + '...'
  }
}

function tagType(type: ChannelType) {
  return TYPES.find(t => t.value === type)?.tag ?? 'info'
}
function typeLabel(type: ChannelType) {
  return TYPES.find(t => t.value === type)?.label ?? type
}
</script>

<template>
  <div>
    <div class="header">
      <h2 class="page-h">通知目标</h2>
      <div class="actions">
        <el-input v-model="query" clearable placeholder="搜索类型 / 名称 / 地址" style="width: 260px" />
        <el-button type="primary" @click="openCreate">+ 添加目标</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" style="margin-bottom: 12px">
      支持邮件 / 钉钉 / 飞书 / 企业微信群机器人。每个目标都可在 Topic「订阅」中勾选。
    </el-alert>

    <el-table :data="filteredList" empty-text="尚未添加任何通知目标">
      <el-table-column label="类型" width="160">
        <template #default="{ row }">
          <el-tag :type="tagType(row.type)" size="small">{{ typeLabel(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="label" label="名称" width="180" />
      <el-table-column label="地址 / Webhook">
        <template #default="{ row }">
          <span :title="row.endpoint">{{ masked(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="加签" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.hasSecret" type="success" size="small">已配置</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled" @change="toggle(row)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="720px">
      <el-form label-width="120px">
        <el-form-item label="类型">
          <el-select v-model="draft.type" :disabled="isEditing" placeholder="请选择通知类型" style="width: 100%">
            <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <div v-if="isEditing" class="form-tip">编辑时类型只读；如需更换类型，请新增一个通知目标。</div>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="draft.label" placeholder="例如：运维群组" />
        </el-form-item>
        <el-form-item :label="draft.type === 'EMAIL' ? '邮箱' : 'Webhook URL'" required>
          <el-input v-model="draft.endpoint" :placeholder="currentType.placeholder" />
        </el-form-item>
        <el-form-item v-if="currentType.secret" label="加签密钥">
          <el-input v-model="draft.secret" type="password" show-password
                    :placeholder="isEditing ? '留空保持原密钥；取消保持可清空或替换' : currentType.secret" />
          <el-checkbox v-if="isEditing" v-model="draft.keepSecret" :disabled="!!draft.secret" style="margin-top: 6px">
            留空时保持原密钥
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header { display:flex; justify-content: space-between; align-items: center; margin-bottom: 16px; gap: 12px; }
.actions { display:flex; align-items: center; gap: 8px; }
.page-h { color: var(--la-fg); margin: 0; }
.muted { color: var(--la-fg-muted); }
.form-tip { color: var(--la-fg-muted); font-size: 12px; line-height: 1.4; margin-top: 6px; }
</style>
