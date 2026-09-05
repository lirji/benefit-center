import { describe, expect, it } from 'vitest'
import { validateTenantSelection } from './tenantSelection'

describe('tenant selection', () => {
  const organization = 'benefit-center'
  const clientId = 'ragshared0client00000001-org-benefit-center'

  it('accepts only the configured organization and matching derived client', () => {
    expect(validateTenantSelection(' benefit-center ', organization, clientId)).toEqual({
      ok: true,
      organization,
    })
  })

  it('rejects an empty or unknown organization', () => {
    expect(validateTenantSelection('', organization, clientId)).toEqual({ ok: false, message: '请输入所属组织' })
    expect(validateTenantSelection('other', organization, clientId)).toEqual({
      ok: false,
      message: '组织 other 未开放。当前可用组织：benefit-center',
    })
  })

  it('fails closed when the configured client does not belong to the organization', () => {
    expect(validateTenantSelection(organization, organization, 'unexpected-client')).toEqual({
      ok: false,
      message: '统一登录配置与组织不匹配，请联系管理员',
    })
  })
})
