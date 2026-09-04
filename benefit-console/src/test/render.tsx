import type { PropsWithChildren, ReactElement } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { appTheme } from '../theme/theme'
import { AuthContext, type AuthContextValue } from '../auth/AuthContext'
import type { UserSession } from '../api/types'

const ALL_PERMISSIONS = ['benefit.admin', 'benefit.award.read', 'benefit.award.write', 'benefit.remediate']

export function mockAuth(overrides: Partial<UserSession> = {}): AuthContextValue {
  const user: UserSession = {
    authenticated: true,
    sub: 'test-user',
    name: 'qa-ops',
    tenantId: 'dev-tenant',
    permissions: ALL_PERMISSIONS,
    ...overrides,
  }
  return {
    user,
    loaded: true,
    redirecting: false,
    error: '',
    authenticated: user.authenticated,
    can: (permission?: string) => !permission || user.permissions.includes(permission),
    load: async () => {},
    login: async () => {},
    completeLogin: async () => '/dashboard',
    logout: async () => {},
  }
}

export function renderApp(
  element: ReactElement,
  auth: AuthContextValue = mockAuth(),
  initialEntries: string[] = ['/'],
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: PropsWithChildren) {
    return (
      <ConfigProvider locale={zhCN} theme={appTheme}>
        <App>
          <QueryClientProvider client={queryClient}>
            <AuthContext.Provider value={auth}>
              <MemoryRouter initialEntries={initialEntries} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
                {children}
              </MemoryRouter>
            </AuthContext.Provider>
          </QueryClientProvider>
        </App>
      </ConfigProvider>
    )
  }
  return { ...render(element, { wrapper: Wrapper }), queryClient }
}
