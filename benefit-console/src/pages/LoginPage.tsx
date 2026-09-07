import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  ApartmentOutlined,
  ArrowRightOutlined,
  CheckCircleFilled,
  DatabaseOutlined,
  GiftOutlined,
  LoginOutlined,
  SafetyCertificateOutlined,
  ToolOutlined,
} from '@ant-design/icons'
import { Alert, Button, Form, Input } from 'antd'
import { AUTH_CONFIG, AUTH_MODE, DEFAULT_DEV_TENANT, getDevTenantId, setDevTenantId } from '../auth/config'
import { useAuth } from '../auth/AuthContext'
import { validateTenantSelection } from '../auth/tenantSelection'
import '../styles/login.css'

const FEATURES = [
  { icon: <GiftOutlined />, title: '组合发放履约', desc: '现金、券、码与实物按 item 履约，部分成功可追踪' },
  { icon: <ToolOutlined />, title: '受控补发处置', desc: 'UNKNOWN 先查询，明确失败后才能补发或冲正' },
  { icon: <DatabaseOutlined />, title: '目录与库存', desc: 'SKU、路由和中心配额在同一工作台维护' },
  { icon: <SafetyCertificateOutlined />, title: '租户隔离', desc: '按货主业务租户隔离，登录组织 benefit-center 不是货主' },
]

export function LoginPage() {
  const auth = useAuth()
  const [params] = useSearchParams()
  const [tenant, setTenant] = useState(AUTH_MODE === 'dev' ? getDevTenantId() : AUTH_CONFIG.organization)
  const [tenantError, setTenantError] = useState('')
  const isOidc = AUTH_MODE === 'oidc'

  async function submit(event: FormEvent) {
    event.preventDefault()
    setTenantError('')
    if (isOidc) {
      const result = validateTenantSelection(tenant, AUTH_CONFIG.organization, AUTH_CONFIG.clientId)
      if (!result.ok) {
        setTenantError(result.message)
        return
      }
    } else {
      setDevTenantId(tenant || DEFAULT_DEV_TENANT)
    }
    await auth.login(params.get('returnTo') ?? '/dashboard')
  }

  const mark = (
    <span className="login-mark" aria-hidden>
      <GiftOutlined />
    </span>
  )

  return (
    <main className="login-root">
      <div className="login-card">
        <aside className="login-aside">
          <span className="login-aside-ring" aria-hidden />
          <div className="login-aside-top">
            {mark}
            <span className="login-aside-name">权益发放中台</span>
          </div>
          <div className="login-aside-main">
            <p className="login-aside-kicker">BENEFIT CENTER</p>
            <h1 className="login-aside-title">
              发放可追溯
              <br />
              补发可审计
            </h1>
            <p className="login-aside-sub">运营先看未知与部分成功，再配置目录与库存。手工发奖不在本台。</p>
            <ul className="login-features">
              {FEATURES.map((f) => (
                <li key={f.title}>
                  <span className="login-feature-icon">{f.icon}</span>
                  <span className="login-feature-text">
                    <b>{f.title}</b>
                    <small>{f.desc}</small>
                  </span>
                </li>
              ))}
            </ul>
          </div>
          <div className="login-aside-foot">Benefit Center · Fulfillment Console</div>
        </aside>

        <section className="login-form">
          <div className="login-form-brand">
            {mark}
            <span>权益发放中台</span>
          </div>
          <p className="login-kicker">{isOidc ? 'Casdoor SSO · PKCE' : 'Local · Dev Mode'}</p>
          <h2 className="login-form-title">{isOidc ? '欢迎进入权益运营台' : '进入本地开发模式'}</h2>
          <p className="login-form-sub">
            {isOidc
              ? '登录组织是 benefit-center，与营销的 marketing-platform 不同。货主来自 JWT 的 tenant_id，须与营销建活动时相同。'
              : '这里填货主业务租户，写入 X-Tenant-Id，默认 dev-tenant。必须与营销创建活动时相同。营销 DEV 默认是 retail-cn；填不一样则对方看不到这些商品。'}
          </p>

          <Form layout="vertical" onSubmitCapture={submit} requiredMark={false}>
            <Form.Item
              label={isOidc ? '登录组织' : '货主业务租户'}
              validateStatus={tenantError ? 'error' : undefined}
              help={tenantError || undefined}
            >
              <Input
                value={tenant}
                onChange={(e) => {
                  setTenant(e.target.value)
                  setTenantError('')
                }}
                disabled={auth.redirecting}
                autoComplete="organization"
                spellCheck={false}
                placeholder={isOidc ? '例如 benefit-center' : DEFAULT_DEV_TENANT}
                size="large"
                prefix={<ApartmentOutlined className="login-input-icon" />}
              />
            </Form.Item>

            {auth.error && <Alert type="error" showIcon message={auth.error} className="login-alert" />}

            <Button
              type="primary"
              size="large"
              block
              htmlType="submit"
              loading={auth.redirecting}
              className="login-submit"
            >
              <span className="login-submit-main">
                {!auth.redirecting && <LoginOutlined />}
                {auth.redirecting ? '正在跳转 Casdoor…' : isOidc ? '使用 Casdoor 登录' : '进入本地开发模式'}
              </span>
              {!auth.redirecting && isOidc && <ArrowRightOutlined className="login-submit-arrow" />}
            </Button>
          </Form>

          <div className="login-secure-note">
            <CheckCircleFilled />
            <span>
              {isOidc
                ? '由 Casdoor 提供统一身份认证，使用 OIDC Authorization Code + PKCE。'
                : '本地开发模式免登录，请勿用于生产环境。'}
            </span>
          </div>
        </section>
      </div>
      <p className="login-foot">权益发放中台 · 内部运营使用</p>
    </main>
  )
}
