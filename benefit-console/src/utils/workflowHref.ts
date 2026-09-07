export const LOCAL_WORKFLOW_ORIGIN = 'http://localhost:8302'
export const SKU_GO_LIVE_KEY = 'benefitSkuGoLive'

export function workflowConsoleOrigin(): string {
  return (import.meta.env.VITE_WORKFLOW_CONSOLE_ORIGIN ?? '').trim().replace(/\/$/, '')
}

/** origin 未烘焙时仍给出可复制的本机待办 URL，避免 PENDING 看起来像「正在审批」。 */
export function workflowTasksFallbackUrl(businessKey: string, processDefinitionKey = SKU_GO_LIVE_KEY): string {
  return `${LOCAL_WORKFLOW_ORIGIN}/tasks?definitionKey=${encodeURIComponent(processDefinitionKey)}&businessKey=${encodeURIComponent(businessKey)}`
}

export function workflowProcessHref(processDefinitionKey: string, businessKey: string): string | null {
  const origin = workflowConsoleOrigin()
  if (!origin || !processDefinitionKey || !businessKey) return null
  return `${origin}/process/${encodeURIComponent(processDefinitionKey)}?businessKey=${encodeURIComponent(businessKey)}`
}

export function workflowTasksHref(processDefinitionKey: string, businessKey: string): string | null {
  const origin = workflowConsoleOrigin()
  if (!origin || !processDefinitionKey || !businessKey) return null
  return `${origin}/tasks?definitionKey=${encodeURIComponent(processDefinitionKey)}&businessKey=${encodeURIComponent(businessKey)}`
}
