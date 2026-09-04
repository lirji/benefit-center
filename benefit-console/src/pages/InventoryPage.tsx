import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Card, Form, Grid, Input, InputNumber, Modal, Select, Space, Table } from 'antd'
import { adjustInventory, importCodeAsset, listCodeAssets, listInventory } from '../api/benefit'
import type { CodeAssetView, InventoryView } from '../api/types'
import { EmptyState, ErrorState, PageSkeleton } from '../components/common/AsyncState'
import { PageHeader } from '../components/common/PageHeader'
import { errorMessage, formatCount, formatDateTime, truncatedHint } from '../utils/format'
import { useAuth } from '../auth/AuthContext'

export function InventoryPage() {
  const screens = Grid.useBreakpoint()
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const canWrite = useAuth().can('benefit.admin')
  const [adjustOpen, setAdjustOpen] = useState(false)
  const [importOpen, setImportOpen] = useState(false)
  const [adjustForm] = Form.useForm()
  const [importForm] = Form.useForm()

  const accounts = useQuery({ queryKey: ['inventory'], queryFn: () => listInventory(50) })
  const codes = useQuery({ queryKey: ['code-assets'], queryFn: () => listCodeAssets(undefined, 50) })

  const adjust = useMutation({
    mutationFn: adjustInventory,
    onSuccess: async () => {
      message.success('库存调整已受理')
      setAdjustOpen(false)
      adjustForm.resetFields()
      await queryClient.invalidateQueries({ queryKey: ['inventory'] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  const importCode = useMutation({
    mutationFn: importCodeAsset,
    onSuccess: async () => {
      message.success('码资产已导入（仅密文）')
      setImportOpen(false)
      importForm.resetFields()
      await queryClient.invalidateQueries({ queryKey: ['code-assets'] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  if (accounts.isPending) return <PageSkeleton />
  if (accounts.isError) return <ErrorState message={errorMessage(accounts.error)} onRetry={() => void accounts.refetch()} />

  const rows = accounts.data || []
  const codeRows = codes.data || []

  return (
    <>
      <PageHeader
        eyebrow="INVENTORY"
        title="库存中心"
        description="查看当前租户账户余额，并按幂等 requestId 调整配额。兑换码只展示哈希前 8 位，不接受明文。"
        extra={canWrite ? (
          <Space wrap>
            <Button onClick={() => setImportOpen(true)}>导入码资产</Button>
            <Button type="primary" aria-label="调整库存" onClick={() => setAdjustOpen(true)}>调整库存</Button>
          </Space>
        ) : undefined}
      />
      {!screens.md && <Alert type="info" showIcon message="目录和码池导入建议在桌面完成，窄屏仅适合查看余额。" style={{ marginBottom: 16 }} />}

      <Card title="库存账户" extra={truncatedHint(rows)} className="data-card">
        {screens.md ? (
          <Table<InventoryView>
            rowKey="accountId"
            size="small"
            pagination={false}
            dataSource={rows}
            locale={{ emptyText: <EmptyState description="未建账户。调整库存时会按 accountId 创建。" /> }}
            columns={[
              { title: '账户', dataIndex: 'accountId', render: (v: string) => <span className="mono">{v}</span> },
              { title: 'SKU', dataIndex: 'skuId' },
              { title: '归属', dataIndex: 'ownerType' },
              { title: '可用', dataIndex: 'available', render: formatCount },
              { title: '预占', dataIndex: 'reserved', render: formatCount },
              { title: '已发', dataIndex: 'issued', render: formatCount },
            ]}
          />
        ) : (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {rows.map((row) => (
              <div className="mobile-data-card" key={row.accountId}>
                <div className="mobile-card-heading">
                  <strong>{row.skuId}</strong>
                  <span>{row.ownerType}</span>
                </div>
                <span className="mono mobile-card-id">{row.accountId}</span>
                <div className="mobile-card-stats">
                  <span>可用 {row.available}</span>
                  <span>预占 {row.reserved}</span>
                  <span>已发 {row.issued}</span>
                </div>
              </div>
            ))}
            {rows.length === 0 && <EmptyState description="未建账户。调整库存时会按 accountId 创建。" />}
          </Space>
        )}
      </Card>

      <Card title="码资产（仅元数据）" extra={truncatedHint(codeRows)} style={{ marginTop: 16 }}>
        <Table<CodeAssetView>
          rowKey="codeAssetId"
          size="small"
          pagination={false}
          dataSource={codeRows}
          columns={[
            { title: '资产 ID', dataIndex: 'codeAssetId' },
            { title: 'SKU', dataIndex: 'skuId' },
            { title: '状态', dataIndex: 'status' },
            { title: '哈希前缀', dataIndex: 'codeHashPrefix', render: (v: string) => <span className="mono">{v}</span> },
            { title: '过期', dataIndex: 'expiresAt', render: formatDateTime },
          ]}
        />
      </Card>

      <Modal title="调整库存" open={adjustOpen} onCancel={() => setAdjustOpen(false)} footer={null} destroyOnHidden>
        <Form
          form={adjustForm}
          layout="vertical"
          onFinish={(values) => adjust.mutate(values)}
          initialValues={{ ownerType: 'CENTER_QUOTA', ownerId: 'platform', deltaAvailable: 10 }}
        >
          <Form.Item name="accountId" label="账户 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="skuId" label="SKU" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="ownerType" label="归属类型" rules={[{ required: true }]}>
            <Select options={[{ value: 'CENTER_QUOTA' }, { value: 'CENTER_STOCK' }]} />
          </Form.Item>
          <Form.Item name="ownerId" label="归属 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="deltaAvailable" label="可用增量" rules={[{ required: true, message: '请填写增量' }]}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="requestId" label="幂等请求号" rules={[{ required: true, message: 'requestId 必填' }]}>
            <Input placeholder="adj-..." />
          </Form.Item>
          <Button type="primary" htmlType="submit" aria-label="提交调整" loading={adjust.isPending} block style={{ minHeight: 44 }}>提交调整</Button>
        </Form>
      </Modal>

      <Modal title="导入加密码资产" open={importOpen} onCancel={() => setImportOpen(false)} footer={null} destroyOnHidden>
        <Alert type="warning" showIcon message="禁止填写明文兑换码。这里只接收哈希和密文。" style={{ marginBottom: 16 }} />
        <Form form={importForm} layout="vertical" onFinish={(values) => importCode.mutate(values)}>
          <Form.Item name="codeAssetId" label="资产 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="skuId" label="SKU" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="codeHash" label="codeHash" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="cipherText" label="cipherText" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item>
          <Form.Item name="keyVersion" label="密钥版本" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="expiresAt" label="过期时间"><Input placeholder="2026-12-01T00:00:00Z" /></Form.Item>
          <Button type="primary" htmlType="submit" loading={importCode.isPending} block style={{ minHeight: 44 }}>导入</Button>
        </Form>
      </Modal>
    </>
  )
}
