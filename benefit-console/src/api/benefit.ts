import { api, ApiError } from './client'
import type {
  AttentionOrder,
  AwardOrder,
  CodeAssetView,
  ConsoleIdentity,
  InventoryView,
  Overview,
  RemediationCommand,
  RemediationResult,
  RemediationView,
  RouteView,
  SkuView,
  SkuApprovalRuntime,
  SkuSubmitAcceptance,
  TenantView,
  UserSession,
  WalletEntryCommand,
  WalletEntryCommandAcceptance,
  WalletEntryView,
  WalletView,
} from './types'

export function getMe(): Promise<UserSession> {
  return api.get<ConsoleIdentity>('/admin/v1/console/me').then(({ data }) => ({
    authenticated: true,
    sub: data.subject,
    name: data.displayName || data.subject,
    tenantId: data.tenantId,
    permissions: data.scopes,
  }))
}

export const getOverview = () => api.get<Overview>('/admin/v1/console/overview').then((r) => r.data)
export const getAttentionOrders = (limit = 20) =>
  api.get<AttentionOrder[]>('/admin/v1/console/attention-orders', { params: { limit } }).then((r) => r.data)
export const getCurrentTenant = () => api.get<TenantView>('/admin/v1/tenants/current').then((r) => r.data)
export const listSkus = (limit = 20) => api.get<SkuView[]>('/admin/v1/skus', { params: { limit } }).then((r) => r.data)
export const listRoutes = (skuId?: string, limit = 20) =>
  api.get<RouteView[]>('/admin/v1/routes', { params: { skuId, limit } }).then((r) => r.data)
export const listInventory = (limit = 20) =>
  api.get<InventoryView[]>('/admin/v1/inventory/accounts', { params: { limit } }).then((r) => r.data)
export const listRemediations = (status?: string, limit = 20) =>
  api.get<RemediationView[]>('/admin/v1/remediations', { params: { status, limit } }).then((r) => r.data)
export const listCodeAssets = (skuId?: string, limit = 20) =>
  api.get<CodeAssetView[]>('/admin/v1/code-assets', { params: { skuId, limit } }).then((r) => r.data)

export async function getAwardOrder(orderNo: string): Promise<AwardOrder> {
  try {
    return (await api.get<AwardOrder>(`/openapi/v1/award-orders/${encodeURIComponent(orderNo)}`)).data
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) throw new ApiError('未找到该发放订单', 404)
    throw error
  }
}

export async function findAwardOrder(sourceSystem: string, sourceRequestId: string): Promise<AwardOrder> {
  try {
    return (await api.get<AwardOrder>('/openapi/v1/award-orders', { params: { sourceSystem, sourceRequestId } })).data
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) throw new ApiError('未找到该发放订单', 404)
    throw error
  }
}

export const getRemediation = (remediationNo: string) =>
  api.get<RemediationResult>(`/internal/v1/remediations/${encodeURIComponent(remediationNo)}`).then((r) => r.data)
export const acceptRemediation = (command: RemediationCommand) =>
  api
    .post<RemediationResult>('/internal/v1/remediations', command, {
      headers: { 'Idempotency-Key': command.externalCommandId },
    })
    .then((r) => r.data)
export const executeRemediation = (remediationNo: string) =>
  api
    .post<RemediationResult>(`/internal/v1/remediations/${encodeURIComponent(remediationNo)}/execute`, undefined, {
      headers: { 'Idempotency-Key': remediationNo },
    })
    .then((r) => r.data)

export async function getWallet(subjectRef: string): Promise<WalletView> {
  try {
    return (await api.get<WalletView>(`/admin/v1/wallets/${encodeURIComponent(subjectRef)}`)).data
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) throw new ApiError('该用户暂无权益', 404, 'WALLET_NOT_FOUND')
    throw error
  }
}

export const listWalletEntries = (subjectRef: string, params?: { status?: string; skuId?: string; limit?: number }) =>
  api.get<WalletEntryView[]>(`/admin/v1/wallets/${encodeURIComponent(subjectRef)}/entries`, { params }).then((r) => r.data)

export type WalletEntryAction = 'freeze' | 'redeem' | 'refund'

export function commandWalletEntry(
  entryId: string,
  action: WalletEntryAction,
  body: WalletEntryCommand,
  idempotencyKey: string,
) {
  return api
    .post<WalletEntryCommandAcceptance>(
      `/openapi/v1/wallet-entries/${encodeURIComponent(entryId)}:${action}`,
      body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )
    .then((r) => r.data)
}

export const saveTenant = (tenantId: string, body: object) =>
  api.put(`/admin/v1/tenants/${encodeURIComponent(tenantId)}`, body)
export const saveSku = (skuId: string, body: object) =>
  api.put(`/admin/v1/skus/${encodeURIComponent(skuId)}`, body)
export const submitSkuApproval = (skuId: string, body: { expectedVersion: number }) =>
  api
    .post<SkuSubmitAcceptance>(`/admin/v1/skus/${encodeURIComponent(skuId)}:submit-for-approval`, body)
    .then((r) => r.data)
export const retrySkuApproval = (skuId: string, body: { expectedVersion: number }) =>
  api
    .post<SkuSubmitAcceptance>(`/admin/v1/skus/${encodeURIComponent(skuId)}:retry-approval`, body)
    .then((r) => r.data)
export const withdrawSkuApproval = (skuId: string, body: { expectedVersion: number }) =>
  api
    .post<SkuSubmitAcceptance>(`/admin/v1/skus/${encodeURIComponent(skuId)}:withdraw-approval`, body)
    .then((r) => r.data)
export const getSkuApprovalRuntime = (skuId: string) =>
  api
    .get<SkuApprovalRuntime>(`/admin/v1/skus/${encodeURIComponent(skuId)}/approval-runtime`)
    .then((r) => r.data)
export const saveRoute = (routeId: string, body: object) =>
  api.put(`/admin/v1/routes/${encodeURIComponent(routeId)}`, body)
export const adjustInventory = (body: object) => api.post('/admin/v1/inventory/adjustments', body)
export const importCodeAsset = (body: object) => api.post('/admin/v1/code-assets', body)
