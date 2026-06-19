<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import {
  Bell,
  Folder,
  Key,
  Message,
  User,
  Setting,
  DataAnalysis,
  Promotion,
  Moon,
  Sunny
} from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const theme = useThemeStore()

const activeMenu = computed(() => {
  if (route.name === 'topic-detail') return '/namespaces'
  return route.path
})

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="app-shell">
    <el-aside width="220px" class="aside">
      <div class="brand" @click="router.push('/')">
        <el-icon class="brand-icon"><Bell /></el-icon>
        <span>Lite-Alert</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="transparent"
        :text-color="theme.mode === 'dark' ? '#cdd6f4' : '#1f2937'"
        active-text-color="#3d7cff"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <template #title>仪表盘</template>
        </el-menu-item>
        <el-menu-item index="/namespaces">
          <el-icon><Folder /></el-icon>
          <template #title>命名空间</template>
        </el-menu-item>
        <el-menu-item index="/apikeys">
          <el-icon><Key /></el-icon>
          <template #title>ApiKey</template>
        </el-menu-item>
        <el-menu-item index="/contacts">
          <el-icon><Message /></el-icon>
          <template #title>通知目标</template>
        </el-menu-item>
        <el-menu-item index="/audit">
          <el-icon><Promotion /></el-icon>
          <template #title>调用记录</template>
        </el-menu-item>
        <template v-if="auth.isAdmin">
          <el-menu-item v-if="auth.hasPermission('USER_VIEW')" index="/admin/users">
            <el-icon><User /></el-icon>
            <template #title>用户管理</template>
          </el-menu-item>
          <el-menu-item v-if="auth.hasPermission('ROLE_VIEW')" index="/admin/roles">
            <el-icon><Key /></el-icon>
            <template #title>角色管理</template>
          </el-menu-item>
          <el-menu-item v-if="auth.hasPermission('SYSTEM_SETTINGS_VIEW')" index="/admin/system">
            <el-icon><Setting /></el-icon>
            <template #title>系统设置</template>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container class="content-shell">
      <el-header class="header">
        <span class="page-title">{{ route.meta.title ?? '' }}</span>
        <div class="user-pill">
          <el-tooltip :content="theme.mode === 'dark' ? '切换到亮色' : '切换到暗色'" placement="bottom">
            <el-button text @click="theme.toggle()" :icon="theme.mode === 'dark' ? Sunny : Moon" circle />
          </el-tooltip>
          <span class="username">{{ auth.user?.username }}</span>
          <el-tag size="small" :type="auth.isAdmin ? 'danger' : 'info'">
            {{ auth.user?.role }}
          </el-tag>
          <el-button text type="info" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-shell { min-height: 100vh; background: var(--la-bg); color: var(--la-fg); }
.aside {
  background: var(--la-bg-aside);
  border-right: 1px solid var(--la-border);
  padding: 0;
}
.brand {
  display: flex; align-items: center; gap: 10px;
  padding: 18px 20px;
  cursor: pointer;
  font-weight: 600;
  font-size: 18px;
  color: #3d7cff;
  border-bottom: 1px solid var(--la-border);
}
.brand-icon { font-size: 22px; }
.header {
  background: var(--la-bg-aside);
  border-bottom: 1px solid var(--la-border);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px;
}
.page-title { color: var(--la-fg-muted); font-size: 13px; }
.user-pill { display: flex; align-items: center; gap: 12px; }
.username { color: var(--la-fg); font-size: 13px; }
.content-shell { min-width: 0; }
.main { padding: 24px; background: var(--la-bg); min-width: 0; overflow-x: hidden; }
:deep(.el-menu) { border-right: 0 !important; }
:deep(.el-menu-item:hover) { background: var(--la-accent-soft) !important; }
:deep(.el-menu-item.is-active) { background: var(--la-accent-soft) !important; }
</style>
