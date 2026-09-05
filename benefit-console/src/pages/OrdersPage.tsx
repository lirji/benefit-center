import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Form, Grid, Input, Space, Table } from 'antd'
import { findAwardOrder, getAttentionOrders, getAwardOrder } from '../api/benefit'
import { ApiError } from '../api/client'
import type { AttentionOrder } from '../api/types'
import { EmptyState, ErrorState } from '../components/common/AsyncState'
import { PageHeader } from '../components/common/PageHeader'
import { AwardOrderStatusTag } from '../components/common/StatusTag'
import { OrderDetailDrawer } from '../components/orders/OrderDetailDrawer'
import { errorMessage, formatDateTime, truncatedHint } from '../utils/format'

export function OrdersPage() {
  const [params, setParams] = useSearchParams()
  const screens = Grid.useBreakpoint()
  const [orderNo, setOrderNo] = useState(params.get('orderNo') || '')
  const [sourceSystem, setSourceSystem] = useState(params.get('sourceSystem') || '')
  const [sourceRequestId, setSourceRequestId] = useState(params.get('sourceRequestId') || params.get('q') || '')
  const [lookupError, setLookupError] = useState('')
  const [activeOrder, setActiveOrder] = useState<string | null>(params.get('orderNo'))
  const autoLookup = useRef(false)

  const attention = useQuery({
    queryKey: ['attention-orders'],
    queryFn: () => getAttentionOrders(20),
  })

  useEffect(() => {
    const fromUrl = params.get('orderNo')
    if (fromUrl) {
      setActiveOrder(fromUrl)
      setOrderNo(fromUrl)
    }
  }, [params])

  useEffect(() => {
    if (autoLookup.current || params.get('orderNo')) return
    const system = (params.get('sourceSystem') || '').trim()
    const requestId = (params.get('sourceRequestId') || params.get('q') || '').trim()
    if (!system || !requestId) return
    autoLookup.current = true
    void lookup()
  }, [])

  async function lookup() {
    setLookupError('')
    try {
      if (orderNo.trim()) {
        const order = await getAwardOrder(orderNo.trim())
        setActiveOrder(order.orderNo)
        setParams({ orderNo: order.orderNo })
        return
      }
      if (sourceSystem.trim() && sourceRequestId.trim()) {
        const order = await findAwardOrder(sourceSystem.trim(), sourceRequestId.trim())
        setActiveOrder(order.orderNo)
        setParams({ orderNo: order.orderNo })
        return
      }
      setLookupError('请输入订单号，或同时填写来源系统与来源请求号。')
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        setLookupError('未找到该发放订单。请确认租户与单号后重试。')
        return
      }
      setLookupError(errorMessage(error))
    }
  }

  const orders = attention.data || []

  return (
    <>
      <PageHeader
        eyebrow="AWARD ORDERS"
        title="发放订单"
        description="按订单号或来源幂等键点查。本台不提供手工发奖，只查询 Drools / 上游已受理的订单。"
      />

      <Card className="filter-card" title="点查订单">
        <Form layout={screens.md ? 'inline' : 'vertical'} onFinish={() => void lookup()}>
          <Form.Item label="订单号">
            <Input value={orderNo} onChange={(e) => setOrderNo(e.target.value)} placeholder="ORD-..." allowClear />
          </Form.Item>
          <Form.Item label="来源系统">
            <Input value={sourceSystem} onChange={(e) => setSourceSystem(e.target.value)} placeholder="drools" />
          </Form.Item>
          <Form.Item label="来源请求号">
            <Input value={sourceRequestId} onChange={(e) => setSourceRequestId(e.target.value)} placeholder="sourceRequestId" />
          </Form.Item>
          <Form.Item className="filter-actions">
            <Button type="primary" htmlType="submit" aria-label="查询">查询</Button>
          </Form.Item>
        </Form>
        {lookupError && <Alert type={lookupError.includes('未找到') ? 'info' : 'error'} showIcon message={lookupError} style={{ marginTop: 12 }} />}
      </Card>

      <Card title="需要关注" extra={truncatedHint(orders)}>
        {attention.isError ? (
          <ErrorState message={errorMessage(attention.error)} onRetry={() => void attention.refetch()} />
        ) : screens.md ? (
          <Table<AttentionOrder>
            rowKey="orderNo"
            size="small"
            pagination={false}
            dataSource={orders}
            locale={{ emptyText: <EmptyState description="未搜索时，这里展示部分成功或未知履约的订单。" /> }}
            columns={[
              {
                title: '订单号',
                dataIndex: 'orderNo',
                render: (value: string) => (
                  <Button type="link" className="table-link" onClick={() => setActiveOrder(value)}>{value}</Button>
                ),
              },
              { title: '来源', dataIndex: 'sourceSystem' },
              { title: '状态', dataIndex: 'status', render: (s: string) => <AwardOrderStatusTag status={s} /> },
              { title: '更新时间', dataIndex: 'updatedAt', render: formatDateTime },
            ]}
          />
        ) : (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {orders.map((order) => (
              <button className="mobile-data-card" key={order.orderNo} onClick={() => setActiveOrder(order.orderNo)}>
                <div className="mobile-card-heading">
                  <strong>{order.orderNo}</strong>
                  <AwardOrderStatusTag status={order.status} />
                </div>
                <span className="mobile-card-id">{order.sourceSystem} · {order.sourceRequestId}</span>
              </button>
            ))}
            {orders.length === 0 && <EmptyState description="未搜索时，这里展示部分成功或未知履约的订单。" />}
          </Space>
        )}
      </Card>

      <OrderDetailDrawer orderNo={activeOrder} onClose={() => setActiveOrder(null)} />
    </>
  )
}
