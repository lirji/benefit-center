import axios, { type InternalAxiosRequestConfig } from 'axios'
import { AUTH_MODE, getDevTenantId } from '../auth/config'
import { getAccessToken, renewAccessToken } from '../auth/oidc'

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly code?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

/** Admin writes require Idempotency-Key. Callers may set a business key first (e.g. remediations). */
export function applyWriteIdempotencyKey(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig {
  const method = (config.method ?? 'get').toUpperCase()
  if (!WRITE_METHODS.has(method)) return config
  const existing = config.headers.get('Idempotency-Key')
  if (existing) return config
  config.headers.set('Idempotency-Key', crypto.randomUUID())
  return config
}

export const api = axios.create({
  baseURL: import.meta.env.VITE_BENEFIT_API_BASE || '',
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
})

api.interceptors.request.use(async (config) => {
  applyWriteIdempotencyKey(config)
  const token = await getAccessToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  if (AUTH_MODE === 'dev') config.headers['X-Tenant-Id'] = getDevTenantId()
  return config
})

api.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const original = error.config as (InternalAxiosRequestConfig & { _retry?: boolean }) | undefined
      if (error.response?.status === 401 && AUTH_MODE === 'oidc' && original && !original._retry) {
        original._retry = true
        const renewed = await renewAccessToken()
        if (renewed) {
          original.headers.Authorization = `Bearer ${renewed}`
          return api(original)
        }
      }
      const body = error.response?.data as { error?: string; code?: string; message?: string } | undefined
      const fallback = error.response ? `请求失败（${error.response.status}）` : '无法连接权益服务'
      return Promise.reject(new ApiError(body?.message || fallback, error.response?.status, body?.error || body?.code))
    }
    return Promise.reject(error)
  },
)
