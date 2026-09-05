import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AwardItemStatusTag, AwardOrderStatusTag, canRemediateItem, SkuStatusTag, WalletEntryStatusTag } from './StatusTag'

describe('status tags', () => {
  it('renders Chinese labels, not raw colors only', () => {
    render(<AwardOrderStatusTag status="PARTIAL_SUCCEEDED" />)
    expect(screen.getByText('部分成功')).toBeInTheDocument()
    render(<AwardItemStatusTag status="UNKNOWN" />)
    expect(screen.getByText('结果未知')).toBeInTheDocument()
  })

  it('disables remediation for UNKNOWN and QUERYING', () => {
    expect(canRemediateItem('UNKNOWN')).toBe(false)
    expect(canRemediateItem('QUERYING')).toBe(false)
    expect(canRemediateItem('FAILED_FINAL')).toBe(true)
  })

  it('renders template and wallet statuses', () => {
    render(<SkuStatusTag status="ACTIVE" />)
    expect(screen.getByText('已投放')).toBeInTheDocument()
    render(<SkuStatusTag status="PENDING_APPROVAL" />)
    expect(screen.getByText('待审批')).toBeInTheDocument()
    render(<WalletEntryStatusTag status="UNUSED" />)
    expect(screen.getByText('未使用')).toBeInTheDocument()
  })
})
