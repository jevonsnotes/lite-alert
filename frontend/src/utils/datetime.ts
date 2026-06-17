/**
 * Lightweight date formatting helpers. We don't pull in dayjs / date-fns
 * because the project only needs three forms across the UI.
 *
 * - {@link formatDateTime}: full timestamp, dropdowns and table cells.
 * - {@link formatDate}:     date only, e.g. for ApiKey expiry.
 * - {@link formatRelative}: "3 分钟前" — for table secondary text.
 *
 * Inputs are accepted as ISO-8601 strings, numbers (epoch ms), or Date.
 * Empty / invalid → returns an em-dash so tables don't show "Invalid Date".
 */

const PLACEHOLDER = '—'

function toDate(input: unknown): Date | null {
  if (input == null || input === '') return null
  if (input instanceof Date) return isNaN(input.getTime()) ? null : input
  if (typeof input === 'number') {
    const d = new Date(input)
    return isNaN(d.getTime()) ? null : d
  }
  if (typeof input === 'string') {
    const trimmed = input.trim()
    if (!trimmed) return null
    const d = new Date(trimmed)
    return isNaN(d.getTime()) ? null : d
  }
  return null
}

function pad(n: number) { return n < 10 ? `0${n}` : `${n}` }

export function formatDateTime(input: unknown): string {
  const d = toDate(input)
  if (!d) return PLACEHOLDER
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
       + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function formatDate(input: unknown): string {
  const d = toDate(input)
  if (!d) return PLACEHOLDER
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function formatRelative(input: unknown): string {
  const d = toDate(input)
  if (!d) return PLACEHOLDER
  const diffSec = Math.round((Date.now() - d.getTime()) / 1000)
  if (Math.abs(diffSec) < 60) return diffSec >= 0 ? '刚刚' : '即将'
  const abs = Math.abs(diffSec)
  const future = diffSec < 0
  const value = abs < 3600 ? `${Math.round(abs / 60)} 分钟`
              : abs < 86400 ? `${Math.round(abs / 3600)} 小时`
              : abs < 86400 * 30 ? `${Math.round(abs / 86400)} 天`
              : abs < 86400 * 365 ? `${Math.round(abs / (86400 * 30))} 个月`
              : `${Math.round(abs / (86400 * 365))} 年`
  return future ? `${value}后` : `${value}前`
}
