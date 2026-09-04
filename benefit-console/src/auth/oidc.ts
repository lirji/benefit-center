import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { AUTH_CONFIG, AUTH_MODE } from './config'

export interface LoginState {
  returnTo?: string
}

export function sanitizeReturnTo(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return '/dashboard'
  if (value.startsWith('/auth/callback') || value.startsWith('/login')) return '/dashboard'
  return value
}

export const userManager = new UserManager({
  authority: AUTH_CONFIG.serverUrl,
  client_id: AUTH_CONFIG.clientId,
  redirect_uri: `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: `${window.location.origin}/login`,
  response_type: 'code',
  scope: AUTH_CONFIG.scope,
  loadUserInfo: false,
  automaticSilentRenew: false,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
})

export async function currentOidcUser(): Promise<User | null> {
  if (AUTH_MODE !== 'oidc') return null
  const user = await userManager.getUser()
  if (!user) return null
  if (!user.expired) return user
  try {
    return await userManager.signinSilent()
  } catch {
    await userManager.removeUser()
    return null
  }
}

export async function getAccessToken(): Promise<string | undefined> {
  return (await currentOidcUser())?.access_token
}

export async function renewAccessToken(): Promise<string | undefined> {
  if (AUTH_MODE !== 'oidc') return undefined
  const existing = await userManager.getUser()
  if (!existing) return undefined
  try {
    return (await userManager.signinSilent())?.access_token
  } catch {
    await userManager.removeUser()
    return undefined
  }
}
