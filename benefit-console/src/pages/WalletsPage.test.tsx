import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { commandWalletEntry, getWallet, listWalletEntries } from '../api/benefit'
import type { WalletEntryView } from '../api/types'
import { mockAuth, renderApp } from '../test/render'
import { WalletsPage } from './WalletsPage'

vi.mock('../api/benefit', () => ({
  getWallet: vi.fn(),
  listWalletEntries: vi.fn(),
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

describe('WalletsPage', () => {
  beforeEach(() => {
    vi.mocked(getWallet).mockReset()
    vi.mocked(listWalletEntries).mockReset()
    vi.mocked(commandWalletEntry).mockReset()
  })

  it('does not query until a subject is submitted', async () => {
    renderApp(<WalletsPage />, undefined, ['/wallets'])
    expect(await screen.findByRole('heading', { name: '用户资产' })).toBeInTheDocument()
    expect(screen.getByText(/输入用户稳定引用后查询券包/)).toBeInTheDocument()
    expect(getWallet).not.toHaveBeenCalled()
    expect(listWalletEntries).not.toHaveBeenCalled()
  })

  it('shows empty state when the wallet is missing', async () => {
    vi.mocked(getWallet).mockRejectedValue(new ApiError('该用户暂无权益', 404, 'WALLET_NOT_FOUND'))
    vi.mocked(listWalletEntries).mockResolvedValue([])
    renderApp(<WalletsPage />, undefined, ['/wallets'])
    await userEvent.type(screen.getByPlaceholderText(/用户稳定引用/), 'user-1')
    await userEvent.click(screen.getByRole('button', { name: '查询' }))
    expect(await screen.findByText('该用户暂无权益')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '核销' })).not.toBeInTheDocument()
  })

  it('opens the drawer from a subject + entry deep-link', async () => {
    vi.mocked(getWallet).mockResolvedValue({
      subjectRef: 'user-1',
      totalEntries: 1,
      unusedEntries: 1,
      cashBalances: [],
    })
    vi.mocked(listWalletEntries).mockResolvedValue([unusedEntry])
    renderApp(<WalletsPage />, undefined, ['/wallets?subject=user-1&entry=WE-1'])
    expect(await screen.findByRole('dialog', { name: '券包条目' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '核销' })).toBeInTheDocument()
  })

  it('opens unused entry actions for an admin', async () => {
    vi.mocked(getWallet).mockResolvedValue({
      subjectRef: 'user-1',
      totalEntries: 1,
      unusedEntries: 1,
      cashBalances: [],
    })
    vi.mocked(listWalletEntries).mockResolvedValue([unusedEntry])
    renderApp(<WalletsPage />, undefined, ['/wallets'])
    await userEvent.type(screen.getByPlaceholderText(/用户稳定引用/), 'user-1')
    await userEvent.click(screen.getByRole('button', { name: '查询' }))
    await userEvent.click(await screen.findByRole('button', { name: '详情' }))
    expect(await screen.findByRole('button', { name: '冻结' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '核销' })).toBeInTheDocument()
  })

  it('hides wallet commands without benefit.admin', async () => {
    vi.mocked(getWallet).mockResolvedValue({
      subjectRef: 'user-1',
      totalEntries: 1,
      unusedEntries: 1,
      cashBalances: [],
    })
    vi.mocked(listWalletEntries).mockResolvedValue([unusedEntry])
    renderApp(<WalletsPage />, mockAuth({ permissions: ['benefit.award.read'] }), ['/wallets'])
    await userEvent.type(screen.getByPlaceholderText(/用户稳定引用/), 'user-1')
    await userEvent.click(screen.getByRole('button', { name: '查询' }))
    await userEvent.click(await screen.findByRole('button', { name: '详情' }))
    expect(await screen.findByRole('dialog', { name: '券包条目' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '冻结' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '核销' })).not.toBeInTheDocument()
  })
})
