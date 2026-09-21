// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '../api/contracts'

const mocks = vi.hoisted(() => ({
  request: vi.fn(),
  notify: vi.fn(),
  registerAuthSessionClearer: vi.fn(),
  tokenSet: vi.fn(),
  tokenClear: vi.fn(),
  token: null as string | null,
}))

vi.mock('../api/http', () => ({
  request: mocks.request,
  registerAuthSessionClearer: mocks.registerAuthSessionClearer,
  tokenStorage: {
    get: () => mocks.token,
    set: (token: string) => { mocks.token = token; mocks.tokenSet(token) },
    clear: () => { mocks.token = null; mocks.tokenClear() },
  },
}))
vi.mock('../ui/notifications', () => ({ showTopNotification: mocks.notify }))

import { useAuthStore } from './auth'

const response = {
  accessToken: 'token', tokenType: 'Bearer' as const, expiresIn: 3600,
  user: { id: 'user001', username: 'alice', email: 'alice@example.com', role: 'USER' as const, createdAt: '2026-08-31T00:00:00Z' },
}

describe('authentication notifications', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    mocks.token = null
    vi.clearAllMocks()
  })

  it('marks the session authenticated after a successful login from the signed-out screen', async () => {
    const auth = useAuthStore()
    expect(auth.isAuthenticated).toBe(false)
    mocks.request.mockResolvedValue(response)

    await auth.login({ identifier: 'alice', password: 'long-enough-password' })

    expect(auth.isAuthenticated).toBe(true)
  })

  it('keeps a successful login successful when the optional top notification fails', async () => {
    const auth = useAuthStore()
    mocks.request.mockResolvedValue(response)
    mocks.notify.mockImplementationOnce(() => { throw new Error('notification unavailable') })

    await expect(auth.login({ identifier: 'alice', password: 'long-enough-password' })).resolves.toEqual(response)

    expect(auth.isAuthenticated).toBe(true)
  })

  it('does not expose an authenticated session when identity persistence fails', async () => {
    const auth = useAuthStore()
    mocks.request.mockResolvedValue(response)
    const originalSetItem = Storage.prototype.setItem
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
      if (key === 'resume-matching.identity') throw new Error('storage unavailable')
      return originalSetItem.call(this, key, value)
    })

    try {
      await expect(auth.login({ identifier: 'alice', password: 'long-enough-password' })).rejects.toThrow('storage unavailable')
      expect(auth.isAuthenticated).toBe(false)
    } finally {
      setItemSpy.mockRestore()
    }
  })

  it('restores an existing session when token persistence fails', async () => {
    const previousUser = { id: 'user002', username: 'before', email: 'before@example.com', role: 'USER' as const, createdAt: '2026-08-31T00:00:00Z' }
    const previousIdentity = JSON.stringify(previousUser)
    mocks.token = 'previous-token'
    localStorage.setItem('resume-matching.identity', previousIdentity)
    const auth = useAuthStore()
    mocks.request.mockResolvedValue(response)
    mocks.tokenSet.mockImplementation((token: string) => {
      if (token === response.accessToken) throw new Error('token storage unavailable')
    })

    try {
      await expect(auth.login({ identifier: 'alice', password: 'long-enough-password' })).rejects.toThrow('token storage unavailable')

      expect(mocks.token).toBe('previous-token')
      expect(auth.user).toEqual(previousUser)
      expect(localStorage.getItem('resume-matching.identity')).toBe(previousIdentity)
    } finally {
      mocks.tokenSet.mockReset()
    }
  })

  it('shows a top success notification after login succeeds', async () => {
    mocks.request.mockResolvedValue(response)
    await useAuthStore().login({ identifier: 'alice', password: 'long-enough-password' })
    expect(mocks.notify).toHaveBeenCalledWith('登录成功。', 'success')
  })

  it('shows a top success notification after registration succeeds', async () => {
    mocks.request.mockResolvedValue(response)
    await useAuthStore().register({ username: 'alice', email: 'alice@example.com', password: 'long-enough-password', role: 'USER' })
    expect(mocks.notify).toHaveBeenCalledWith('注册成功，已为你登录。', 'success')
  })

  it('shows a safe top failure notification after login fails', async () => {
    mocks.request.mockRejectedValue(new Error('network failure'))
    await expect(useAuthStore().login({ identifier: 'alice', password: 'long-enough-password' })).rejects.toThrow('network failure')
    expect(mocks.notify).toHaveBeenCalledWith('登录失败，请稍后重试。', 'error')
  })

  it('keeps a legacy login authentication response out of the session-expired message', async () => {
    mocks.request.mockRejectedValue(new ApiError({
      code: 'AUTHENTICATION_REQUIRED', message: 'AUTHENTICATION_REQUIRED', correlationId: 'c', retryable: false,
    }, 401))

    await expect(useAuthStore().login({ identifier: 'alice', password: 'long-enough-password' })).rejects.toBeInstanceOf(ApiError)

    expect(mocks.notify).toHaveBeenCalledWith('登录失败，请稍后重试。', 'error')
  })

  it('shows a generic credential error without exposing whether the account exists', async () => {
    mocks.request.mockRejectedValue(new ApiError({
      code: 'INVALID_CREDENTIALS', message: 'INVALID_CREDENTIALS', correlationId: 'c', retryable: false,
    }, 401))

    await expect(useAuthStore().login({ identifier: 'alice', password: 'long-enough-password' })).rejects.toBeInstanceOf(ApiError)

    expect(mocks.notify).toHaveBeenCalledWith('用户名或密码不正确，请检查后重试。', 'error')
  })

  it('shows a safe top failure notification after registration fails', async () => {
    mocks.request.mockRejectedValue(new Error('network failure'))
    await expect(useAuthStore().register({ username: 'alice', email: 'alice@example.com', password: 'long-enough-password', role: 'USER' })).rejects.toThrow('network failure')
    expect(mocks.notify).toHaveBeenCalledWith('注册失败，请稍后重试。', 'error')
  })
})
