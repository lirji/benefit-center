import type { SkuTemplateStatus } from '../../api/types'

export function canEditSkuFields(status?: SkuTemplateStatus | null): boolean {
  return status == null || status === 'DRAFT' || status === 'ACTIVE' || status === 'PAUSED'
}

export function canSubmitSkuApproval(status?: SkuTemplateStatus | null): boolean {
  return status === 'DRAFT'
}

export function operationalStatusOptions(status: SkuTemplateStatus): { value: SkuTemplateStatus; label: string }[] | null {
  if (status === 'ACTIVE') {
    return [
      { value: 'ACTIVE', label: '已投放' },
      { value: 'PAUSED', label: '已暂停' },
    ]
  }
  if (status === 'PAUSED') {
    return [
      { value: 'ACTIVE', label: '已投放' },
      { value: 'PAUSED', label: '已暂停' },
      { value: 'RETIRED', label: '已下线' },
    ]
  }
  return null
}
