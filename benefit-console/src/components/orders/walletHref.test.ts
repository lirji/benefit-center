import { describe, expect, it } from 'vitest'
import { walletEntryHref } from './walletHref'

describe('walletEntryHref', () => {
  it('builds a subject + entry deep-link', () => {
    expect(walletEntryHref('user-1', 'WE-1')).toBe('/wallets?subject=user-1&entry=WE-1')
  })

  it('stays empty without both recipient and entry', () => {
    expect(walletEntryHref(null, 'WE-1')).toBe('')
    expect(walletEntryHref('user-1', null)).toBe('')
    expect(walletEntryHref(undefined, undefined)).toBe('')
  })
})
