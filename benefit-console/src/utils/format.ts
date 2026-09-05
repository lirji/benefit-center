import { ApiError } from '../api/client'

export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}

export function formatCount(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(value)
}

export function formatMinor(value?: number | null, currency?: string | null): string {
  if (value == null) return '—'
  const formatted = new Intl.NumberFormat('zh-CN').format(value)
  return currency ? `${currency} ${formatted}` : formatted
}

const ERROR_COPY: Record<string, string> = {
  SKU_NOT_DRAFT: '只有草稿可以提交上线审批',
  SKU_VERSION_CONFLICT: '模板已被他人更新，请刷新后重试',
  SKU_APPROVAL_LOCKED: '审批中不能修改模板',
  SKU_ILLEGAL_TRANSITION: '不允许的状态变更',
  SKU_SUBMIT_IN_FLIGHT: '该版本已在审批中',
  WALLET_ENTRY_NOT_FOUND: '券包条目不存在',
  WALLET_ILLEGAL_TRANSITION: '当前状态不能做这个操作',
  WALLET_ALREADY_USED: '该券已核销，不能重复核销',
  WALLET_VERSION_CONFLICT: '条目已被他人更新，请刷新后重试',
  WALLET_EXPIRED: '该券已过期，不能再核销',
  IDEMPOTENCY_PAYLOAD_CONFLICT: '相同幂等键不能提交不同内容，请刷新后重试',
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code && ERROR_COPY[error.code]) return ERROR_COPY[error.code]
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

export const LIST_LIMIT = 50

export function truncatedHint(items: unknown[]): string | undefined {
  return items.length >= LIST_LIMIT ? '列表已截断到 50 条，请用更精确的条件查询。' : undefined
}

export function toDatetimeLocal(value?: string | null): string | undefined {
  if (!value) return undefined
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return undefined
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function fromDatetimeLocal(value?: string | null): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}
