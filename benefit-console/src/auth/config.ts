export type AuthMode = 'dev' | 'oidc'

export const AUTH_MODE: AuthMode = import.meta.env.VITE_AUTH_MODE === 'oidc' ? 'oidc' : 'dev'

export const AUTH_CONFIG = {
  serverUrl: import.meta.env.VITE_CASDOOR_SERVER_URL ?? 'http://localhost:8000',
  clientId: import.meta.env.VITE_CASDOOR_CLIENT_ID ?? 'ragshared0client00000001-org-benefit-center',
  organization: import.meta.env.VITE_CASDOOR_ORGANIZATION ?? 'benefit-center',
  scope: import.meta.env.VITE_CASDOOR_SCOPE ?? 'openid profile offline_access',
}

export const DEV_TENANT_KEY = 'benefit.dev.tenantId'
export const DEFAULT_DEV_TENANT = 'dev-tenant'

export function getDevTenantId(): string {
  if (typeof window === 'undefined') return DEFAULT_DEV_TENANT
  return window.localStorage.getItem(DEV_TENANT_KEY)?.trim() || DEFAULT_DEV_TENANT
}

export function setDevTenantId(tenantId: string) {
  window.localStorage.setItem(DEV_TENANT_KEY, tenantId.trim() || DEFAULT_DEV_TENANT)
}
