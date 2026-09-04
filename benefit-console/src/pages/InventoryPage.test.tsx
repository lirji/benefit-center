import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listCodeAssets, listInventory } from '../api/benefit'
import { renderApp } from '../test/render'
import { InventoryPage } from './InventoryPage'

vi.mock('../api/benefit', () => ({
  listInventory: vi.fn(),
  listCodeAssets: vi.fn(),
  adjustInventory: vi.fn(),
  importCodeAsset: vi.fn(),
}))

describe('InventoryPage', () => {
  beforeEach(() => {
    vi.mocked(listInventory).mockResolvedValue([])
    vi.mocked(listCodeAssets).mockResolvedValue([])
  })

  it('requires requestId before adjusting inventory', async () => {
    renderApp(<InventoryPage />)
    expect(await screen.findByRole('heading', { name: '库存中心' })).toBeInTheDocument()
    await userEvent.click(await screen.findByRole('button', { name: '调整库存' }))
    await userEvent.click(await screen.findByRole('button', { name: '提交调整' }))
    expect(await screen.findByText('requestId 必填')).toBeInTheDocument()
    expect(screen.queryByText(/cipher/i)).not.toBeInTheDocument()
  })
})
