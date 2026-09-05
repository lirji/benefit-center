import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { commandWalletEntry } from '../../api/benefit'
import type { WalletEntryView } from '../../api/types'
import { renderApp } from '../../test/render'
import { WalletDetailDrawer } from './WalletDetailDrawer'

vi.mock('../../api/benefit', () => ({
  commandWalletEntry: vi.fn(),
}))

const unusedEntry: WalletEntryView = {
  entryId: 'WE-1',
  subjectRef: 'user-1',
  skuId: 'SKU-1',
  skuVersion: 1,
  awardOrderNo: 'ORD-1',
  itemNo: 'IT-1',
  assetType: 'COUPON',
  status: 'UNUSED',
  version: 0,
  expiresAt: '2026-12-01T00:00:00Z',
  faceValueMinor: 1000,
  currency: 'CNY',
  createdAt: '2026-09-01T00:00:00Z',
}

describe('WalletDetailDrawer', () => {
  beforeEach(() => {
    vi.mocked(commandWalletEntry).mockReset()
    vi.mocked(commandWalletEntry).mockResolvedValue({ entryId: 'WE-1', status: 'USED', version: 1 })
  })

  it('shows freeze and redeem for unused entries', async () => {
    renderApp(<WalletDetailDrawer entry={unusedEntry} canWrite onClose={() => undefined} />)
    expect(await screen.findByRole('button', { name: '冻结' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '核销' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '退券' })).not.toBeInTheDocument()
  })

  it('shows refund only for used entries', async () => {
    renderApp(<WalletDetailDrawer entry={{ ...unusedEntry, status: 'USED', version: 2 }} canWrite onClose={() => undefined} />)
    expect(await screen.findByRole('button', { name: '退券' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '冻结' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '核销' })).not.toBeInTheDocument()
  })

  it('hides action buttons without write permission', () => {
    renderApp(<WalletDetailDrawer entry={unusedEntry} canWrite={false} onClose={() => undefined} />)
    expect(screen.getByText('WE-1')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '冻结' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '核销' })).not.toBeInTheDocument()
  })

  it('submits redeem with expectedVersion and a business idempotency key', async () => {
    const onAccepted = vi.fn()
    renderApp(<WalletDetailDrawer entry={unusedEntry} canWrite onClose={() => undefined} onAccepted={onAccepted} />)
    await userEvent.click(await screen.findByRole('button', { name: '核销' }))
    expect(await screen.findByText('确认核销这张券？')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '确认提交' }))
    await waitFor(() => expect(commandWalletEntry).toHaveBeenCalledWith(
      'WE-1',
      'redeem',
      { expectedVersion: 0 },
      'redeem:WE-1:0',
    ))
    expect(await screen.findByText('已受理')).toBeInTheDocument()
    expect(onAccepted).toHaveBeenCalledWith({ entryId: 'WE-1', status: 'USED', version: 1 })
  })

  it('maps already-used conflicts instead of pretending success', async () => {
    vi.mocked(commandWalletEntry).mockRejectedValue(new ApiError('conflict', 409, 'WALLET_ALREADY_USED'))
    renderApp(<WalletDetailDrawer entry={unusedEntry} canWrite onClose={() => undefined} />)
    await userEvent.click(await screen.findByRole('button', { name: '核销' }))
    await userEvent.click(await screen.findByRole('button', { name: '确认提交' }))
    expect(await screen.findByText('该券已核销，不能重复核销')).toBeInTheDocument()
    expect(screen.queryByText('已受理')).not.toBeInTheDocument()
  })
})
