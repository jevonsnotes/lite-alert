import { defineStore } from 'pinia'

export type ThemeMode = 'dark' | 'light'

const KEY = 'lite-alert.theme'

function applyTheme(mode: ThemeMode) {
  const root = document.documentElement
  if (mode === 'dark') root.classList.add('dark')
  else root.classList.remove('dark')
  root.dataset.theme = mode
}

export const useThemeStore = defineStore('theme', {
  state: () => ({
    mode: 'dark' as ThemeMode
  }),
  actions: {
    hydrate() {
      const saved = (localStorage.getItem(KEY) as ThemeMode | null) ?? 'dark'
      this.mode = saved
      applyTheme(saved)
    },
    toggle() {
      this.mode = this.mode === 'dark' ? 'light' : 'dark'
      localStorage.setItem(KEY, this.mode)
      applyTheme(this.mode)
    },
    set(mode: ThemeMode) {
      if (mode === this.mode) return
      this.mode = mode
      localStorage.setItem(KEY, mode)
      applyTheme(mode)
    }
  }
})
