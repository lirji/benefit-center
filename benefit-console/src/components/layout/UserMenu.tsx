import { LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { App, Avatar, Dropdown, Grid, Space, Tag, Typography, type MenuProps } from 'antd'
import { useAuth } from '../../auth/AuthContext'

function roleOf(can: (p?: string) => boolean): { label: string; color: string } {
  if (can('benefit.admin')) return { label: '管理员', color: 'cyan' }
  if (can('benefit.remediate')) return { label: '处置员', color: 'green' }
  return { label: '观察员', color: 'default' }
}

export function UserMenu() {
  const auth = useAuth()
  const { modal } = App.useApp()
  const screens = Grid.useBreakpoint()
  const compact = !screens.lg
  const user = auth.user
  if (!user) return null

  const role = roleOf(auth.can)
  const initial = (user.name || '?').trim().charAt(0).toUpperCase()

  const items: MenuProps['items'] = [
    { key: 'who', label: <Typography.Text type="secondary">{user.name}</Typography.Text>, disabled: true },
    { key: 'tenant', label: <Typography.Text type="secondary">租户 {user.tenantId}</Typography.Text>, disabled: true },
    { type: 'divider' },
    { key: 'logout', danger: true, icon: <LogoutOutlined />, label: '退出登录' },
  ]

  const onClick: MenuProps['onClick'] = ({ key }) => {
    if (key !== 'logout') return
    modal.confirm({
      title: '退出登录',
      content: '将结束当前会话并返回登录页。',
      okText: '退出',
      cancelText: '取消',
      onOk: () => auth.logout(),
    })
  }

  return (
    <Dropdown menu={{ items, onClick }} trigger={['click']} placement="bottomRight">
      <a
        onClick={(e) => e.preventDefault()}
        aria-label={`当前用户 ${user.name}`}
        style={{ display: 'inline-flex', alignItems: 'center', cursor: 'pointer' }}
      >
        <Space size={8}>
          <Avatar size={30} style={{ background: '#0F6F6A' }} icon={initial ? undefined : <UserOutlined />}>
            {initial || undefined}
          </Avatar>
          {!compact && (
            <Space size={6}>
              <Typography.Text strong>{user.name}</Typography.Text>
              <Tag color={role.color} style={{ marginInlineEnd: 0 }}>
                {role.label}
              </Tag>
            </Space>
          )}
        </Space>
      </a>
    </Dropdown>
  )
}
