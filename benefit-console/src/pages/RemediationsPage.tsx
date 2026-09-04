import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Card, Col, Drawer, Form, Grid, Input, Row, Space, Table } from 'antd'
import { acceptRemediation, executeRemediation, getRemediation, listRemediations } from '../api/benefit'
import type { RemediationView } from '../api/types'
import { EmptyState, ErrorState } from '../components/common/AsyncState'
import { PageHeader } from '../components/common/PageHeader'
import { RemediationStatusTag } from '../components/common/StatusTag'
import { RemediationForm, type RemediationFormValues } from '../components/remediations/RemediationForm'
import { errorMessage, formatDateTime, truncatedHint } from '../utils/format'
import { useAuth } from '../auth/AuthContext'

export function RemediationsPage() {
  const [params] = useSearchParams()
  const screens = Grid.useBreakpoint()
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const canWrite = useAuth().can('benefit.remediate')
  const [lookupNo, setLookupNo] = useState('')
  const [detailNo, setDetailNo] = useState<string | null>(null)
  const [formError, setFormError] = useState('')

  const list = useQuery({
    queryKey: ['remediations'],
    queryFn: () => listRemediations(undefined, 20),
  })
  const detail = useQuery({
    queryKey: ['remediation', detailNo],
    queryFn: () => getRemediation(detailNo!),
    enabled: Boolean(detailNo),
  })

  const initial = useMemo<Partial<RemediationFormValues>>(() => ({
    awardItemNo: params.get('itemNo') || '',
    originalOperationNo: params.get('operationNo') || '',
    action: params.get('status') === 'SUCCEEDED' ? 'REVERSE' : 'REISSUE',
    externalCommandId: `cmd-${Date.now()}`,
  }), [params])

  const accept = useMutation({
    mutationFn: acceptRemediation,
    onSuccess: async (result) => {
      setFormError('')
      message.success(`补发已受理 ${result.remediationNo}`)
      await queryClient.invalidateQueries({ queryKey: ['remediations'] })
      setDetailNo(result.remediationNo)
    },
    onError: (error) => setFormError(errorMessage(error)),
  })

  const execute = useMutation({
    mutationFn: executeRemediation,
    onSuccess: async () => {
      message.success('已触发执行')
      await queryClient.invalidateQueries({ queryKey: ['remediation', detailNo] })
      await queryClient.invalidateQueries({ queryKey: ['remediations'] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  const rows = list.data || []

  return (
    <>
      <PageHeader
        eyebrow="REMEDIATION"
        title="补发处置"
        description="从订单子项预填，或按补发号查询。UNKNOWN 子项禁止提交。补发/冲正必须带原 operation。"
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={10}>
          <Card title="发起补发" className="data-card">
            {!canWrite && <Alert type="info" showIcon message="当前身份没有 benefit.remediate，只能查看。" style={{ marginBottom: 16 }} />}
            {formError && <Alert type="error" showIcon message={formError} style={{ marginBottom: 16 }} />}
            <RemediationForm
              initial={initial}
              itemStatus={params.get('status') || undefined}
              operationStatus={params.get('operationStatus') || params.get('status') || undefined}
              submitting={accept.isPending}
              onSubmit={(values) => {
                if (!canWrite) return
                accept.mutate(values)
              }}
            />
          </Card>
        </Col>
        <Col xs={24} xl={14}>
          <Card className="filter-card" title="按号查询">
            <Form layout={screens.md ? 'inline' : 'vertical'} onFinish={() => lookupNo && setDetailNo(lookupNo)}>
              <Form.Item>
                <Input value={lookupNo} onChange={(e) => setLookupNo(e.target.value)} placeholder="RM-..." />
              </Form.Item>
              <Form.Item>
                <Button type="primary" htmlType="submit">打开</Button>
              </Form.Item>
            </Form>
          </Card>
          <Card title="本租户补发" extra={truncatedHint(rows)}>
            {list.isError ? (
              <ErrorState message={errorMessage(list.error)} onRetry={() => void list.refetch()} />
            ) : screens.md ? (
              <Table<RemediationView>
                rowKey="remediationNo"
                size="small"
                pagination={false}
                dataSource={rows}
                locale={{ emptyText: <EmptyState description="从订单发起或输入 remediationNo。" /> }}
                columns={[
                  {
                    title: '补发号',
                    dataIndex: 'remediationNo',
                    render: (v: string) => <Button type="link" className="table-link" onClick={() => setDetailNo(v)}>{v}</Button>,
                  },
                  { title: '动作', dataIndex: 'action' },
                  { title: '状态', dataIndex: 'status', render: (s: string) => <RemediationStatusTag status={s} /> },
                  { title: '更新时间', dataIndex: 'updatedAt', render: formatDateTime },
                ]}
              />
            ) : (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                {rows.map((row) => (
                  <button className="mobile-data-card" key={row.remediationNo} onClick={() => setDetailNo(row.remediationNo)}>
                    <div className="mobile-card-heading">
                      <strong>{row.remediationNo}</strong>
                      <RemediationStatusTag status={row.status} />
                    </div>
                    <span className="mobile-card-id">{row.action} · {row.itemNo}</span>
                  </button>
                ))}
                {rows.length === 0 && <EmptyState description="从订单发起或输入 remediationNo。" />}
              </Space>
            )}
          </Card>
        </Col>
      </Row>

      <Drawer title="补发详情" width={screens.md ? 480 : '100%'} open={Boolean(detailNo)} onClose={() => setDetailNo(null)}>
        {detail.data && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <div><strong>{detail.data.remediationNo}</strong></div>
            <RemediationStatusTag status={detail.data.status} />
            <div>命令号 {detail.data.externalCommandId}</div>
            {detail.data.reference && <div>参考 {detail.data.reference}</div>}
            {canWrite && detail.data.status === 'APPROVED' && (
              <Button type="primary" style={{ minHeight: 44 }} loading={execute.isPending} onClick={() => execute.mutate(detail.data!.remediationNo)}>
                执行补发
              </Button>
            )}
          </Space>
        )}
      </Drawer>
    </>
  )
}
