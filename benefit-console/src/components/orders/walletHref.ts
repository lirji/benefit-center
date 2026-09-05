export function walletEntryHref(recipientRef?: string | null, entryId?: string | null) {
  if (!recipientRef || !entryId) return ''
  const params = new URLSearchParams({ subject: recipientRef, entry: entryId })
  return `/wallets?${params}`
}
