import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getAwardOrder } from '../../api/benefit'
import type { AwardOrder } from '../../api/types'
import { renderApp } from '../../test/render'
import { OrderDetailDrawer } from './OrderDetailDrawer'

vi.mock('../../api/benefit', () => ({
  getAwardOrder: vi.fn(),
}))

const baseOrder: AwardOrder = {
  orderNo: 'ORD-1',
  sourceSystem: 'drools',
  sourceRequestId: 'req-1',
  sourceBusinessNo: null,
  recipientRef: 'user-1',
  status: 'SUCCEEDED',
  homeCell: 'cell-0',
  items: [{
    itemNo: 'IT-1',
    clientItemId: 'c1',
    skuId: 'SKU-1',
    benefitType: 'COUPON',
    quantity: 1,
    amountMinor: 1000,
    currency: 'CNY',
    status: 'SUCCEEDED',
    routeId: 'R-1',
    failureCode: null,
    latestOperationNo: 'OP-1',
    latestOperationStatus: 'SUCCEEDED',
    walletEntryId: 'WE-1',
  }],
}

describe('OrderDetailDrawer', () => {
  beforeEach(() => {
    vi.mocked(getAwardOrder).mockReset()
  })

  it('links an issued wallet entry to the wallet drawer', async () => {
    vi.mocked(getAwardOrder).mockResolvedValue(baseOrder)
    renderApp(<OrderDetailDrawer orderNo="ORD-1" onClose={() => undefined} />, undefined, ['/orders?orderNo=ORD-1'])
    expect(await screen.findByRole('button', { name: '入账券包' })).toBeInTheDocument()
    expect(screen.getByText('user-1')).toBeInTheDocument()
  })

  it('keeps the entry id as text when recipientRef is blank', async () => {
    vi.mocked(getAwardOrder).mockResolvedValue({ ...baseOrder, recipientRef: '' })
    renderApp(<OrderDetailDrawer orderNo="ORD-1" onClose={() => undefined} />, undefined, ['/orders?orderNo=ORD-1'])
    expect(await screen.findByText('WE-1')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '入账券包' })).not.toBeInTheDocument()
  })
})
