import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { permission: 'DASHBOARD_VIEW' }
      },
      {
        path: 'namespaces',
        name: 'namespaces',
        component: () => import('@/views/Namespaces.vue')
      },
      {
        path: 'topics/:id',
        name: 'topic-detail',
        component: () => import('@/views/TopicDetail.vue'),
        props: true
      },
      {
        path: 'apikeys',
        name: 'apikeys',
        component: () => import('@/views/ApiKeys.vue')
      },
      {
        path: 'contacts',
        name: 'contacts',
        component: () => import('@/views/Contacts.vue')
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/views/Audit.vue')
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/AdminUsers.vue'),
        meta: { permission: 'USER_VIEW' }
      },
      {
        path: 'admin/roles',
        name: 'admin-roles',
        component: () => import('@/views/AdminRoles.vue'),
        meta: { permission: 'ROLE_VIEW' }
      },
      {
        path: 'admin/system',
        name: 'admin-system',
        component: () => import('@/views/AdminSystem.vue'),
        meta: { permission: 'SYSTEM_SETTINGS_VIEW' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!auth.isLoggedIn && !to.meta.public) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.permission && !auth.hasPermission(String(to.meta.permission))) {
    return { name: 'dashboard' }
  }
  return true
})

export default router
