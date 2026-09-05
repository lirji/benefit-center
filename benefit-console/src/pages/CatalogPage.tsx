import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Card, Drawer, Form, Grid, Input, InputNumber, Select, Space, Switch, Table, Tabs } from 'antd'
import { getCurrentTenant, listRoutes, listSkus, saveRoute, saveSku, saveTenant, submitSkuApproval } from '../api/benefit'
import type { RouteView, SkuView } from '../api/types'
import { SkuDrawer } from '../components/catalog/SkuDrawer'
import { EmptyState, ErrorState, PageSkeleton } from '../components/common/AsyncState'
import { PageHeader } from '../components/common/PageHeader'
import { SkuStatusTag } from '../components/common/StatusTag'
import { errorMessage, formatMinor, toDatetimeLocal, truncatedHint } from '../utils/format'
import { useAuth } from '../auth/AuthContext'

function validityLabel(row: SkuView) {
  if (row.validityType === 'RELATIVE') return row.relativeDays ? `领取后 ${row.relativeDays} 天` : '相对有效期'
  const from = row.validFrom ? toDatetimeLocal(row.validFrom) : '—'
  const to = row.validTo ? toDatetimeLocal(row.validTo) : '—'
  return `${from} ~ ${to}`
}

export function CatalogPage() {
  const screens = Grid.useBreakpoint()
  const [params] = useSearchParams()
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const canWrite = useAuth().can('benefit.admin')
  const [skuOpen, setSkuOpen] = useState(false)
  const [routeOpen, setRouteOpen] = useState(false)
  const [editingSku, setEditingSku] = useState<SkuView | null>(null)
  const [editingRoute, setEditingRoute] = useState<RouteView | null>(null)
  const [activeTab, setActiveTab] = useState(params.get('skuId') ? 'skus' : 'tenant')
  const [missingSku, setMissingSku] = useState(false)
  const [routeForm] = Form.useForm()
  const [tenantForm] = Form.useForm()
  const openedFromUrl = useRef(false)

  const tenant = useQuery({
    queryKey: ['tenant-current'],
    queryFn: getCurrentTenant,
    retry: false,
  })
  const skus = useQuery({ queryKey: ['skus'], queryFn: () => listSkus(50) })
  const routes = useQuery({ queryKey: ['routes'], queryFn: () => listRoutes(undefined, 50) })

  const saveTenantMut = useMutation({
    mutationFn: (body: { tenantId: string; homeCell: string; enabled: boolean; expectedVersion?: number }) =>
      saveTenant(body.tenantId, body),
    onSuccess: async () => {
      message.success('租户已保存')
      await queryClient.invalidateQueries({ queryKey: ['tenant-current'] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })
  const saveSkuMut = useMutation({
    mutationFn: (body: Record<string, unknown> & { skuId: string }) => saveSku(body.skuId, body),
    onSuccess: async () => {
      message.success('模板已保存')
      setSkuOpen(false)
      await queryClient.invalidateQueries({ queryKey: ['skus'] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })
  const submitSkuMut = useMutation({
    mutationFn: () => {
      if (!editingSku) throw new Error('请先保存草稿')
      return submitSkuApproval(editingSku.skuId, { expectedVersion: editingSku.version })
    },
    onSuccess: async (acceptance) => {
      message.info('已提交审批')
      const next: SkuView = {
        ...editingSku!,
        status: acceptance.status,
        version: acceptance.version,
        approvalProcessDefinitionKey: 'benefitSkuGoLive',
        approvalBusinessKey: acceptance.skuId,
      }
      setEditingSku(next)
      await queryClient.invalidateQueries({ queryKey: ['skus'] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })
  const saveRouteMut = useMutation({
    mutationFn: (body: Record<string, unknown> & { routeId: string }) => saveRoute(body.routeId, body),
    onSuccess: async () => {
      message.success('路由已保存')
      setRouteOpen(false)
      await queryClient.invalidateQueries({ queryKey: ['routes'] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  const skuRows = skus.data || []
  const routeRows = routes.data || []

  function openSku(row?: SkuView) {
    setEditingSku(row ?? null)
    setSkuOpen(true)
  }

  useEffect(() => {
    const skuId = params.get('skuId')
    if (!skuId || skus.isPending || openedFromUrl.current) return
    openedFromUrl.current = true
    setActiveTab('skus')
    const row = skuRows.find((item) => item.skuId === skuId)
    if (row) {
      setMissingSku(false)
      openSku(row)
    } else {
      setMissingSku(true)
    }
  }, [params, skuRows, skus.isPending])

  if (skus.isPending) return <PageSkeleton />
  if (skus.isError) return <ErrorState message={errorMessage(skus.error)} onRetry={() => void skus.refetch()} />

  return (
    <>
      <PageHeader
        eyebrow="CATALOG"
        title="权益目录"
        description="维护模板状态、有效期与用户限额。草稿需提交上线审批后才会投放。门槛与互斥在营销活动里配置。"
        extra={canWrite ? (
          <Space wrap>
            <Button onClick={() => openSku()}>新建模板</Button>
            <Button type="primary" onClick={() => { setEditingRoute(null); routeForm.resetFields(); routeForm.setFieldsValue({ enabled: true, priority: 1, reserveMode: 'LAZY', ownerType: 'CENTER_QUOTA' }); setRouteOpen(true) }}>新建路由</Button>
          </Space>
        ) : undefined}
      />
      {!screens.md && <Alert type="info" showIcon message="建议在桌面完成目录配置，窄屏表单仍可单列提交。" style={{ marginBottom: 16 }} />}
      {missingSku && <Alert type="warning" showIcon message="未找到该模板" style={{ marginBottom: 16 }} />}

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'tenant',
            label: '租户',
            children: (
              <Card>
                {tenant.isError && <Alert type="info" showIcon message="当前租户尚未配置，可在此创建。" style={{ marginBottom: 16 }} />}
                <Form
                  form={tenantForm}
                  layout="vertical"
                  initialValues={tenant.data || { tenantId: '', homeCell: 'cell-0', enabled: true }}
                  key={tenant.data?.tenantId || 'new'}
                  onFinish={(values) => saveTenantMut.mutate({
                    ...values,
                    expectedVersion: tenant.data?.version,
                  })}
                >
                  <Form.Item name="tenantId" label="租户 ID" rules={[{ required: true }]}>
                    <Input disabled={Boolean(tenant.data)} />
                  </Form.Item>
                  <Form.Item name="homeCell" label="Home Cell" rules={[{ required: true }]}><Input /></Form.Item>
                  <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
                  <Button type="primary" htmlType="submit" loading={saveTenantMut.isPending} disabled={!canWrite} style={{ minHeight: 44 }}>保存租户</Button>
                </Form>
              </Card>
            ),
          },
          {
            key: 'skus',
            label: '模板',
            children: (
              <Card extra={truncatedHint(skuRows)}>
                {screens.md ? (
                  <Table<SkuView>
                    rowKey="skuId"
                    size="small"
                    pagination={false}
                    dataSource={skuRows}
                    locale={{ emptyText: <EmptyState description="尚未创建模板" /> }}
                    columns={[
                      { title: 'SKU', dataIndex: 'skuId' },
                      { title: '类型', dataIndex: 'benefitType' },
                      { title: '面额', render: (_, row) => formatMinor(row.faceValueMinor, row.currency) },
                      { title: '状态', render: (_, row) => <SkuStatusTag status={row.status} /> },
                      { title: '有效期', render: (_, row) => validityLabel(row) },
                      {
                        title: '操作',
                        render: (_, row) => (
                          <Button type="link" onClick={() => openSku(row)}>
                            {canWrite && row.status !== 'PENDING_APPROVAL' && row.status !== 'RETIRED' ? '编辑' : '查看'}
                          </Button>
                        ),
                      },
                    ]}
                  />
                ) : (
                  <Space direction="vertical" size={12} style={{ width: '100%' }}>
                    {skuRows.map((row) => (
                      <button className="mobile-data-card" key={row.skuId} onClick={() => openSku(row)}>
                        <div className="mobile-card-heading"><strong>{row.skuId}</strong><SkuStatusTag status={row.status} /></div>
                        <span className="mobile-card-id">{row.benefitType} · {validityLabel(row)}</span>
                      </button>
                    ))}
                    {skuRows.length === 0 && <EmptyState description="尚未创建模板" />}
                  </Space>
                )}
              </Card>
            ),
          },
          {
            key: 'routes',
            label: '路由',
            children: (
              <Card extra={truncatedHint(routeRows)}>
                <Table<RouteView>
                  rowKey="routeId"
                  size="small"
                  pagination={false}
                  dataSource={routeRows}
                  locale={{ emptyText: <EmptyState description="尚未创建路由" /> }}
                  columns={[
                    { title: '路由', dataIndex: 'routeId' },
                    { title: 'SKU', dataIndex: 'skuId' },
                    { title: '渠道', dataIndex: 'channelCode' },
                    { title: '优先级', dataIndex: 'priority' },
                    { title: '状态', render: (_, row) => row.enabled ? '启用' : '停用' },
                    {
                      title: '操作',
                      render: (_, row) => canWrite ? <Button type="link" onClick={() => { setEditingRoute(row); routeForm.setFieldsValue(row); setRouteOpen(true) }}>编辑</Button> : null,
                    },
                  ]}
                />
              </Card>
            ),
          },
        ]}
      />

      <SkuDrawer
        open={skuOpen}
        sku={editingSku}
        canWrite={canWrite}
        saving={saveSkuMut.isPending}
        submitting={submitSkuMut.isPending}
        wide={Boolean(screens.md)}
        onClose={() => setSkuOpen(false)}
        onSave={(body) => saveSkuMut.mutate(body)}
        onSubmitApproval={() => submitSkuMut.mutate()}
      />

      <Drawer title={editingRoute ? '编辑路由' : '新建路由'} width={screens.md ? 480 : '100%'} open={routeOpen} onClose={() => setRouteOpen(false)}>
        <Form form={routeForm} layout="vertical" onFinish={(values) => saveRouteMut.mutate({ ...values, expectedVersion: editingRoute?.version })}>
          <Form.Item name="routeId" label="路由 ID" rules={[{ required: true }]}><Input disabled={Boolean(editingRoute)} /></Form.Item>
          <Form.Item name="skuId" label="SKU" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="priority" label="优先级" rules={[{ required: true }]}><InputNumber min={1} style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="channelCode" label="渠道" rules={[{ required: true }]}><Input placeholder="CENTER_CODE" /></Form.Item>
          <Form.Item name="ownerType" label="库存归属" rules={[{ required: true }]}>
            <Select options={[{ value: 'CENTER_QUOTA' }, { value: 'CENTER_STOCK' }]} />
          </Form.Item>
          <Form.Item name="reserveMode" label="预占模式" rules={[{ required: true }]}>
            <Select options={[{ value: 'LAZY' }, { value: 'EAGER' }]} />
          </Form.Item>
          <Form.Item name="fallbackRouteId" label="回退路由"><Input /></Form.Item>
          <Form.Item name="configRef" label="配置引用"><Input /></Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
          <Button type="primary" htmlType="submit" loading={saveRouteMut.isPending} block style={{ minHeight: 44 }}>保存</Button>
        </Form>
      </Drawer>
    </>
  )
}
