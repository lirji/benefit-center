import axios from 'axios'
import { describe, expect, it } from 'vitest'
import { applyWriteIdempotencyKey } from './client'

describe('applyWriteIdempotencyKey', () => {
  it('does not attach a key on GET', () => {
    const config = { method: 'get', headers: axios.AxiosHeaders.from({}) }
    applyWriteIdempotencyKey(config)
    expect(config.headers.get('Idempotency-Key')).toBeUndefined()
  })

  it('attaches a UUID on PUT when missing', () => {
    const config = { method: 'put', headers: axios.AxiosHeaders.from({}) }
    applyWriteIdempotencyKey(config)
    expect(config.headers.get('Idempotency-Key')).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  })

  it('keeps a caller-supplied key (remediation business id)', () => {
    const config = {
      method: 'post',
      headers: axios.AxiosHeaders.from({ 'Idempotency-Key': 'cmd-external-1' }),
    }
    applyWriteIdempotencyKey(config)
    expect(config.headers.get('Idempotency-Key')).toBe('cmd-external-1')
  })
})
