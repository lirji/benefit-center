import { useNavigate } from 'react-router-dom'
import { Button, Result } from 'antd'

export function ForbiddenPage() {
  const navigate = useNavigate()
  return (
    <main style={{ minHeight: '100dvh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <Result
        status="403"
        title="403"
        subTitle="你没有访问该页面的权限，请联系管理员分配 benefit.admin / 对应 scope。"
        extra={
          <Button type="primary" onClick={() => navigate('/dashboard', { replace: true })}>
            返回工作台
          </Button>
        }
      />
    </main>
  )
}
