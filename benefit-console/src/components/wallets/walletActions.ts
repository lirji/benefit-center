import type { WalletEntryAction } from '../../api/benefit'
import type { WalletEntryStatus } from '../../api/types'

export type WalletActionSpec = {
  action: WalletEntryAction
  label: string
  confirmTitle: string
  confirmHint: string
}

export function walletActionsFor(status: WalletEntryStatus): WalletActionSpec[] {
  if (status === 'UNUSED') {
    return [
      {
        action: 'freeze',
        label: '冻结',
        confirmTitle: '确认冻结这张券？',
        confirmHint: '冻结后仍可核销，不能解冻。按入账面额记账，不读当前模板。',
      },
      {
        action: 'redeem',
        label: '核销',
        confirmTitle: '确认核销这张券？',
        confirmHint: '按入账面额记账，不读当前模板。提交后不可撤销。',
      },
    ]
  }
  if (status === 'FROZEN') {
    return [
      {
        action: 'redeem',
        label: '核销',
        confirmTitle: '确认核销这张券？',
        confirmHint: '按入账面额记账，不读当前模板。提交后不可撤销。',
      },
    ]
  }
  if (status === 'USED') {
    return [
      {
        action: 'refund',
        label: '退券',
        confirmTitle: '确认退券？',
        confirmHint: '这是退已核销的用户资产，不是履约冲正。',
      },
    ]
  }
  return []
}

export function walletCommandKey(action: WalletEntryAction, entryId: string, version: number) {
  return `${action}:${entryId}:${version}`
}
