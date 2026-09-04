import { Tag } from 'antd'

const orderLabels: Record<string, string> = {
  ACCEPTED: '已受理',
  PROCESSING: '履约中',
  SUCCEEDED: '已成功',
  PARTIAL_SUCCEEDED: '部分成功',
  FAILED: '已失败',
  REMEDIATING: '补发中',
  REVERSING: '冲正中',
  REVERSED: '已冲正',
  PARTIALLY_REVERSED: '部分冲正',
}

const itemLabels: Record<string, string> = {
  PENDING: '待处理',
  RESERVED: '已预占',
  DISPATCHING: '下发中',
  SUCCEEDED: '已成功',
  FAILED_FINAL: '明确失败',
  UNKNOWN: '结果未知',
  QUERYING: '查询确认中',
  REISSUING: '补发中',
  REVERSING: '冲正中',
  REVERSED: '已冲正',
  REVERSAL_FAILED: '冲正失败',
  REVERSAL_UNKNOWN: '冲正未知',
}

const remediationLabels: Record<string, string> = {
  PROPOSED: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  DISPATCHING: '执行中',
  SUCCEEDED: '已成功',
  FAILED: '已失败',
  UNKNOWN: '结果未知',
}

const colors: Record<string, string> = {
  ACCEPTED: 'default',
  PROCESSING: 'processing',
  SUCCEEDED: 'success',
  PARTIAL_SUCCEEDED: 'warning',
  FAILED: 'error',
  REMEDIATING: 'processing',
  REVERSING: 'processing',
  REVERSED: 'purple',
  PARTIALLY_REVERSED: 'warning',
  PENDING: 'default',
  RESERVED: 'processing',
  DISPATCHING: 'processing',
  FAILED_FINAL: 'error',
  UNKNOWN: 'error',
  QUERYING: 'warning',
  REISSUING: 'processing',
  REVERSAL_FAILED: 'error',
  REVERSAL_UNKNOWN: 'warning',
  PROPOSED: 'warning',
  APPROVED: 'processing',
  REJECTED: 'default',
}

export function AwardOrderStatusTag({ status }: { status: string }) {
  return <Tag color={colors[status] || 'default'}>{orderLabels[status] || status}</Tag>
}

export function AwardItemStatusTag({ status }: { status: string }) {
  return <Tag color={colors[status] || 'default'}>{itemLabels[status] || status}</Tag>
}

export function RemediationStatusTag({ status }: { status: string }) {
  return <Tag color={colors[status] || 'default'}>{remediationLabels[status] || status}</Tag>
}

export function canRemediateItem(status?: string | null) {
  return status !== 'UNKNOWN' && status !== 'QUERYING'
}
