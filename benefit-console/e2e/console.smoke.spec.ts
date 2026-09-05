import { expect, test, type Page } from '@playwright/test'

async function mockApi(page: Page) {
  await page.route('**/admin/**', async (route) => {
    const url = new URL(route.request().url())
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
    if (url.pathname === '/admin/v1/console/me') {
      return json({ tenantId: 'dev-tenant', subject: 'dev', displayName: 'qa-ops', scopes: ['benefit.admin', 'benefit.award.read', 'benefit.remediate'] })
    }
    if (url.pathname === '/admin/v1/console/overview') {
      return json({ unknownOps: 1, partialOrders: 1, pendingRemediations: 0, skuCount: 2, enabledRoutes: 1, activeTemplateCount: 1, walletIssued24h: 0 })
    }
    if (url.pathname === '/admin/v1/console/attention-orders') {
      return json([{ orderNo: 'ORD-1', sourceSystem: 'drools', sourceRequestId: 'req-1', status: 'PARTIAL_SUCCEEDED', reason: 'PARTIAL_SUCCEEDED', updatedAt: '2026-09-04T04:00:00Z' }])
    }
    if (url.pathname === '/admin/v1/tenants/current') {
      return json({ tenantId: 'dev-tenant', homeCell: 'cell-0', enabled: true, version: 1 })
    }
    if (url.pathname === '/admin/v1/skus') {
      return json([
        {
          skuId: 'SKU-1', benefitType: 'COUPON', faceValueMinor: null, currency: null, status: 'ACTIVE', enabled: true,
          validityType: 'RELATIVE', validFrom: null, validTo: null, relativeDays: 7, usableWeekdays: [1, 2, 3, 4, 5, 6, 7],
          dailyQuota: null, userLimitPerDay: 1, userLimitTotal: null, equivalentSkuId: null, version: 1,
        },
        {
          skuId: 'SKU-PENDING', benefitType: 'COUPON', faceValueMinor: null, currency: null, status: 'PENDING_APPROVAL', enabled: false,
          validityType: 'RELATIVE', validFrom: null, validTo: null, relativeDays: 7, usableWeekdays: [1, 2, 3, 4, 5, 6, 7],
          dailyQuota: null, userLimitPerDay: 1, userLimitTotal: null, equivalentSkuId: null,
          approvalProcessDefinitionKey: 'benefitSkuGoLive', approvalBusinessKey: 'SKU-PENDING', version: 2,
        },
      ])
    }
    if (url.pathname.endsWith('/entries') && url.pathname.includes('/admin/v1/wallets/')) {
      return json([{
        entryId: 'WE-1',
        subjectRef: 'user-1',
        skuId: 'SKU-1',
        skuVersion: 1,
        awardOrderNo: 'ORD-1',
        itemNo: 'IT-1',
        assetType: 'COUPON',
        status: 'UNUSED',
        version: 0,
        expiresAt: '2026-12-01T00:00:00Z',
        faceValueMinor: 1000,
        currency: 'CNY',
        createdAt: '2026-09-01T00:00:00Z',
      }])
    }
    if (url.pathname.startsWith('/admin/v1/wallets/')) {
      return json({ subjectRef: 'user-1', totalEntries: 1, unusedEntries: 1, cashBalances: [] })
    }
    if (url.pathname === '/admin/v1/routes') return json([])
    if (url.pathname === '/admin/v1/inventory/accounts') return json([])
    if (url.pathname === '/admin/v1/remediations') return json([])
    if (url.pathname === '/admin/v1/code-assets') return json([])
    return route.fulfill({ status: 404, body: '{}' })
  })
  await page.route('**/openapi/**', async (route) => {
    const url = new URL(route.request().url())
    if (/\/openapi\/v1\/wallet-entries\/[^/]+:(freeze|redeem|refund)$/.test(url.pathname)) {
      return route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({ entryId: 'WE-1', status: 'USED', version: 1 }),
      })
    }
    if (url.pathname === '/openapi/v1/award-orders/ORD-1') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          orderNo: 'ORD-1',
          sourceSystem: 'drools',
          sourceRequestId: 'req-1',
          sourceBusinessNo: null,
          recipientRef: 'user-1',
          status: 'PARTIAL_SUCCEEDED',
          homeCell: 'cell-0',
          items: [{ itemNo: 'IT-1', clientItemId: 'c1', skuId: 'SKU-1', benefitType: 'COUPON', quantity: 1, amountMinor: null, currency: null, status: 'FAILED_FINAL', routeId: 'R-1', failureCode: 'NOT_ISSUED', latestOperationNo: 'OP-1', latestOperationStatus: 'FAILED_FINAL', walletEntryId: 'WE-1' }],
        }),
      })
    }
    return route.fulfill({ status: 404, body: '{}' })
  })
  await page.route('**/internal/**', async (route) => {
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })
}

async function navigateByMenu(page: Page, label: string) {
  if ((page.viewportSize()?.width || 1280) < 992) {
    await page.getByRole('button', { name: '打开菜单' }).click()
  }
  await page.getByText(label, { exact: true }).click()
}

test('operator can open the dashboard and inspect an order', async ({ page }) => {
  await mockApi(page)
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: '权益运营总览' })).toBeVisible()
  await expect(page.getByLabel('未知履约')).toContainText('1')

  await navigateByMenu(page, '发放订单')
  await expect(page.getByRole('heading', { name: '发放订单' })).toBeVisible()
  await page.getByPlaceholder('ORD-...').fill('ORD-1')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByText('发放订单详情')).toBeVisible()
  if ((page.viewportSize()?.width || 1280) < 768) {
    await expect(page.locator('.mobile-data-card').first()).toBeVisible()
    await expect(page.getByRole('button', { name: '入账券包' })).toBeVisible()
  } else {
    await expect(page.getByRole('button', { name: '入账券包' })).toBeVisible()
  }
})

test('narrow viewport can open wallets as cards', async ({ page }) => {
  await mockApi(page)
  await page.goto('/wallets')
  await expect(page.getByRole('heading', { name: '用户资产' })).toBeVisible()
  if ((page.viewportSize()?.width || 1280) >= 768) test.skip()
  await page.getByPlaceholder(/用户稳定引用/).fill('user-1')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByText('券包条目')).toBeVisible()
  await expect(page.locator('.mobile-data-card').first()).toBeVisible()
})

test('narrow viewport wallet drawer redeem button is 44px', async ({ page }) => {
  await mockApi(page)
  await page.goto('/wallets?subject=user-1')
  await expect(page.getByRole('heading', { name: '用户资产' })).toBeVisible()
  if ((page.viewportSize()?.width || 1280) >= 768) test.skip()
  await expect(page.getByText('券包条目')).toBeVisible()
  await page.locator('.mobile-data-card').first().click()
  const redeem = page.getByRole('button', { name: '核销' })
  await expect(redeem).toBeVisible()
  const box = await redeem.boundingBox()
  expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
})

test('narrow viewport can open a pending catalog template', async ({ page }) => {
  await mockApi(page)
  await page.goto('/catalog?skuId=SKU-PENDING')
  await expect(page.getByRole('heading', { name: '权益目录' })).toBeVisible()
  if ((page.viewportSize()?.width || 1280) >= 768) test.skip()
  await expect(page.locator('.mobile-data-card').filter({ hasText: '待审批' })).toBeVisible()
  await expect(page.getByText('已提交上线审批，办理在流程中台')).toBeVisible()
  await expect(page.getByRole('button', { name: '提交上线审批' })).toHaveCount(0)
})

test('narrow viewport opens the drawer menu', async ({ page }) => {
  await mockApi(page)
  await page.goto('/dashboard')
  if ((page.viewportSize()?.width || 1280) >= 992) test.skip()
  await page.getByRole('button', { name: '打开菜单' }).click()
  await expect(page.getByText('库存中心', { exact: true })).toBeVisible()
})
