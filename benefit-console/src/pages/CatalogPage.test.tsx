import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getCurrentTenant, getSkuApprovalRuntime, listRoutes, listSkus, retrySkuApproval, saveSku, submitSkuApproval, withdrawSkuApproval } from '../api/benefit'
import type { SkuView } from '../api/types'
import { renderApp } from '../test/render'
import { CatalogPage } from './CatalogPage'

vi.mock('../api/benefit', () => ({
  getCurrentTenant: vi.fn(),
  getSkuApprovalRuntime: vi.fn(),
  listSkus: vi.fn(),
  listRoutes: vi.fn(),
  saveSku: vi.fn(),
  submitSkuApproval: vi.fn(),
  retrySkuApproval: vi.fn(),
  withdrawSkuApproval: vi.fn(),
  saveRoute: vi.fn(),
  saveTenant: vi.fn(),
}))

const draftSku: SkuView = {
  skuId: 'SKU-DRAFT',
  benefitType: 'COUPON',
  faceValueMinor: null,
  currency: null,
  status: 'DRAFT',
  enabled: false,
  validityType: 'RELATIVE',
  validFrom: null,
  validTo: null,
  relativeDays: 7,
  usableWeekdays: [1, 2, 3, 4, 5, 6, 7],
  dailyQuota: null,
  userLimitPerDay: null,
  userLimitTotal: null,
  equivalentSkuId: null,
  version: 0,
}

const pendingSku: SkuView = {
  ...draftSku,
  skuId: 'SKU-PENDING',
  status: 'PENDING_APPROVAL',
  approvalProcessDefinitionKey: 'benefitSkuGoLive',
  approvalBusinessKey: 'SKU-PENDING',
  version: 1,
}

describe('CatalogPage', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.mocked(getCurrentTenant).mockResolvedValue({ tenantId: 'dev-tenant', homeCell: 'cell-0', enabled: true, version: 1 })
    vi.mocked(listSkus).mockResolvedValue([])
    vi.mocked(listRoutes).mockResolvedValue([])
    vi.mocked(saveSku).mockResolvedValue(undefined as never)
    vi.mocked(submitSkuApproval).mockResolvedValue({ skuId: 'SKU-DRAFT', status: 'PENDING_APPROVAL', version: 1 })
    vi.mocked(getSkuApprovalRuntime).mockResolvedValue({ instancePresent: false, definitionStatus: 'DEPLOYED' })
    vi.mocked(retrySkuApproval).mockResolvedValue({ skuId: 'SKU-PENDING', status: 'PENDING_APPROVAL', version: 1 })
    vi.mocked(withdrawSkuApproval).mockResolvedValue({ skuId: 'SKU-PENDING', status: 'DRAFT', version: 2 })
  })

  it('requires relative days for relative validity', async () => {
    renderApp(<CatalogPage />)
    expect(await screen.findByRole('heading', { name: '权益目录' })).toBeInTheDocument()
    await userEvent.click(await screen.findByRole('button', { name: '新建模板' }))
    expect(await screen.findByRole('dialog', { name: '新建模板' })).toBeInTheDocument()
    await userEvent.type(await screen.findByLabelText('SKU ID'), 'SKU-REL')
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    expect(await screen.findByText('相对有效期必须填写天数')).toBeInTheDocument()
    expect(saveSku).not.toHaveBeenCalled()
  })

  it('lets a draft submit for approval and never offers ACTIVE as a draft exit', async () => {
    vi.mocked(listSkus).mockResolvedValue([draftSku])
    renderApp(<CatalogPage />)
    await userEvent.click(await screen.findByRole('tab', { name: '模板' }))
    await userEvent.click(await screen.findByRole('button', { name: '编辑' }))
    expect(await screen.findByRole('button', { name: '提交上线审批' })).toBeEnabled()
    expect(screen.queryByRole('combobox', { name: /已投放/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '提交上线审批' }))
    await waitFor(() => expect(submitSkuApproval).toHaveBeenCalledWith('SKU-DRAFT', { expectedVersion: 0 }))
    expect(await screen.findByText('已提交审批')).toBeInTheDocument()
    expect(screen.queryByText('已投放')).not.toBeInTheDocument()
    expect(screen.getByText('实例未建立')).toBeInTheDocument()
  })

  it('keeps the drawer open after the first draft save so approval can be submitted', async () => {
    vi.mocked(saveSku).mockImplementation(async () => {
      vi.mocked(listSkus).mockResolvedValue([{ ...draftSku, skuId: 'SKU-KEEP', relativeDays: 7 }])
      return undefined as never
    })
    renderApp(<CatalogPage />)
    await userEvent.click(await screen.findByRole('button', { name: '新建模板' }))
    expect(await screen.findByRole('dialog', { name: '新建模板' })).toBeInTheDocument()
    await userEvent.type(await screen.findByLabelText('SKU ID'), 'SKU-KEEP')
    await userEvent.type(screen.getByLabelText('领取后有效天数'), '7')
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await waitFor(() => expect(saveSku).toHaveBeenCalled())
    expect(await screen.findByText('模板已保存')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '提交上线审批' })).toBeEnabled()
  })

  it('keeps pending templates read-only and shows instance-not-created copy without origin', async () => {
    vi.mocked(listSkus).mockResolvedValue([pendingSku])
    renderApp(<CatalogPage />)
    await userEvent.click(await screen.findByRole('tab', { name: '模板' }))
    await userEvent.click(await screen.findByRole('button', { name: '查看' }))
    expect(await screen.findByText('实例未建立')).toBeInTheDocument()
    expect(screen.getByText(/http:\/\/localhost:8302\/tasks\?definitionKey=benefitSkuGoLive&businessKey=SKU-PENDING/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重发审批' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '退回草稿' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '提交上线审批' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '查看审批轨迹' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('SKU ID')).toBeDisabled()
  })

  it('retries the same approval version and withdraws only after confirmation', async () => {
    vi.mocked(listSkus).mockResolvedValue([pendingSku])
    renderApp(<CatalogPage />)
    await userEvent.click(await screen.findByRole('tab', { name: '模板' }))
    await userEvent.click(await screen.findByRole('button', { name: '查看' }))
    await userEvent.click(await screen.findByRole('button', { name: '重发审批' }))
    await waitFor(() => expect(retrySkuApproval).toHaveBeenCalledWith('SKU-PENDING', { expectedVersion: 1 }))

    await userEvent.click(screen.getByRole('button', { name: '退回草稿' }))
    expect(await screen.findByText('该操作只把 SKU 退回 DRAFT，不会终止任何已存在或迟到建立的流程实例。')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '确认退回' }))
    await waitFor(() => expect(withdrawSkuApproval).toHaveBeenCalledWith('SKU-PENDING', { expectedVersion: 1 }))
  })

  it('hides recovery actions when workflow reports an existing instance', async () => {
    vi.mocked(listSkus).mockResolvedValue([pendingSku])
    vi.mocked(getSkuApprovalRuntime).mockResolvedValue({
      instancePresent: true,
      definitionStatus: 'DEPLOYED',
      processInstanceId: 'pi-1',
    })
    renderApp(<CatalogPage />)
    await userEvent.click(await screen.findByRole('tab', { name: '模板' }))
    await userEvent.click(await screen.findByRole('button', { name: '查看' }))
    expect(await screen.findByText('已提交上线审批，办理在流程中台')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重发审批' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '退回草稿' })).not.toBeInTheDocument()
  })

  it('shows a missing-template alert for unknown skuId deep links', async () => {
    renderApp(<CatalogPage />, undefined, ['/catalog?skuId=MISSING'])
    expect(await screen.findByText('未找到该模板')).toBeInTheDocument()
  })
})
