import { afterEach, describe, expect, it, vi } from 'vitest'

describe('workflowHref', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.resetModules()
  })

  it('returns null when origin is empty', async () => {
    vi.stubEnv('VITE_WORKFLOW_CONSOLE_ORIGIN', '')
    const { workflowProcessHref, workflowTasksHref } = await import('./workflowHref')
    expect(workflowProcessHref('benefitSkuGoLive', 'SKU-1')).toBeNull()
    expect(workflowTasksHref('benefitSkuGoLive', 'SKU-1')).toBeNull()
  })

  it('builds process and task links when origin is set', async () => {
    vi.stubEnv('VITE_WORKFLOW_CONSOLE_ORIGIN', 'http://localhost:8302/')
    const { workflowProcessHref, workflowTasksHref } = await import('./workflowHref')
    expect(workflowProcessHref('benefitSkuGoLive', 'SKU-1')).toBe(
      'http://localhost:8302/process/benefitSkuGoLive?businessKey=SKU-1',
    )
    expect(workflowTasksHref('benefitSkuGoLive', 'SKU-1')).toBe(
      'http://localhost:8302/tasks?definitionKey=benefitSkuGoLive&businessKey=SKU-1',
    )
  })
})
