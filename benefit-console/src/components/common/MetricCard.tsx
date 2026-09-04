import type { ReactNode } from 'react'
import { ArrowRightOutlined } from '@ant-design/icons'
import { Button } from 'antd'

interface Props {
  label: string
  value: string
  hint: string
  icon: ReactNode
  tone: 'primary' | 'success' | 'warning' | 'error'
  actionLabel?: string
  onAction?: () => void
}

export function MetricCard({ label, value, hint, icon, tone, actionLabel, onAction }: Props) {
  return (
    <section className={`metric-card metric-${tone}`} aria-label={label}>
      <div className="metric-icon">{icon}</div>
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      <div className="metric-footer">
        <span>{hint}</span>
        {actionLabel && onAction && (
          <Button type="link" size="small" onClick={onAction}>
            {actionLabel} <ArrowRightOutlined />
          </Button>
        )}
      </div>
    </section>
  )
}
