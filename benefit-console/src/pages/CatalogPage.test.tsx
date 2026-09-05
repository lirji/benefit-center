import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getCurrentTenant, listRoutes, listSkus, saveSku, submitSkuApproval } from '../api/benefit'
import type { SkuView } from '../api/types'
import { renderApp } from '../test/render'
import { CatalogPage } from './CatalogPage'

vi.mock('../api/benefit', () => ({
  getCurrentTenant: vi.fn(),
  listSkus: vi.fn(),
  listRoutes: vi.fn(),
  saveSku: vi.fn(),
  submitSkuApproval: vi.fn(),
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

describe('CatalogPage', { timeout: 15_000 }, () => {
  beforeEach(() => {
    vi.mocked(getCurrentTenant).mockResolvedValue({ tenantId: 'dev-tenant', homeCell: 'cell-0', enabled: true, version: 1 })
    vi.mocked(listSkus).mockResolvedValue([])
    vi.mocked(listRoutes).mockResolvedValue([])
    vi.mocked(saveSku).mockResolvedValue(undefined as never)
    vi.mocked(submitSkuApproval).mockResolvedValue({ skuId: 'SKU-DRAFT', status: 'PENDING_APPROVAL', version: 1 })
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
    expect(screen.getByText('已提交上线审批，办理在流程中台')).toBeInTheDocument()
  })

  it('keeps pending templates read-only and hides workflow links without origin', async () => {
    vi.mocked(listSkus).mockResolvedValue([pendingSku])
    renderApp(<CatalogPage />)
    await userEvent.click(await screen.findByRole('tab', { name: '模板' }))
    await userEvent.click(await screen.findByRole('button', { name: '查看' }))
    expect(await screen.findByText('已提交上线审批，办理在流程中台')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '提交上线审批' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '查看审批轨迹' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('SKU ID')).toBeDisabled()
  })

  it('shows a missing-template alert for unknown skuId deep links', async () => {
    renderApp(<CatalogPage />, undefined, ['/catalog?skuId=MISSING'])
    expect(await screen.findByText('未找到该模板')).toBeInTheDocument()
  })
})
