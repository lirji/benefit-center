import { Alert, Button, Form, Input, Radio, Tooltip } from 'antd'
import { canRemediateItem } from '../common/StatusTag'

export type RemediationFormValues = {
  externalCommandId: string
  action: 'REISSUE' | 'REVERSE' | 'MANUAL_REVIEW'
  awardItemNo: string
  originalOperationNo?: string
  reason: string
  approvalRef?: string
}

export function RemediationForm({
  initial,
  itemStatus,
  operationStatus,
  submitting,
  onSubmit,
}: {
  initial?: Partial<RemediationFormValues>
  itemStatus?: string
  operationStatus?: string
  submitting?: boolean
  onSubmit: (values: RemediationFormValues) => void
}) {
  const blocked = !canRemediateItem(itemStatus) || !canRemediateItem(operationStatus)
  const [form] = Form.useForm<RemediationFormValues>()
  const action = Form.useWatch('action', form) || initial?.action || 'REISSUE'

  return (
    <Form
      form={form}
      layout="vertical"
      initialValues={{ action: 'REISSUE', ...initial }}
      onFinish={onSubmit}
      disabled={blocked}
    >
      {blocked && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="UNKNOWN / QUERYING 不能补发"
          description="必须先由 worker 查询同一 operation，确认 NOT_ISSUED 或成功后才能处置。"
        />
      )}
      <Form.Item name="awardItemNo" label="子项号" rules={[{ required: true, message: '请填写子项号' }]}>
        <Input placeholder="IT-..." />
      </Form.Item>
      <Form.Item name="action" label="动作">
        <Radio.Group>
          <Radio.Button value="REISSUE">补发</Radio.Button>
          <Radio.Button value="REVERSE">冲正</Radio.Button>
          <Radio.Button value="MANUAL_REVIEW">人工复核</Radio.Button>
        </Radio.Group>
      </Form.Item>
      {action !== 'MANUAL_REVIEW' && (
        <Form.Item
          name="originalOperationNo"
          label="原履约号"
          rules={[{ required: true, message: '补发/冲正必须填写 originalOperationNo' }]}
        >
          <Input placeholder="OP-..." />
        </Form.Item>
      )}
      <Form.Item name="reason" label="原因" rules={[{ required: true, message: '请填写原因' }]}>
        <Input.TextArea rows={3} maxLength={512} showCount />
      </Form.Item>
      <Form.Item
        name="approvalRef"
        label="审批单号"
        extra={action === 'MANUAL_REVIEW' ? '人工复核可不填' : '建议填写，有审批单号才会自动批准'}
      >
        <Input placeholder="approval-..." />
      </Form.Item>
      <Form.Item name="externalCommandId" label="外部命令号" rules={[{ required: true, message: '请填写幂等命令号' }]}>
        <Input placeholder="cmd-..." />
      </Form.Item>
      <Tooltip title={blocked ? 'UNKNOWN 子项不能提交补发' : undefined}>
        <Button type="primary" htmlType="submit" loading={submitting} disabled={blocked} block style={{ minHeight: 44 }}>
          提交补发
        </Button>
      </Tooltip>
    </Form>
  )
}
