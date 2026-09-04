export type TenantSelectionResult =
  | { ok: true; organization: string }
  | { ok: false; message: string }

export function validateTenantSelection(
  rawTenant: string,
  expectedOrganization: string,
  configuredClientId: string,
): TenantSelectionResult {
  const tenant = rawTenant.trim()
  const organization = expectedOrganization.trim()

  if (!tenant) return { ok: false, message: '请输入所属组织' }
  if (!organization) return { ok: false, message: '未配置可用组织，请联系管理员' }
  if (tenant !== organization) {
    return { ok: false, message: `组织 ${tenant} 未开放。当前可用组织：${organization}` }
  }
  if (!configuredClientId.endsWith(`-org-${organization}`)) {
    return { ok: false, message: '统一登录配置与组织不匹配，请联系管理员' }
  }

  return { ok: true, organization }
}
