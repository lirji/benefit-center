import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Form, Grid, Input, Select, Space, Table } from 'antd'
import { ApiError } from '../api/client'
import { getWallet, listWalletEntries } from '../api/benefit'
import type { WalletEntryCommandAcceptance, WalletEntryView } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { EmptyState, ErrorState } from '../components/common/AsyncState'
import { PageHeader } from '../components/common/PageHeader'
import { WalletEntryStatusTag } from '../components/common/StatusTag'
import { WalletDetailDrawer } from '../components/wallets/WalletDetailDrawer'
import { errorMessage, formatDateTime, formatMinor, truncatedHint } from '../utils/format'

const STATUS_OPTIONS = [
  { value: 'UNUSED', label: '未使用' },
  { value: 'FROZEN', label: '已冻结' },
  { value: 'USED', label: '已使用' },
  { value: 'EXPIRED', label: '已过期' },
  { value: 'REVERSED', label: '已冲正' },
]

export function WalletsPage() {
  const screens = Grid.useBreakpoint()
  const canWrite = useAuth().can('benefit.admin')
  const [params, setParams] = useSearchParams()
  const [subject, setSubject] = useState(params.get('subject') || '')
  const [status, setStatus] = useState<string | undefined>()
  const [skuId, setSkuId] = useState('')
  const [submitted, setSubmitted] = useState(params.get('subject') || '')
  const [selected, setSelected] = useState<WalletEntryView | null>(null)

  useEffect(() => {
    const fromUrl = params.get('subject') || ''
    setSubject(fromUrl)
    setSubmitted(fromUrl)
  }, [params])

  const queryEnabled = submitted.length > 0
  const wallet = useQuery({
    queryKey: ['wallet', submitted],
    queryFn: () => getWallet(submitted),
    enabled: queryEnabled,
    retry: false,
  })
  const entries = useQuery({
    queryKey: ['wallet-entries', submitted, status, skuId],
    queryFn: () => listWalletEntries(submitted, { status, skuId: skuId || undefined, limit: 50 }),
    enabled: queryEnabled,
    retry: false,
  })

  function search() {
    const next = subject.trim()
    if (!next) return
    if (next.length > 256) return
    setSubmitted(next)
    setParams({ subject: next })
  }

  const notFound = wallet.isError && wallet.error instanceof ApiError && wallet.error.status === 404
  const rows = entries.data || []

  useEffect(() => {
    const entryId = params.get('entry')
    if (!entryId || selected?.entryId === entryId) return
    const match = rows.find((row) => row.entryId === entryId)
    if (match) setSelected(match)
  }, [params, rows, selected])

  function closeDrawer() {
    setSelected(null)
    if (!params.get('entry')) return
    const next = new URLSearchParams(params)
    next.delete('entry')
    setParams(next, { replace: true })
  }

  async function handleAccepted(acceptance: WalletEntryCommandAcceptance) {
    setSelected((current) => (
      current && current.entryId === acceptance.entryId
        ? { ...current, status: acceptance.status, version: acceptance.version }
        : current
    ))
    const [refreshed] = await Promise.all([entries.refetch(), wallet.refetch()])
    const next = refreshed.data?.find((row) => row.entryId === acceptance.entryId)
    if (next) setSelected(next)
  }

  return (
    <>
      <PageHeader
        eyebrow="WALLET"
        title="用户资产"
        description="按用户稳定引用点查券包与红包余额。不会拉全量用户。"
      />
      <Card style={{ marginBottom: 16 }}>
        <Form layout={screens.md ? 'inline' : 'vertical'} onFinish={search}>
          <Form.Item
            label="用户引用"
            validateStatus={subject.length > 256 ? 'error' : undefined}
            help={subject.length > 256 ? '最长 256 个字符' : undefined}
          >
            <Input
              value={subject}
              onChange={(event) => setSubject(event.target.value)}
              placeholder="用户稳定引用，如 Casdoor sub 或营销 subject token"
              maxLength={256}
              allowClear
              style={{ minWidth: screens.md ? 360 : '100%' }}
            />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" aria-label="查询" disabled={!subject.trim()} style={{ minHeight: 44 }}>查询</Button>
          </Form.Item>
        </Form>
      </Card>

      {!queryEnabled && <EmptyState description="输入用户稳定引用后查询券包，不会预拉全量用户。" />}

      {queryEnabled && wallet.isError && !notFound && wallet.error instanceof ApiError && wallet.error.status === 400 && (
        <Alert type="error" showIcon message={errorMessage(wallet.error)} style={{ marginBottom: 16 }} />
      )}
      {queryEnabled && wallet.isError && !notFound && !(wallet.error instanceof ApiError && wallet.error.status === 400) && (
        <ErrorState message={errorMessage(wallet.error)} onRetry={() => void wallet.refetch()} />
      )}
      {queryEnabled && notFound && <EmptyState description="该用户暂无权益" />}

      {queryEnabled && wallet.data && (
        <>
          <Space wrap size={16} style={{ marginBottom: 16 }}>
            <span>条目 {wallet.data.totalEntries}</span>
            <span>未使用 {wallet.data.unusedEntries}</span>
            {wallet.data.cashBalances.map((item) => (
              <span key={item.currency}>{item.currency} 余额 {formatMinor(item.balanceMinor, item.currency)}</span>
            ))}
          </Space>
          <Card
            title="券包条目"
            extra={(
              <Space wrap>
                <Select
                  allowClear
                  placeholder="状态"
                  options={STATUS_OPTIONS}
                  value={status}
                  onChange={setStatus}
                  style={{ minWidth: 120 }}
                />
                <Input
                  allowClear
                  placeholder="SKU"
                  value={skuId}
                  onChange={(event) => setSkuId(event.target.value)}
                  style={{ width: 160 }}
                />
                {truncatedHint(rows)}
              </Space>
            )}
          >
            {entries.isError ? (
              <ErrorState message={errorMessage(entries.error)} onRetry={() => void entries.refetch()} />
            ) : screens.md ? (
              <Table<WalletEntryView>
                rowKey="entryId"
                size="small"
                pagination={false}
                dataSource={rows}
                locale={{ emptyText: <EmptyState description="该用户暂无权益" /> }}
                columns={[
                  { title: '条目', dataIndex: 'entryId', render: (value: string) => <span className="mono">{value}</span> },
                  { title: 'SKU', dataIndex: 'skuId' },
                  { title: '类型', dataIndex: 'assetType' },
                  { title: '状态', dataIndex: 'status', render: (value: string) => <WalletEntryStatusTag status={value} /> },
                  { title: '面额', render: (_, row) => formatMinor(row.faceValueMinor, row.currency) },
                  { title: '过期', dataIndex: 'expiresAt', render: formatDateTime },
                  {
                    title: '操作',
                    render: (_, row) => <Button type="link" onClick={() => setSelected(row)}>详情</Button>,
                  },
                ]}
              />
            ) : (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                {rows.map((row) => (
                  <button className="mobile-data-card" key={row.entryId} onClick={() => setSelected(row)}>
                    <div className="mobile-card-heading">
                      <strong>{row.skuId}</strong>
                      <WalletEntryStatusTag status={row.status} />
                    </div>
                    <span className="mono mobile-card-id">{row.entryId}</span>
                    <div className="mobile-card-stats">
                      <span>{formatMinor(row.faceValueMinor, row.currency)}</span>
                      <span>{formatDateTime(row.expiresAt)}</span>
                    </div>
                  </button>
                ))}
                {rows.length === 0 && <EmptyState description="该用户暂无权益" />}
              </Space>
            )}
          </Card>
        </>
      )}

      <WalletDetailDrawer
        entry={selected}
        canWrite={canWrite}
        onClose={closeDrawer}
        onAccepted={handleAccepted}
      />
    </>
  )
}
