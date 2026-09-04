import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { PageSkeleton } from '../components/common/AsyncState'
import { useAuth } from './AuthContext'

export function RequireAuth({ permission }: { permission?: string }) {
  const auth = useAuth()
  const location = useLocation()
  if (!auth.loaded) return <PageSkeleton />
  if (!auth.authenticated) {
    const returnTo = encodeURIComponent(location.pathname + location.search)
    return <Navigate replace to={`/login?returnTo=${returnTo}`} />
  }
  if (!auth.can(permission)) return <Navigate replace to="/forbidden" />
  return <Outlet />
}

export function RequireGuest() {
  const auth = useAuth()
  if (!auth.loaded) return <PageSkeleton />
  return auth.authenticated ? <Navigate replace to="/dashboard" /> : <Outlet />
}
