import { afterEach, describe, expect, it } from 'vitest'
import { getAdminOperatorId, saveAdminAuth, clearAdminAuth } from './adminAuth'

function tokenWithUid(uid) {
  const payload = btoa(JSON.stringify({
    uid,
    exp: Math.floor(Date.now() / 1000) + 3600
  })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `hdr.${payload}.sig`
}

describe('admin operator identity', () => {
  afterEach(() => {
    clearAdminAuth()
  })

  it('reads uid from the current admin token and does not invent 10001', () => {
    expect(getAdminOperatorId()).toBe('')
    saveAdminAuth({ token: tokenWithUid(42), username: 'admin' })
    expect(getAdminOperatorId()).toBe('42')
  })
})
