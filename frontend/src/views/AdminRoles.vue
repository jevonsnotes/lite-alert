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
  { label: '系统概览', id: 'g_dashboard', children: [
    { label: '仪表盘_查看（DASHBOARD_VIEW）', id: 'DASHBOARD_VIEW' },
    { label: '全局统计（STATS_VIEW）', id: 'STATS_VIEW' },
    { label: '全局统计（所有）（STATS_VIEW_ALL）', id: 'STATS_VIEW_ALL' }] },
  { label: '命名空间', id: 'g_ns', children: [
    { label: '命名空间_查看（NAMESPACE_VIEW）', id: 'NAMESPACE_VIEW' },
    { label: '命名空间_查看（所有）（NAMESPACE_VIEW_ALL）', id: 'NAMESPACE_VIEW_ALL' },
    { label: '命名空间_创建（NAMESPACE_CREATE）', id: 'NAMESPACE_CREATE' },
    { label: '命名空间_编辑（NAMESPACE_UPDATE）', id: 'NAMESPACE_UPDATE' },
    { label: '命名空间_禁用（NAMESPACE_DISABLE）', id: 'NAMESPACE_DISABLE' },
    { label: '命名空间_删除（NAMESPACE_DELETE）', id: 'NAMESPACE_DELETE' }] },
  { label: 'Topic', id: 'g_topic', children: [
    { label: 'Topic_查看（TOPIC_VIEW）', id: 'TOPIC_VIEW' },
    { label: 'Topic_查看（所有）（TOPIC_VIEW_ALL）', id: 'TOPIC_VIEW_ALL' },
    { label: 'Topic_创建（TOPIC_CREATE）', id: 'TOPIC_CREATE' },
    { label: 'Topic_编辑（TOPIC_UPDATE）', id: 'TOPIC_UPDATE' },
    { label: 'Topic_发布（TOPIC_PUBLISH）', id: 'TOPIC_PUBLISH' },
    { label: 'Topic_禁用（TOPIC_DISABLE）', id: 'TOPIC_DISABLE' },
    { label: 'Topic_删除（TOPIC_DELETE）', id: 'TOPIC_DELETE' }] },
  { label: 'ApiKey', id: 'g_ak', children: [
    { label: 'ApiKey_查看（APIKEY_VIEW）', id: 'APIKEY_VIEW' },
    { label: 'ApiKey_查看（所有）（APIKEY_VIEW_ALL）', id: 'APIKEY_VIEW_ALL' },
    { label: 'ApiKey_创建（APIKEY_CREATE）', id: 'APIKEY_CREATE' },
    { label: 'ApiKey_编辑（APIKEY_UPDATE）', id: 'APIKEY_UPDATE' },
    { label: 'ApiKey_轮换（APIKEY_ROTATE）', id: 'APIKEY_ROTATE' },
    { label: 'ApiKey_删除（APIKEY_DELETE）', id: 'APIKEY_DELETE' }] },
  { label: '通知目标', id: 'g_contact', children: [
    { label: '通知目标_查看（CONTACT_VIEW）', id: 'CONTACT_VIEW' },
    { label: '通知目标_查看（所有）（CONTACT_VIEW_ALL）', id: 'CONTACT_VIEW_ALL' },
    { label: '通知目标_创建（CONTACT_CREATE）', id: 'CONTACT_CREATE' },
    { label: '通知目标_编辑（CONTACT_UPDATE）', id: 'CONTACT_UPDATE' },
    { label: '通知目标_删除（CONTACT_DELETE）', id: 'CONTACT_DELETE' }] },
  { label: '调用记录与投递', id: 'g_audit', children: [
    { label: '审计日志_查看（AUDIT_VIEW）', id: 'AUDIT_VIEW' },
    { label: '审计日志_查看（所有）（AUDIT_VIEW_ALL）', id: 'AUDIT_VIEW_ALL' },
    { label: '投递记录_查看（DELIVERY_VIEW）', id: 'DELIVERY_VIEW' },
    { label: '投递记录_查看报文（DELIVERY_PAYLOAD_READ）', id: 'DELIVERY_PAYLOAD_READ' }] },
  { label: '系统管理', id: 'g_admin', children: [
    { label: '用户_查看（USER_VIEW）', id: 'USER_VIEW' },
    { label: '用户_创建（USER_CREATE）', id: 'USER_CREATE' },
    { label: '用户_编辑（USER_UPDATE）', id: 'USER_UPDATE' },
    { label: '用户_删除（USER_DELETE）', id: 'USER_DELETE' },
    { label: '角色_查看（ROLE_VIEW）', id: 'ROLE_VIEW' },
    { label: '角色_创建（ROLE_CREATE）', id: 'ROLE_CREATE' },
    { label: '角色_编辑（ROLE_UPDATE）', id: 'ROLE_UPDATE' },
    { label: '角色_删除（ROLE_DELETE）', id: 'ROLE_DELETE' },
    { label: '系统_查看健康（SYSTEM_HEALTH_VIEW）', id: 'SYSTEM_HEALTH_VIEW' },
    { label: '系统_查看设置（SYSTEM_SETTINGS_VIEW）', id: 'SYSTEM_SETTINGS_VIEW' },
    { label: '系统_编辑设置（SYSTEM_SETTINGS_UPDATE）', id: 'SYSTEM_SETTINGS_UPDATE' },
    { label: '系统_查看邮件配置（MAIL_CONFIG_VIEW）', id: 'MAIL_CONFIG_VIEW' },
    { label: '系统_编辑邮件配置（MAIL_CONFIG_UPDATE）', id: 'MAIL_CONFIG_UPDATE' },
    { label: '系统_邮件测试（SMTP_TEST）', id: 'SMTP_TEST' }] }
]

function filterPermNode(value: string, data: any) {
  return !value || String(data.label).toLowerCase().includes(value.toLowerCase())
}
watch(permSearch, v => permTreeRef.value?.filter(v))

function onPermCheckChange(_d: any, checked: boolean, indeterminate: boolean) {
  // el-tree check-change; collect keys after state update
  setTimeout(() => { if (permTreeRef.value) permCheckKeys.value = permTreeRef.value.getCheckedKeys().filter((k: string) => !String(k).startsWith('g_')) }, 0)
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
          <el-button size="small" link type="primary" @click="openEdit(row as Role)">编辑</el-button>
          <el-button size="small" link type="danger" :disabled="row.systemBuiltin" @click="remove(row as Role)">删除</el-button>
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
