import { useEffect, useRef } from 'react'
import { Alert, Button, Drawer, Form, Input, InputNumber, Modal, Select, Space, Typography } from 'antd'
import type { SkuApprovalRuntime, SkuTemplateStatus, SkuView, ValidityType } from '../../api/types'
import { SkuStatusTag } from '../common/StatusTag'
import { fromDatetimeLocal, toDatetimeLocal } from '../../utils/format'
import { workflowProcessHref, workflowTasksFallbackUrl, workflowTasksHref } from '../../utils/workflowHref'
import { canEditSkuFields, canSubmitSkuApproval, operationalStatusOptions } from './skuLifecycle'

const WEEKDAYS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' },
]

export function skuFormValues(row?: SkuView | null) {
  if (!row) {
    return {
      benefitType: 'COUPON',
      status: 'DRAFT' as SkuTemplateStatus,
      validityType: 'RELATIVE' as ValidityType,
      usableWeekdays: [1, 2, 3, 4, 5, 6, 7],
    }
  }
  return {
    ...row,
    validFrom: toDatetimeLocal(row.validFrom),
    validTo: toDatetimeLocal(row.validTo),
  }
}

export type SkuSavePayload = Record<string, unknown> & { skuId: string }

export function SkuDrawer({
  open,
  sku,
  canWrite,
  saving,
  submitting,
  wide,
  onClose,
  onSave,
  onSubmitApproval,
  approvalRuntime,
  runtimeLoading,
  recovering,
  onRetryApproval,
  onWithdrawApproval,
  tenantId,
}: {
  open: boolean
  sku: SkuView | null
  canWrite: boolean
  saving: boolean
  submitting: boolean
  wide: boolean
  onClose: () => void
  onSave: (body: SkuSavePayload) => void
  onSubmitApproval: () => void
  approvalRuntime?: SkuApprovalRuntime
  runtimeLoading: boolean
  recovering: boolean
  onRetryApproval: () => void
  onWithdrawApproval: () => void
  tenantId?: string
}) {
  const [form] = Form.useForm()
  const submitRef = useRef<HTMLButtonElement>(null)
  const benefitType = Form.useWatch('benefitType', form)
  const validityType = Form.useWatch('validityType', form)

  useEffect(() => {
    if (open) form.setFieldsValue(skuFormValues(sku))
  }, [open, sku, form])
  useEffect(() => {
    if (open && sku && canSubmitSkuApproval(sku.status)) {
      submitRef.current?.focus()
    }
  }, [open, sku])
  const status = (sku?.status ?? 'DRAFT') as SkuTemplateStatus
  const fieldsLocked = !canWrite || !canEditSkuFields(sku?.status) || status === 'PENDING_APPROVAL'
  const pending = status === 'PENDING_APPROVAL'
  const processKey = sku?.approvalProcessDefinitionKey || 'benefitSkuGoLive'
  const businessKey = sku?.approvalBusinessKey || sku?.skuId || ''
  const processHref = pending ? workflowProcessHref(processKey, businessKey) : null
  const tasksHref = pending ? workflowTasksHref(processKey, businessKey) : null
  const instanceMissing = pending && approvalRuntime?.instancePresent === false
  const runtimeUnavailable = pending && !runtimeLoading && approvalRuntime == null
  const fallbackTasksUrl = businessKey ? workflowTasksFallbackUrl(businessKey, processKey) : ''
  const opsOptions = sku ? operationalStatusOptions(sku.status) : null

  return (
    <Drawer
      title={sku ? '编辑模板' : '新建模板'}
      width={wide ? 520 : '100%'}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      {pending && (
        <Alert
          type={instanceMissing || runtimeUnavailable ? 'warning' : 'info'}
          showIcon
          style={{ marginBottom: 16 }}
          message={runtimeLoading ? '正在核对审批实例' : instanceMissing ? '实例未建立' : runtimeUnavailable ? '暂时无法核对审批实例' : '已提交上线审批，办理在流程中台'}
          description={
            <Space direction="vertical" size={4}>
              {runtimeLoading ? (
                <span>正在向流程台查询同租户、同业务键的实例。</span>
              ) : instanceMissing ? (
                <>
                  <span>流程中台已确认没有该业务键的实例，可重发原审批 start，或只把 SKU 退回草稿。</span>
                  <span>SKU <Typography.Text copyable={{ text: sku?.skuId }}>{sku?.skuId}</Typography.Text></span>
                  <span>租户 <Typography.Text copyable={{ text: tenantId }}>{tenantId || '—'}</Typography.Text></span>
                  <span>业务键 <Typography.Text copyable={{ text: businessKey }}>{businessKey}</Typography.Text></span>
                  {fallbackTasksUrl ? (
                    <Typography.Paragraph copyable={{ text: fallbackTasksUrl }} style={{ marginBottom: 0 }}>
                      {fallbackTasksUrl}
                    </Typography.Paragraph>
                  ) : null}
                  <Space wrap>
                    <Button type="primary" loading={recovering} disabled={!canWrite} onClick={onRetryApproval}>
                      重发审批
                    </Button>
                    <Button
                      danger
                      loading={recovering}
                      disabled={!canWrite}
                      onClick={() => Modal.confirm({
                        title: '退回草稿？',
                        content: '该操作只把 SKU 退回 DRAFT，不会终止任何已存在或迟到建立的流程实例。',
                        okText: '确认退回',
                        cancelText: '取消',
                        okButtonProps: { danger: true },
                        onOk: onWithdrawApproval,
                      })}
                    >
                      退回草稿
                    </Button>
                  </Space>
                </>
              ) : (
                <>
                  <span>{runtimeUnavailable ? '为避免误撤正在办理的审批，恢复按钮已禁用；请先到流程台核对。' : '状态落地前不能改字段。办理显示「已受理」，不是「已投放」。'}</span>
                  {processHref ? (
                    <a href={processHref} target="_blank" rel="noreferrer">查看审批轨迹</a>
                  ) : null}
                  {tasksHref ? (
                    <a href={tasksHref} target="_blank" rel="noreferrer">打开待办</a>
                  ) : null}
                  {!processHref && !tasksHref && fallbackTasksUrl ? (
                    <Typography.Paragraph copyable={{ text: fallbackTasksUrl }} style={{ marginBottom: 0 }}>
                      {fallbackTasksUrl}
                    </Typography.Paragraph>
                  ) : null}
                </>
              )}
            </Space>
          }
        />
      )}
      <Form
        form={form}
        layout="vertical"
        disabled={fieldsLocked}
        onFinish={(values) => {
          const nextStatus = sku?.status === 'DRAFT' || !sku ? 'DRAFT' : values.status
          onSave({
            skuId: values.skuId,
            benefitType: values.benefitType,
            status: nextStatus,
            validityType: values.validityType,
            relativeDays: values.validityType === 'RELATIVE' ? values.relativeDays : null,
            validFrom: values.validityType === 'ABSOLUTE' ? fromDatetimeLocal(values.validFrom) : null,
            validTo: values.validityType === 'ABSOLUTE' ? fromDatetimeLocal(values.validTo) : null,
            usableWeekdays: values.usableWeekdays,
            dailyQuota: values.dailyQuota ?? null,
            userLimitPerDay: values.userLimitPerDay ?? null,
            userLimitTotal: values.userLimitTotal ?? null,
            faceValueMinor: values.benefitType === 'CASH' ? values.faceValueMinor : null,
            currency: values.benefitType === 'CASH' ? values.currency : null,
            expectedVersion: sku?.version,
          })
        }}
      >
        <Form.Item name="skuId" label="SKU ID" rules={[{ required: true }]}>
          <Input disabled={Boolean(sku)} aria-label="SKU ID" />
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
          <Alert type="info" showIcon message="非现金模板不能携带金额或币种。" style={{ marginBottom: 16 }} />
        )}
        <Form.Item label="状态">
          <Space>
            <SkuStatusTag status={status} />
            {opsOptions ? (
              <Form.Item name="status" noStyle rules={[{ required: true }]}>
                <Select style={{ minWidth: 140 }} options={opsOptions} disabled={!canWrite} />
              </Form.Item>
            ) : null}
          </Space>
        </Form.Item>
        <Form.Item name="validityType" label="有效期类型" rules={[{ required: true }]}>
          <Select options={[{ value: 'RELATIVE', label: '领取后 N 天' }, { value: 'ABSOLUTE', label: '绝对窗口' }]} />
        </Form.Item>
        {validityType === 'RELATIVE' ? (
          <Form.Item name="relativeDays" label="领取后有效天数" rules={[{ required: true, message: '相对有效期必须填写天数' }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
        ) : (
          <>
            <Form.Item name="validFrom" label="开始时间" rules={[{ required: true, message: '绝对窗口必须填写开始时间' }]}>
              <Input type="datetime-local" />
            </Form.Item>
            <Form.Item name="validTo" label="结束时间" rules={[{ required: true, message: '绝对窗口必须填写结束时间' }]}>
              <Input type="datetime-local" />
            </Form.Item>
          </>
        )}
        <Form.Item name="usableWeekdays" label="可用星期">
          <Select mode="multiple" options={WEEKDAYS} />
        </Form.Item>
        <Form.Item name="dailyQuota" label="日配额">
          <InputNumber min={1} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="userLimitPerDay" label="单用户每日上限">
          <InputNumber min={1} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="userLimitTotal" label="单用户累计上限">
          <InputNumber min={1} style={{ width: '100%' }} />
        </Form.Item>
        {status !== 'RETIRED' && status !== 'PENDING_APPROVAL' ? (
          <Button type="primary" htmlType="submit" aria-label={sku ? '保存模板' : '保存草稿'} loading={saving} disabled={!canWrite} block style={{ minHeight: 44 }}>
            {sku ? '保存模板' : '保存草稿'}
          </Button>
        ) : null}
      </Form>
      {sku == null || canSubmitSkuApproval(sku.status) ? (
        <Button
          ref={submitRef}
          type="primary"
          aria-label="提交上线审批"
          loading={submitting}
          disabled={!canWrite || !sku}
          title={!sku ? '请先保存草稿' : undefined}
          onClick={onSubmitApproval}
          block
          style={{ minHeight: 44, marginTop: 12 }}
        >
          提交上线审批
        </Button>
      ) : null}
    </Drawer>
  )
}
