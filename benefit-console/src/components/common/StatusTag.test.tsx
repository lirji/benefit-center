import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AwardItemStatusTag, AwardOrderStatusTag, canRemediateItem } from './StatusTag'

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
})
