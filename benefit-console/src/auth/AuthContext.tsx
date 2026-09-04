import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import { getMe } from '../api/benefit'
import type { UserSession } from '../api/types'
import { AUTH_MODE } from './config'
import { currentOidcUser, sanitizeReturnTo, userManager, type LoginState } from './oidc'

export type AuthContextValue = {
  user: UserSession | null
  loaded: boolean
  redirecting: boolean
  error: string
  authenticated: boolean
  can: (permission?: string) => boolean
  load: () => Promise<void>
  login: (returnTo?: string) => Promise<void>
  completeLogin: () => Promise<string>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

function friendlyAuthError(error: unknown) {
  if (error instanceof TypeError || (error instanceof Error && /failed to fetch|network|无法连接/i.test(error.message))) {
    return '无法连接统一身份服务，请确认 Casdoor 已启动后重试。'
  }
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
    return '当前身份没有访问权益运营台的权限。'
  }
  return error instanceof Error ? error.message : '身份服务暂时不可用，请稍后重试。'
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSession | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [redirecting, setRedirecting] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      if (AUTH_MODE === 'oidc' && !(await currentOidcUser())) {
        setUser(null)
        return
      }
      setUser(await getMe())
    } catch (cause) {
      setUser(null)
      if (!(cause instanceof ApiError) || (cause.status !== 401 && cause.status !== 403)) {
        setError(friendlyAuthError(cause))
      }
    } finally {
      setLoaded(true)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const login = useCallback(
    async (returnTo = '/dashboard') => {
      setError('')
      if (AUTH_MODE === 'dev') {
        setLoaded(false)
        await load()
        window.location.assign(sanitizeReturnTo(returnTo))
        return
      }
      setRedirecting(true)
      try {
        await userManager.signinRedirect({ state: { returnTo: sanitizeReturnTo(returnTo) } satisfies LoginState })
      } catch (cause) {
        setRedirecting(false)
        setError(friendlyAuthError(cause))
      }
    },
    [load],
  )

  const completeLogin = useCallback(async () => {
    const oidcUser = await userManager.signinRedirectCallback()
    const state = oidcUser.state as LoginState | undefined
    setLoaded(false)
    await load()
    return sanitizeReturnTo(state?.returnTo)
  }, [load])

  const logout = useCallback(async () => {
    setUser(null)
    setLoaded(true)
    if (AUTH_MODE === 'oidc') {
      try {
        await userManager.signoutRedirect()
        return
      } catch {
        await userManager.removeUser()
      }
    }
    window.location.assign('/login')
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      loaded,
      redirecting,
      error,
      authenticated: Boolean(user?.authenticated),
      can: (permission?: string) => !permission || Boolean(user?.permissions.includes(permission)),
      load,
      login,
      completeLogin,
      logout,
    }),
    [user, loaded, redirecting, error, load, login, completeLogin, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}
