import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { findAwardOrder, getAttentionOrders, getAwardOrder } from '../api/benefit'
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

  it('looks up an order from marketing deep-link query params', async () => {
    const order = {
      orderNo: 'ORD-1',
      sourceSystem: 'drools-activity',
      sourceRequestId: 'src-dead-1',
      sourceBusinessNo: null,
      recipientRef: 'user-1',
      status: 'SUCCEEDED',
      homeCell: 'cell-a',
      items: [],
    }
    vi.mocked(findAwardOrder).mockResolvedValue(order)
    vi.mocked(getAwardOrder).mockResolvedValue(order)
    renderApp(<OrdersPage />, undefined, ['/orders?q=src-dead-1&sourceSystem=drools-activity'])
    expect(await screen.findByDisplayValue('src-dead-1')).toBeInTheDocument()
    expect(findAwardOrder).toHaveBeenCalledWith('drools-activity', 'src-dead-1')
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
