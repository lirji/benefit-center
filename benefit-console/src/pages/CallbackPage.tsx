import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Result, Spin } from 'antd'
import { useAuth } from '../auth/AuthContext'

export function CallbackPage() {
  const { completeLogin } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    started.current = true
    completeLogin()
      .then((path) => navigate(path, { replace: true }))
      .catch((cause) => setError(cause instanceof Error ? cause.message : '登录回调处理失败'))
  }, [completeLogin, navigate])

  return (
    <main
      style={{ minHeight: '100dvh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16 }}
    >
      {error ? (
        <Result
          status="warning"
          title="登录未完成"
          subTitle={error}
          extra={
            <Button type="primary" onClick={() => navigate('/login', { replace: true })}>
              返回登录
            </Button>
          }
        />
      ) : (
        <Spin size="large" tip="正在完成登录…">
          <div style={{ padding: 24 }} />
        </Spin>
      )}
    </main>
  )
}
