import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getAttentionOrders, getOverview } from '../api/benefit'
import { renderApp } from '../test/render'
import { DashboardPage } from './DashboardPage'

vi.mock('../api/benefit', () => ({
  getOverview: vi.fn(),
  getAttentionOrders: vi.fn(),
}))

const mockedOverview = vi.mocked(getOverview)
const mockedAttention = vi.mocked(getAttentionOrders)

describe('DashboardPage', () => {
  beforeEach(() => {
    mockedOverview.mockResolvedValue({
      unknownOps: 2,
      partialOrders: 1,
      pendingRemediations: 3,
      skuCount: 0,
      enabledRoutes: 0,
    })
    mockedAttention.mockResolvedValue([])
  })

  it('shows real counts and empty catalog CTA', async () => {
    renderApp(<DashboardPage />)
    expect(await screen.findByRole('heading', { name: '权益运营总览' })).toBeInTheDocument()
    expect(screen.getByLabelText('未知履约')).toHaveTextContent('2')
    expect(screen.getByLabelText('待处理补发')).toHaveTextContent('3')
    expect(await screen.findByText(/尚未配置 SKU/)).toBeInTheDocument()
  })
})
