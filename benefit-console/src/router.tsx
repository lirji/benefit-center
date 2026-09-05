import { Suspense, lazy, type ReactNode } from 'react'
import { Navigate, Outlet, createBrowserRouter } from 'react-router-dom'
import { AppLayout } from './components/layout/AppLayout'
import { PageSkeleton } from './components/common/AsyncState'
import { AuthProvider } from './auth/AuthContext'
import { RequireAuth, RequireGuest } from './auth/RequireAuth'

const DashboardPage = lazy(() => import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })))
const OrdersPage = lazy(() => import('./pages/OrdersPage').then((m) => ({ default: m.OrdersPage })))
const RemediationsPage = lazy(() => import('./pages/RemediationsPage').then((m) => ({ default: m.RemediationsPage })))
const InventoryPage = lazy(() => import('./pages/InventoryPage').then((m) => ({ default: m.InventoryPage })))
const CatalogPage = lazy(() => import('./pages/CatalogPage').then((m) => ({ default: m.CatalogPage })))
const WalletsPage = lazy(() => import('./pages/WalletsPage').then((m) => ({ default: m.WalletsPage })))
const LoginPage = lazy(() => import('./pages/LoginPage').then((m) => ({ default: m.LoginPage })))
const CallbackPage = lazy(() => import('./pages/CallbackPage').then((m) => ({ default: m.CallbackPage })))
const ForbiddenPage = lazy(() => import('./pages/ForbiddenPage').then((m) => ({ default: m.ForbiddenPage })))

const lazyRoute = (node: ReactNode): ReactNode => <Suspense fallback={<PageSkeleton />}>{node}</Suspense>

function AuthLayout() {
  return (
    <AuthProvider>
      <Outlet />
    </AuthProvider>
  )
}

export const router = createBrowserRouter([
  {
    element: <AuthLayout />,
    children: [
      { path: 'login', element: <RequireGuest />, children: [{ index: true, element: lazyRoute(<LoginPage />) }] },
      { path: 'auth/callback', element: lazyRoute(<CallbackPage />) },
      { path: 'forbidden', element: lazyRoute(<ForbiddenPage />) },
      {
        path: '/',
        element: <RequireAuth permission="benefit.admin" />,
        children: [
          {
            element: <AppLayout />,
            children: [
              { index: true, element: <Navigate to="/dashboard" replace /> },
              { path: 'dashboard', element: lazyRoute(<DashboardPage />) },
              { path: 'orders', element: lazyRoute(<OrdersPage />) },
              { path: 'remediations', element: lazyRoute(<RemediationsPage />) },
              { path: 'inventory', element: lazyRoute(<InventoryPage />) },
              { path: 'catalog', element: lazyRoute(<CatalogPage />) },
              { path: 'wallets', element: lazyRoute(<WalletsPage />) },
            ],
          },
        ],
      },
      { path: '*', element: <Navigate to="/dashboard" replace /> },
    ],
  },
])
