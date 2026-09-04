import { useQuery } from '@tanstack/react-query'
import { AlertOutlined, AppstoreOutlined, ClockCircleOutlined, ToolOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Col, Grid, Row, Space, Table } from 'antd'
import { useNavigate } from 'react-router-dom'
import { getAttentionOrders, getOverview } from '../api/benefit'
import { ErrorState, EmptyState, PageSkeleton } from '../components/common/AsyncState'
import { MetricCard } from '../components/common/MetricCard'
import { PageHeader } from '../components/common/PageHeader'
import { AwardOrderStatusTag } from '../components/common/StatusTag'
import type { AttentionOrder } from '../api/types'
import { errorMessage, formatCount, formatDateTime, truncatedHint } from '../utils/format'

export function DashboardPage() {
  const navigate = useNavigate()
  const screens = Grid.useBreakpoint()
  const overview = useQuery({
    queryKey: ['overview'],
    queryFn: getOverview,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  })
  const attention = useQuery({
    queryKey: ['attention-orders'],
    queryFn: () => getAttentionOrders(20),
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  })

  if (overview.isPending && !overview.data) return <PageSkeleton />
  if (overview.isError && !overview.data) {
    return <ErrorState message={errorMessage(overview.error)} onRetry={() => void overview.refetch()} />
  }

  const metrics = overview.data
  const orders = attention.data || []
  const emptyCatalog = metrics && metrics.skuCount === 0

  return (
    <>
      <PageHeader
        eyebrow="BENEFIT OPERATIONS"
        title="权益运营总览"
        description="先看未知履约和部分成功，再进入订单与补发。计数只统计当前租户，每 30 秒自动刷新。"
        extra={<Button type="primary" onClick={() => navigate('/catalog')}>配置目录</Button>}
      />

      {overview.isError && metrics && (
        <Alert type="warning" showIcon message="指标刷新失败，仍显示上次成功数据" style={{ marginBottom: 16 }} />
      )}

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard
            label="未知履约"
            value={formatCount(metrics?.unknownOps ?? 0)}
            hint="UNKNOWN / QUERYING 操作"
            icon={<AlertOutlined />}
            tone="error"
            actionLabel="查看订单"
            onAction={() => navigate('/orders')}
          />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard
            label="部分成功订单"
            value={formatCount(metrics?.partialOrders ?? 0)}
            hint="需要人工核对或补发"
            icon={<ClockCircleOutlined />}
            tone="warning"
            actionLabel="处理订单"
            onAction={() => navigate('/orders')}
          />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard
            label="待处理补发"
            value={formatCount(metrics?.pendingRemediations ?? 0)}
            hint="提议、批准或执行中"
            icon={<ToolOutlined />}
            tone="primary"
            actionLabel="补发处置"
            onAction={() => navigate('/remediations')}
          />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard
            label="可用路由"
            value={formatCount(metrics?.enabledRoutes ?? 0)}
            hint={`${formatCount(metrics?.skuCount ?? 0)} 个 SKU`}
            icon={<AppstoreOutlined />}
            tone="success"
            actionLabel="权益目录"
            onAction={() => navigate('/catalog')}
          />
        </Col>
      </Row>

      <Card
        className="dashboard-grid"
        title="需要关注的订单"
        extra={truncatedHint(orders) ? <span className="mobile-card-id">{truncatedHint(orders)}</span> : undefined}
      >
        {emptyCatalog ? (
          <EmptyState
            description="尚未配置 SKU。先建立目录和库存，工作台才会出现真实发放数据。"
            action={<Button type="primary" onClick={() => navigate('/catalog')}>去配置目录</Button>}
          />
        ) : attention.isError ? (
          <ErrorState message={errorMessage(attention.error)} onRetry={() => void attention.refetch()} />
        ) : screens.md ? (
          <Table<AttentionOrder>
            rowKey="orderNo"
            size="small"
            pagination={false}
            dataSource={orders}
            locale={{ emptyText: '当前没有需要关注的订单' }}
            columns={[
              {
                title: '订单号',
                dataIndex: 'orderNo',
                render: (value: string) => (
                  <Button type="link" className="table-link" onClick={() => navigate(`/orders?orderNo=${encodeURIComponent(value)}`)}>
                    {value}
                  </Button>
                ),
              },
              { title: '来源', dataIndex: 'sourceSystem', width: 140 },
              { title: '状态', dataIndex: 'status', width: 130, render: (status: string) => <AwardOrderStatusTag status={status} /> },
              { title: '原因', dataIndex: 'reason', width: 140 },
              { title: '更新时间', dataIndex: 'updatedAt', width: 180, render: formatDateTime },
            ]}
          />
        ) : (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {orders.map((order) => (
              <button
                className="mobile-data-card"
                key={order.orderNo}
                onClick={() => navigate(`/orders?orderNo=${encodeURIComponent(order.orderNo)}`)}
              >
                <div className="mobile-card-heading">
                  <strong>{order.orderNo}</strong>
                  <AwardOrderStatusTag status={order.status} />
                </div>
                <span className="mobile-card-id">{order.sourceSystem} · {order.sourceRequestId}</span>
                <div className="mobile-card-stats">
                  <span>{order.reason}</span>
                  <span>{formatDateTime(order.updatedAt)}</span>
                </div>
              </button>
            ))}
            {orders.length === 0 && <div className="chart-empty">当前没有需要关注的订单</div>}
          </Space>
        )}
      </Card>
    </>
  )
}
