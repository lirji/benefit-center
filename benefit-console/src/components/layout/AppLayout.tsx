import { useState } from 'react'
import {
  AppstoreOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  GiftOutlined,
  MenuFoldOutlined,
  MenuOutlined,
  MenuUnfoldOutlined,
  ReconciliationOutlined,
  ToolOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import { Breadcrumb, Button, Drawer, Grid, Layout, Menu, Space } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { colors } from '../../theme/colors'
import { UserMenu } from './UserMenu'

const navigation = [
  { key: '/dashboard', label: '工作台', icon: <DashboardOutlined /> },
  { key: '/orders', label: '发放订单', icon: <ReconciliationOutlined /> },
  { key: '/remediations', label: '补发处置', icon: <ToolOutlined /> },
  { key: '/inventory', label: '库存中心', icon: <DatabaseOutlined /> },
  { key: '/catalog', label: '权益目录', icon: <AppstoreOutlined /> },
  { key: '/wallets', label: '用户资产', icon: <WalletOutlined /> },
]

export function AppLayout() {
  const location = useLocation()
  const navigate = useNavigate()
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.lg
  const [collapsed, setCollapsed] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const current = navigation.find((item) => location.pathname.startsWith(item.key))

  const menu = (afterClick?: () => void) => (
    <Menu
      mode="inline"
      selectedKeys={[current?.key || '/dashboard']}
      items={navigation}
      onClick={({ key }) => {
        navigate(key)
        afterClick?.()
      }}
      style={{ borderInlineEnd: 0 }}
    />
  )

  const brand = (
    <div className="brand" aria-label="权益发放中台">
      <span className="brand-mark"><GiftOutlined /></span>
      {!collapsed && <span>权益发放中台</span>}
    </div>
  )

  return (
    <Layout className="app-shell">
      {!isMobile && (
        <Layout.Sider
          theme="light"
          width={224}
          collapsedWidth={72}
          collapsed={collapsed}
          trigger={null}
          className="app-sider"
        >
          {brand}
          {menu()}
        </Layout.Sider>
      )}

      <Layout>
        <Layout.Header className="app-header">
          <Space>
            <Button
              type="text"
              aria-label={isMobile ? '打开菜单' : collapsed ? '展开菜单' : '收起菜单'}
              icon={isMobile ? <MenuOutlined /> : collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => (isMobile ? setDrawerOpen(true) : setCollapsed((value) => !value))}
            />
            <Breadcrumb items={[{ title: '权益运营' }, { title: current?.label || '工作台' }]} />
          </Space>
          <UserMenu />
        </Layout.Header>
        <Layout.Content>
          <main className="app-content">
            <Outlet />
          </main>
        </Layout.Content>
      </Layout>

      <Drawer
        placement="left"
        width={240}
        open={isMobile && drawerOpen}
        onClose={() => setDrawerOpen(false)}
        styles={{ body: { padding: 0 } }}
        title={<Space><GiftOutlined style={{ color: colors.primary }} />权益发放中台</Space>}
      >
        {menu(() => setDrawerOpen(false))}
      </Drawer>
    </Layout>
  )
}
