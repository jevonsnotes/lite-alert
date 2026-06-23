<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const loading = ref(false)

async function onSubmit() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.push(redirect)
  } catch {
    // interceptor already showed the toast
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-shell">
    <el-card class="login-card" shadow="always">
      <template #header>
        <div class="login-header">
          <h2>Lite-Alert</h2>
          <span>轻量级消息通知服务</span>
        </div>
      </template>
      <el-form @submit.prevent="onSubmit" label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="请输入用户名" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width: 100%" @click="onSubmit">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-shell {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background:
    radial-gradient(1200px 600px at 20% 10%, rgba(61, 124, 255, 0.18), transparent 60%),
    radial-gradient(900px 500px at 80% 90%, rgba(61, 124, 255, 0.10), transparent 60%),
    var(--la-bg);
}
.login-card { width: 380px; background: var(--la-bg-elevated); color: var(--la-fg); border: 1px solid var(--la-border); }
.login-header h2 { margin: 0; color: var(--la-accent); }
.login-header span { color: var(--la-fg-muted); font-size: 13px; }
</style>
