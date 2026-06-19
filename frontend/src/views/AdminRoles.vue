<script setup lang="ts">
import { onMounted, reactive, ref, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, patch, del } from '@/http'

type Role = {
  id: string
  name: string
  description?: string
  systemBuiltin: boolean
  permissions: string[]
}

const roles = ref<Role[]>([])
const permissions = ref<string[]>([])
const permSearch = ref('')
const permTreeRef = ref<any>()
const permCheckKeys = ref<string[]>([])
const dialogVisible = ref(false)
const editing = ref<Role | null>(null)
const draft = reactive<{ name: string; description: string; permissions: string[] }>({
  name: '',
  description: '',
  permissions: []
})

const permTreeData = [
  { label: '系统概览', id: 'g_dashboard', children: [{ label: 'DASHBOARD_VIEW', id: 'DASHBOARD_VIEW' }] },
  { label: '命名空间', id: 'g_ns', children: [
    { label: 'NAMESPACE_VIEW', id: 'NAMESPACE_VIEW' }, { label: 'NAMESPACE_CREATE', id: 'NAMESPACE_CREATE' },
    { label: 'NAMESPACE_DISABLE', id: 'NAMESPACE_DISABLE' }, { label: 'NAMESPACE_DELETE', id: 'NAMESPACE_DELETE' }] },
  { label: 'Topic', id: 'g_topic', children: [
    { label: 'TOPIC_VIEW', id: 'TOPIC_VIEW' }, { label: 'TOPIC_CREATE', id: 'TOPIC_CREATE' },
    { label: 'TOPIC_UPDATE', id: 'TOPIC_UPDATE' }, { label: 'TOPIC_PUBLISH', id: 'TOPIC_PUBLISH' },
    { label: 'TOPIC_DISABLE', id: 'TOPIC_DISABLE' }, { label: 'TOPIC_DELETE', id: 'TOPIC_DELETE' }] },
  { label: 'ApiKey', id: 'g_ak', children: [
    { label: 'APIKEY_VIEW', id: 'APIKEY_VIEW' }, { label: 'APIKEY_CREATE', id: 'APIKEY_CREATE' },
    { label: 'APIKEY_UPDATE', id: 'APIKEY_UPDATE' }, { label: 'APIKEY_ROTATE', id: 'APIKEY_ROTATE' },
    { label: 'APIKEY_DELETE', id: 'APIKEY_DELETE' }] },
  { label: '通知目标', id: 'g_contact', children: [
    { label: 'CONTACT_VIEW', id: 'CONTACT_VIEW' }, { label: 'CONTACT_CREATE', id: 'CONTACT_CREATE' },
    { label: 'CONTACT_UPDATE', id: 'CONTACT_UPDATE' }, { label: 'CONTACT_DELETE', id: 'CONTACT_DELETE' }] },
  { label: '调用记录与投递', id: 'g_audit', children: [
    { label: 'AUDIT_VIEW', id: 'AUDIT_VIEW' }, { label: 'DELIVERY_VIEW', id: 'DELIVERY_VIEW' },
    { label: 'DELIVERY_PAYLOAD_READ', id: 'DELIVERY_PAYLOAD_READ' }] },
  { label: '系统管理', id: 'g_admin', children: [
    { label: 'USER_VIEW', id: 'USER_VIEW' }, { label: 'USER_CREATE', id: 'USER_CREATE' },
    { label: 'USER_UPDATE', id: 'USER_UPDATE' }, { label: 'USER_DELETE', id: 'USER_DELETE' },
    { label: 'ROLE_VIEW', id: 'ROLE_VIEW' }, { label: 'ROLE_CREATE', id: 'ROLE_CREATE' },
    { label: 'ROLE_UPDATE', id: 'ROLE_UPDATE' }, { label: 'ROLE_DELETE', id: 'ROLE_DELETE' },
    { label: 'SYSTEM_SETTINGS_VIEW', id: 'SYSTEM_SETTINGS_VIEW' },
    { label: 'SYSTEM_SETTINGS_UPDATE', id: 'SYSTEM_SETTINGS_UPDATE' }] }
]

function filterPermNode(value: string, data: any) {
  return !value || String(data.label).toLowerCase().includes(value.toLowerCase())
}
watch(permSearch, v => permTreeRef.value?.filter(v))

function onPermCheckChange(_d: any, checked: boolean, indeterminate: boolean) {
  // el-tree check-change; collect keys after state update
  setTimeout(() => { if (permTreeRef.value) permCheckKeys.value = permTreeRef.value.getCheckedKeys().filter(k => !String(k).startsWith('g_')) }, 0)
}

async function load() {
  roles.value = await get<Role[]>('/roles')
  permissions.value = await get<string[]>('/roles/permissions')
}

onMounted(load)

function openCreate() {
  editing.value = null
  draft.name = ''
  draft.description = ''
  permCheckKeys.value = []
  dialogVisible.value = true
}

function openEdit(role: Role) {
  editing.value = role
  draft.name = role.name
  draft.description = role.description ?? ''
  const keys = (role.permissions ?? []).map(p => p)
  permCheckKeys.value = []
  dialogVisible.value = true
  nextTick(() => {
    if (permTreeRef.value) permTreeRef.value.setCheckedKeys(keys)
    permCheckKeys.value = keys
  })
}

async function save() {
  if (permTreeRef.value) permCheckKeys.value = permTreeRef.value.getCheckedKeys().filter((k: string) => !String(k).startsWith('g_'))
  const body = { name: draft.name, description: draft.description, permissions: permCheckKeys.value }
  if (editing.value) await patch(`/roles/${editing.value.id}`, body)
  else await post('/roles', body)
  ElMessage.success('已保存')
  dialogVisible.value = false
  await load()
}

async function remove(role: Role) {
  await ElMessageBox.confirm(`删除角色「${role.name}」？`, '删除角色', { type: 'warning' })
  await del(`/roles/${role.id}`)
  ElMessage.success('已删除')
  await load()
}
</script>

<template>
  <div>
    <div class="header">
      <h2 class="page-h">角色管理</h2>
      <el-button type="primary" @click="openCreate">+ 新建角色</el-button>
    </div>

    <el-table :data="roles" stripe>
      <el-table-column prop="name" label="角色" width="180" />
      <el-table-column prop="description" label="描述" show-overflow-tooltip />
      <el-table-column label="内置" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.systemBuiltin" size="small" type="warning">内置</el-tag>
          <span v-else class="muted">否</span>
        </template>
      </el-table-column>
      <el-table-column label="权限数" width="90">
        <template #default="{ row }">{{ row.permissions?.length ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="danger" :disabled="row.systemBuiltin" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑角色' : '新建角色'" width="960px">
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="draft.name" :disabled="!!editing?.systemBuiltin" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="draft.description" :disabled="!!editing?.systemBuiltin" />
        </el-form-item>
        <el-form-item label="权限" class="perm-item">
          <div class="perm-wrapper">
            <el-input v-model="permSearch" placeholder="搜索权限名" clearable size="small" style="margin-bottom: 8px" />
            <el-tree
              ref="permTreeRef"
              :data="permTreeData"
              show-checkbox
              :filter-node-method="filterPermNode"
              :props="{ label: 'label', children: 'children' }"
              node-key="id"
              :default-checked-keys="draft.permissions"
              @check-change="onPermCheckChange"
              style="max-height: 360px; overflow: auto;" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header { display:flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-h { color: var(--la-fg); margin: 0; }
.muted { color: var(--la-fg-muted); font-size: 12px; }
:deep(.el-tree-node__label) { font-size: 13px; }
:deep(.el-form-item.perm-item .el-form-item__content) { display: block; width: 100%; }
.perm-wrapper { border: 1px solid var(--la-border); border-radius: 6px; padding: 8px; }
</style>
