import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ConfigProvider } from 'antd'
import { RemediationForm } from './RemediationForm'

describe('RemediationForm', () => {
  it('disables submit when the item is UNKNOWN', () => {
    render(
      <ConfigProvider>
        <RemediationForm itemStatus="UNKNOWN" operationStatus="UNKNOWN" onSubmit={vi.fn()} />
      </ConfigProvider>,
    )
    expect(screen.getByRole('button', { name: '提交补发' })).toBeDisabled()
    expect(screen.getByText(/UNKNOWN \/ QUERYING 不能补发/)).toBeInTheDocument()
  })
})
