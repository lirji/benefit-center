export function workflowConsoleOrigin(): string {
  return (import.meta.env.VITE_WORKFLOW_CONSOLE_ORIGIN ?? '').trim().replace(/\/$/, '')
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
