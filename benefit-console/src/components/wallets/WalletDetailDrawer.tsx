import { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { App, Button, Descriptions, Drawer, Grid, Input, Modal, Space } from 'antd'
import { commandWalletEntry } from '../../api/benefit'
import type { WalletEntryCommandAcceptance, WalletEntryView } from '../../api/types'
import { WalletEntryStatusTag } from '../common/StatusTag'
import { errorMessage, formatDateTime, formatMinor } from '../../utils/format'
import { walletActionsFor, walletCommandKey, type WalletActionSpec } from './walletActions'

export function WalletDetailDrawer({
  entry,
  canWrite,
  onClose,
  onAccepted,
}: {
  entry: WalletEntryView | null
  canWrite: boolean
  onClose: () => void
  onAccepted?: (acceptance: WalletEntryCommandAcceptance) => void
}) {
  const screens = Grid.useBreakpoint()
  const { message } = App.useApp()
  const [pending, setPending] = useState<WalletActionSpec | null>(null)
  const [reason, setReason] = useState('')
  const actions = entry && canWrite ? walletActionsFor(entry.status) : []

  useEffect(() => {
    setPending(null)
    setReason('')
  }, [entry?.entryId])

  const command = useMutation({
    mutationFn: () => {
      if (!entry || !pending) throw new Error('没有可提交的动作')
      return commandWalletEntry(
        entry.entryId,
        pending.action,
        {
          expectedVersion: entry.version,
          ...(reason.trim() ? { reason: reason.trim() } : {}),
        },
        walletCommandKey(pending.action, entry.entryId, entry.version),
      )
    },
    onSuccess: (acceptance) => {
      message.info('已受理')
      setPending(null)
      setReason('')
      onAccepted?.(acceptance)
    },
    onError: (error) => {
      message.error(errorMessage(error))
    },
  })

  return (
    <>
      <Drawer
        title="券包条目"
        width={screens.md ? 480 : '100%'}
        open={Boolean(entry)}
        onClose={onClose}
        footer={actions.length ? (
          <Space className="wallet-entry-actions" wrap>
            {actions.map((spec) => (
              <Button
                key={spec.action}
                type={spec.action === 'freeze' ? 'default' : 'primary'}
                danger={spec.action === 'refund'}
                aria-label={spec.label}
                disabled={command.isPending}
                onClick={() => setPending(spec)}
              >
                {spec.label}
              </Button>
            ))}
          </Space>
        ) : null}
      >
        {entry && (
          <Descriptions column={1} size="small">
            <Descriptions.Item label="条目">{entry.entryId}</Descriptions.Item>
            <Descriptions.Item label="用户">{entry.subjectRef}</Descriptions.Item>
            <Descriptions.Item label="SKU">{entry.skuId} · v{entry.skuVersion}</Descriptions.Item>
            <Descriptions.Item label="类型">{entry.assetType}</Descriptions.Item>
            <Descriptions.Item label="状态"><WalletEntryStatusTag status={entry.status} /></Descriptions.Item>
            <Descriptions.Item label="版本">{entry.version}</Descriptions.Item>
            <Descriptions.Item label="面额">{formatMinor(entry.faceValueMinor, entry.currency)}</Descriptions.Item>
            <Descriptions.Item label="过期">{formatDateTime(entry.expiresAt)}</Descriptions.Item>
            <Descriptions.Item label="发放订单">{entry.awardOrderNo}</Descriptions.Item>
            <Descriptions.Item label="子项">{entry.itemNo}</Descriptions.Item>
            <Descriptions.Item label="入账时间">{formatDateTime(entry.createdAt)}</Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
      <Modal
        title={pending?.confirmTitle}
        open={Boolean(pending)}
        okText="确认提交"
        confirmLoading={command.isPending}
        onCancel={() => {
          if (!command.isPending) setPending(null)
        }}
        onOk={() => command.mutate()}
        destroyOnHidden
      >
        <p>{pending?.confirmHint}</p>
        <Input.TextArea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="原因（可选）"
          maxLength={512}
          rows={3}
        />
      </Modal>
    </>
  )
}
