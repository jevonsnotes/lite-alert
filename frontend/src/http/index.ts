import axios, { AxiosError, AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

import { useAuthStore } from '@/stores/auth'
import router from '@/router'

const http = axios.create({
  baseURL: '/api',
  timeout: 15_000
})

http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token && !config.url?.startsWith('/auth/login')) {
    config.headers = config.headers ?? {}
    config.headers['Authorization'] = `Bearer ${auth.token}`
  }
  return config
})

http.interceptors.response.use(
  (res) => res.data,
  (err: AxiosError<any>) => {
    const status = err.response?.status
    const body = err.response?.data
    const code = body?.code ?? 'NETWORK_ERROR'
    const message = body?.message ?? err.message ?? 'request failed'

    if (status === 401) {
      const auth = useAuthStore()
      auth.logout()
      if (router.currentRoute.value.name !== 'login') {
        router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
      }
      ElMessage.warning(message)
    } else if (status === 403) {
      ElMessage.error(message || 'permission denied')
    } else if (status && status >= 500) {
      const trace = body?.traceId ? ` (traceId=${body.traceId})` : ''
      ElMessage.error(`${message}${trace}`)
    } else {
      ElMessage.error(message)
    }

    return Promise.reject({ status, code, message, errors: body?.errors ?? [], traceId: body?.traceId })
  }
)

export default http

export type Pageable<T> = {
  items: T[]
  total: number
}

export function get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return http.get(url, config) as unknown as Promise<T>
}
export function post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return http.post(url, data, config) as unknown as Promise<T>
}
export function patch<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return http.patch(url, data, config) as unknown as Promise<T>
}
export function put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return http.put(url, data, config) as unknown as Promise<T>
}
export function del<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return http.delete(url, config) as unknown as Promise<T>
}
