import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Descriptions, Drawer, Grid, Space, Table, Timeline, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { getAwardOrder } from '../../api/benefit'
import type { AwardItem } from '../../api/types'
import { AwardItemStatusTag, AwardOrderStatusTag, canRemediateItem } from '../common/StatusTag'
import { formatDateTime, formatMinor } from '../../utils/format'

export function OrderDetailDrawer({ orderNo, onClose }: { orderNo: string | null; onClose: () => void }) {
  const screens = Grid.useBreakpoint()
  const navigate = useNavigate()
  const query = useQuery({
    queryKey: ['award-order', orderNo],
    queryFn: () => getAwardOrder(orderNo!),
    enabled: Boolean(orderNo),
  })

  return (
    <Drawer
      title="发放订单详情"
      width={screens.md ? 720 : '100%'}
      open={Boolean(orderNo)}
      onClose={onClose}
    >
      {query.isError && <Alert type="error" showIcon message={query.error.message} />}
      {query.data && (
        <>
          <div className="detail-hero">
            <div>
              <Typography.Text type="secondary">订单</Typography.Text>
              <h2 className="mono">{query.data.orderNo}</h2>
              <div className="mono">{query.data.sourceSystem} / {query.data.sourceRequestId}</div>
            </div>
            <AwardOrderStatusTag status={query.data.status} />
          </div>
          <Descriptions column={screens.md ? 2 : 1} size="small" style={{ marginTop: 16 }}>
            <Descriptions.Item label="业务单号">{query.data.sourceBusinessNo || '—'}</Descriptions.Item>
            <Descriptions.Item label="归属 Cell">{query.data.homeCell}</Descriptions.Item>
          </Descriptions>
          <h3 className="section-title">子项</h3>
          {screens.md ? (
            <Table<AwardItem>
              rowKey="itemNo"
              size="small"
              pagination={false}
              dataSource={query.data.items}
              columns={[
                { title: '子项', dataIndex: 'itemNo', render: (v: string) => <span className="mono">{v}</span> },
                { title: 'SKU', dataIndex: 'skuId' },
                { title: '状态', dataIndex: 'status', render: (s: string) => <AwardItemStatusTag status={s} /> },
                { title: '金额', render: (_, item) => formatMinor(item.amountMinor, item.currency) },
                {
                  title: '处置',
                  render: (_, item) => (
                    <RemediateAction item={item} onClose={onClose} navigate={navigate} />
                  ),
                },
              ]}
            />
          ) : (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {query.data.items.map((item) => (
                <div className="mobile-data-card" key={item.itemNo}>
                  <div className="mobile-card-heading">
                    <strong>{item.skuId}</strong>
                    <AwardItemStatusTag status={item.status} />
                  </div>
                  <span className="mono mobile-card-id">{item.itemNo}</span>
                  <RemediateAction item={item} onClose={onClose} navigate={navigate} />
                </div>
              ))}
            </Space>
          )}
          <h3 className="section-title">履约时间线</h3>
          <Timeline
            items={query.data.items.map((item) => ({
              children: (
                <div className="timeline-item">
                  <strong>{item.skuId}</strong>
                  <span>{item.latestOperationNo || '尚无 operation'}</span>
                  <span>{formatDateTime(undefined)}</span>
                </div>
              ),
            }))}
          />
        </>
      )}
    </Drawer>
  )
}

function RemediateAction({
  item,
  onClose,
  navigate,
}: {
  item: AwardItem
  onClose: () => void
  navigate: ReturnType<typeof useNavigate>
}) {
  const unknown = !canRemediateItem(item.status) || !canRemediateItem(item.latestOperationStatus)
  if (unknown) {
    return <Typography.Text type="secondary">UNKNOWN 必须先查询确认，不能补发</Typography.Text>
  }
  if (item.status !== 'FAILED_FINAL' && item.status !== 'SUCCEEDED') return <span>—</span>
  return (
    <Button
      type="link"
      onClick={() => {
        const params = new URLSearchParams({
          itemNo: item.itemNo,
          operationNo: item.latestOperationNo || '',
          status: item.status,
        })
        onClose()
        navigate(`/remediations?${params.toString()}`)
      }}
    >
      发起补发
    </Button>
  )
}
