import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Card, Drawer, Form, Grid, Input, InputNumber, Select, Space, Switch, Table, Tabs } from 'antd'
import { getCurrentTenant, listRoutes, listSkus, saveRoute, saveSku, saveTenant } from '../api/benefit'
import type { RouteView, SkuView } from '../api/types'
import { EmptyState, ErrorState, PageSkeleton } from '../components/common/AsyncState'
import { PageHeader } from '../components/common/PageHeader'
import { errorMessage, formatMinor, truncatedHint } from '../utils/format'
import { useAuth } from '../auth/AuthContext'

export function CatalogPage() {
  const screens = Grid.useBreakpoint()
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const canWrite = useAuth().can('benefit.admin')
  const [skuOpen, setSkuOpen] = useState(false)
  const [routeOpen, setRouteOpen] = useState(false)
  const [editingSku, setEditingSku] = useState<SkuView | null>(null)
  const [editingRoute, setEditingRoute] = useState<RouteView | null>(null)
  const [skuForm] = Form.useForm()
  const [routeForm] = Form.useForm()
  const [tenantForm] = Form.useForm()
  const benefitType = Form.useWatch('benefitType', skuForm)

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
      message.success('SKU 已保存')
      setSkuOpen(false)
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

  if (skus.isPending) return <PageSkeleton />
  if (skus.isError) return <ErrorState message={errorMessage(skus.error)} onRetry={() => void skus.refetch()} />

  const skuRows = skus.data || []
  const routeRows = routes.data || []

  return (
    <>
      <PageHeader
        eyebrow="CATALOG"
        title="权益目录"
        description="维护当前租户、SKU 与渠道路由。路径 id 必须等于请求体 id。CASH 必须有面额和币种。"
        extra={canWrite ? (
          <Space wrap>
            <Button onClick={() => { setEditingSku(null); skuForm.resetFields(); skuForm.setFieldsValue({ enabled: true, benefitType: 'COUPON' }); setSkuOpen(true) }}>新建 SKU</Button>
            <Button type="primary" onClick={() => { setEditingRoute(null); routeForm.resetFields(); routeForm.setFieldsValue({ enabled: true, priority: 1, reserveMode: 'LAZY', ownerType: 'CENTER_QUOTA' }); setRouteOpen(true) }}>新建路由</Button>
          </Space>
        ) : undefined}
      />
      {!screens.md && <Alert type="info" showIcon message="建议在桌面完成目录配置，窄屏表单仍可单列提交。" style={{ marginBottom: 16 }} />}

      <Tabs
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
            label: 'SKU',
            children: (
              <Card extra={truncatedHint(skuRows)}>
                {screens.md ? (
                  <Table<SkuView>
                    rowKey="skuId"
                    size="small"
                    pagination={false}
                    dataSource={skuRows}
                    locale={{ emptyText: <EmptyState description="尚未创建 SKU" /> }}
                    columns={[
                      { title: 'SKU', dataIndex: 'skuId' },
                      { title: '类型', dataIndex: 'benefitType' },
                      { title: '面额', render: (_, row) => formatMinor(row.faceValueMinor, row.currency) },
                      { title: '状态', render: (_, row) => row.enabled ? '启用' : '停用' },
                      {
                        title: '操作',
                        render: (_, row) => canWrite ? <Button type="link" onClick={() => { setEditingSku(row); skuForm.setFieldsValue(row); setSkuOpen(true) }}>编辑</Button> : null,
                      },
                    ]}
                  />
                ) : (
                  <Space direction="vertical" size={12} style={{ width: '100%' }}>
                    {skuRows.map((row) => (
                      <button className="mobile-data-card" key={row.skuId} onClick={() => { if (!canWrite) return; setEditingSku(row); skuForm.setFieldsValue(row); setSkuOpen(true) }}>
                        <div className="mobile-card-heading"><strong>{row.skuId}</strong><span>{row.benefitType}</span></div>
                      </button>
                    ))}
                    {skuRows.length === 0 && <EmptyState description="尚未创建 SKU" />}
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

      <Drawer title={editingSku ? '编辑 SKU' : '新建 SKU'} width={screens.md ? 480 : '100%'} open={skuOpen} onClose={() => setSkuOpen(false)}>
        <Form
          form={skuForm}
          layout="vertical"
          onFinish={(values) => saveSkuMut.mutate({
            ...values,
            expectedVersion: editingSku?.version,
            faceValueMinor: values.benefitType === 'CASH' ? values.faceValueMinor : null,
            currency: values.benefitType === 'CASH' ? values.currency : null,
          })}
        >
          <Form.Item name="skuId" label="SKU ID" rules={[{ required: true }]}>
            <Input disabled={Boolean(editingSku)} />
          </Form.Item>
          <Form.Item name="benefitType" label="类型" rules={[{ required: true }]}>
            <Select options={['CASH', 'COUPON', 'SERVICE_VOUCHER', 'REDEMPTION_CODE', 'PHYSICAL'].map((v) => ({ value: v }))} />
          </Form.Item>
          {benefitType === 'CASH' ? (
            <>
              <Form.Item name="faceValueMinor" label="面额（最小单位）" rules={[{ required: true, message: 'CASH 必须填写面额' }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="currency" label="币种" rules={[{ required: true, message: 'CASH 必须填写币种' }]}>
                <Input maxLength={3} placeholder="CNY" />
              </Form.Item>
            </>
          ) : (
            <Alert type="info" showIcon message="非现金 SKU 不能携带金额或币种。" style={{ marginBottom: 16 }} />
          )}
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
          <Button type="primary" htmlType="submit" loading={saveSkuMut.isPending} block style={{ minHeight: 44 }}>保存</Button>
        </Form>
      </Drawer>

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
