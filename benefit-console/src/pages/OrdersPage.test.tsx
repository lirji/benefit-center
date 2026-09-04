import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { getAttentionOrders, getAwardOrder } from '../api/benefit'
import { renderApp } from '../test/render'
import { OrdersPage } from './OrdersPage'

vi.mock('../api/benefit', () => ({
  getAttentionOrders: vi.fn(),
  getAwardOrder: vi.fn(),
  findAwardOrder: vi.fn(),
}))

describe('OrdersPage', () => {
  beforeEach(() => {
    vi.mocked(getAttentionOrders).mockResolvedValue([])
  })

  it('shows a friendly empty state when the order is missing', async () => {
    vi.mocked(getAwardOrder).mockRejectedValue(new ApiError('未找到该发放订单', 404))
    renderApp(<OrdersPage />, undefined, ['/orders'])
    expect(await screen.findByRole('heading', { name: '发放订单' })).toBeInTheDocument()
    await userEvent.type(await screen.findByPlaceholderText('ORD-...'), 'MISSING')
    await userEvent.click(await screen.findByRole('button', { name: '查询' }))
    expect(await screen.findByText(/未找到该发放订单/)).toBeInTheDocument()
  })
})
