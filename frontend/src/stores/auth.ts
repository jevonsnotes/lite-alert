import { defineStore } from 'pinia'
import { post, get } from '@/http'
import { md5 } from '@/utils/md5'

export type UserProfile = {
  id: string
  username: string
  role: 'ADMIN' | 'USER'
  permissions?: string[]
  roleIds?: string[]
  enabled: boolean
  createdAt?: string
}

const STORAGE_KEY = 'lite-alert.auth'

type Snapshot = {
  token: string
  expiresAt: string
  user: UserProfile
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: '' as string,
    expiresAt: '' as string,
    user: null as UserProfile | null
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isAdmin: (s) => s.user?.role === 'ADMIN',
    hasPermission: (s) => (permission: string) => s.user?.role === 'ADMIN' || (s.user?.permissions ?? []).includes(permission)
  },
  actions: {
    hydrate() {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (!raw) return
      try {
        const snap = JSON.parse(raw) as Snapshot
        if (new Date(snap.expiresAt).getTime() > Date.now()) {
          this.token = snap.token
          this.expiresAt = snap.expiresAt
          this.user = snap.user
        } else {
          localStorage.removeItem(STORAGE_KEY)
        }
      } catch {
        localStorage.removeItem(STORAGE_KEY)
      }
    },
    async login(username: string, password: string) {
      const res = await post<Snapshot>('/auth/login', { username, password: md5(password) })
      this.token = res.token
      this.expiresAt = res.expiresAt
      this.user = res.user
      localStorage.setItem(STORAGE_KEY, JSON.stringify(res))
    },
    async refreshMe() {
      if (!this.token) return
      const me = await get<UserProfile>('/auth/me')
      this.user = me
      const snap: Snapshot = { token: this.token, expiresAt: this.expiresAt, user: me }
      localStorage.setItem(STORAGE_KEY, JSON.stringify(snap))
    },
    logout() {
      this.token = ''
      this.expiresAt = ''
      this.user = null
      localStorage.removeItem(STORAGE_KEY)
    }
  }
})
