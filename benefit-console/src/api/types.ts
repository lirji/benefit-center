export type UserSession = {
  authenticated: boolean
  sub: string
  name: string
  tenantId: string
  permissions: string[]
}

export type ConsoleIdentity = {
  tenantId: string
  subject: string
  displayName: string
  scopes: string[]
}

export type Overview = {
  unknownOps: number
  partialOrders: number
  pendingRemediations: number
  skuCount: number
  enabledRoutes: number
  activeTemplateCount?: number
  walletIssued24h?: number
}

export type SkuTemplateStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'PAUSED' | 'RETIRED'
export type ValidityType = 'ABSOLUTE' | 'RELATIVE'
export type WalletEntryStatus = 'UNUSED' | 'FROZEN' | 'USED' | 'EXPIRED' | 'REVERSED'

export type TenantView = {
  tenantId: string
  homeCell: string
  enabled: boolean
  version: number
}

export type SkuView = {
  skuId: string
  benefitType: string
  faceValueMinor: number | null
  currency: string | null
  status: SkuTemplateStatus
  enabled: boolean
  validityType: ValidityType
  validFrom: string | null
  validTo: string | null
  relativeDays: number | null
  usableWeekdays: number[]
  dailyQuota: number | null
  userLimitPerDay: number | null
  userLimitTotal: number | null
  equivalentSkuId: string | null
  approvalProcessDefinitionKey?: string | null
  approvalBusinessKey?: string | null
  version: number
}

export type SkuSubmitAcceptance = {
  skuId: string
  status: 'PENDING_APPROVAL'
  version: number
}

export type WalletView = {
  subjectRef: string
  totalEntries: number
  unusedEntries: number
  cashBalances: { currency: string; balanceMinor: number }[]
}

export type WalletEntryView = {
  entryId: string
  subjectRef: string
  skuId: string
  skuVersion: number
  awardOrderNo: string
  itemNo: string
  assetType: string
  status: WalletEntryStatus
  version: number
  expiresAt: string | null
  faceValueMinor: number | null
  currency: string | null
  createdAt: string
}

export type WalletEntryCommand = {
  reason?: string
  merchantRef?: string
  expectedVersion?: number
}

export type WalletEntryCommandAcceptance = {
  entryId: string
  status: WalletEntryStatus
  version: number
}

export type RouteView = {
  routeId: string
  skuId: string
  priority: number
  channelCode: string
  ownerType: string
  fallbackRouteId: string | null
  reserveMode: string
  enabled: boolean
  configRef: string | null
  version: number
}

export type InventoryView = {
  accountId: string
  skuId: string
  ownerType: string
  ownerId: string
  available: number
  reserved: number
  issued: number
  version: number
}

export type AttentionOrder = {
  orderNo: string
  sourceSystem: string
  sourceRequestId: string
  status: string
  reason: string
  updatedAt: string | null
}

export type AwardItem = {
  itemNo: string
  clientItemId: string
  skuId: string
  benefitType: string
  quantity: number
  amountMinor: number | null
  currency: string | null
  status: string
  routeId: string | null
  failureCode: string | null
  latestOperationNo: string | null
  latestOperationStatus: string | null
  skuVersion?: number
  walletEntryId?: string | null
}

export type AwardOrder = {
  orderNo: string
  sourceSystem: string
  sourceRequestId: string
  sourceBusinessNo: string | null
  recipientRef: string
  status: string
  homeCell: string
  items: AwardItem[]
}

export type RemediationView = {
  remediationNo: string
  action: string
  itemNo: string
  status: string
  reason: string | null
  updatedAt: string | null
}

export type RemediationResult = {
  externalCommandId: string
  remediationNo: string
  status: string
  reference: string | null
  errorCode: string | null
}

export type CodeAssetView = {
  codeAssetId: string
  skuId: string
  status: string
  codeHashPrefix: string | null
  expiresAt: string | null
}

export type RemediationCommand = {
  externalCommandId: string
  action: 'REISSUE' | 'REVERSE' | 'MANUAL_REVIEW'
  awardItemNo: string
  originalOperationNo?: string
  reason: string
  approvalRef?: string
}
