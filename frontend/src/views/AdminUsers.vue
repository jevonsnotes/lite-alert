<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, patch, del } from '@/http'
import { formatDateTime } from '@/utils/datetime'
import { md5 } from '@/utils/md5'

type User = { id: string; username: string; role: 'ADMIN' | 'USER'; enabled: boolean; createdAt?: string }

const list = ref<User[]>([])
const dialogVisible = ref(false)
const draft = reactive({ username: '', password: '', role: 'USER' })

const showResetDialog = ref(false)
const resetTarget = ref<User | null>(null)
const newPassword = ref('')

async function load() {
  list.value = await get<User[]>('/users')
}
onMounted(load)

function openCreate() {
  draft.username = ''
  draft.password = ''
  draft.role = 'USER'
  dialogVisible.value = true
}
async function submit() {
  await post('/users', { ...draft, password: md5(draft.password) })
  ElMessage.success('已创建')
  dialogVisible.value = false
  await load()
}

async function toggle(u: User) {
  await patch(`/users/${u.id}`, { enabled: !u.enabled })
  await load()
}

function openReset(u: User) {
  resetTarget.value = u
  newPassword.value = ''
  showResetDialog.value = true
}
async function submitReset() {
  if (!newPassword.value || newPassword.value.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  await patch(`/users/${resetTarget.value!.id}`, { password: md5(newPassword.value) })
  ElMessage.success('已重置')
  showResetDialog.value = false
}

async function remove(u: User) {
  await ElMessageBox.confirm(`删除用户「${u.username}」？`, { type: 'warning' })
  await del(`/users/${u.id}`)
  ElMessage.success('已删除')
  await load()
}
</script>

<template>
  <div>
    <div class="header">
      <h2 class="page-h">用户管理</h2>
      <el-button type="primary" @click="openCreate">+ 新建用户</el-button>
    </div>
    <el-table :data="list">
      <el-table-column prop="username" label="用户名" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'">{{ row.role }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="100">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled" @change="toggle(row)" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openReset(row)">重置密码</el-button>
          <el-button size="small" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新建用户" width="460px">
      <el-form label-width="80px">
        <el-form-item label="用户名" required><el-input v-model="draft.username" /></el-form-item>
        <el-form-item label="密码" required><el-input v-model="draft.password" type="password" show-password /></el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="draft.role">
            <el-radio value="USER">USER</el-radio>
            <el-radio value="ADMIN">ADMIN</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showResetDialog" title="重置密码" width="400px">
      <el-input v-model="newPassword" type="password" show-password placeholder="≥ 6 位" />
      <template #footer>
        <el-button @click="showResetDialog = false">取消</el-button>
        <el-button type="primary" @click="submitReset">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header { display:flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-h { color: var(--la-fg); margin: 0; }
</style>
