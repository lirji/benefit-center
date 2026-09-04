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

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

export const LIST_LIMIT = 50

export function truncatedHint(items: unknown[]): string | undefined {
  return items.length >= LIST_LIMIT ? '列表已截断到 50 条，请用更精确的条件查询。' : undefined
}
