import { describe, expect, it } from 'vitest'
import { walletActionsFor, walletCommandKey } from './walletActions'

describe('walletActionsFor', () => {
  it('lets unused entries freeze or redeem', () => {
    expect(walletActionsFor('UNUSED').map((item) => item.action)).toEqual(['freeze', 'redeem'])
  })

  it('lets frozen entries redeem only', () => {
    expect(walletActionsFor('FROZEN').map((item) => item.action)).toEqual(['redeem'])
  })

  it('lets used entries refund as 退券', () => {
    const actions = walletActionsFor('USED')
    expect(actions.map((item) => item.action)).toEqual(['refund'])
    expect(actions[0]?.label).toBe('退券')
    expect(actions[0]?.confirmHint).toMatch(/不是履约冲正/)
  })

  it('hides writes on expired or reversed entries', () => {
    expect(walletActionsFor('EXPIRED')).toEqual([])
    expect(walletActionsFor('REVERSED')).toEqual([])
  })

  it('builds a business idempotency key from action, entry and version', () => {
    expect(walletCommandKey('redeem', 'WE-1', 0)).toBe('redeem:WE-1:0')
  })
})
