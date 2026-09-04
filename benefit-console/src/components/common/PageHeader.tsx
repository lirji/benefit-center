import type { ReactNode } from 'react'

interface Props {
  eyebrow?: string
  title: string
  description: string
  extra?: ReactNode
}

export function PageHeader({ eyebrow, title, description, extra }: Props) {
  return (
    <header className="page-header">
      <div>
        {eyebrow && <div className="page-eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {extra && <div className="page-header-actions">{extra}</div>}
    </header>
  )
}
