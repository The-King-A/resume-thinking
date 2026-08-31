// @vitest-environment jsdom
import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { http, tokenStorage } from './api/http'
import { useAuthStore } from './stores/auth'
import { router } from './router'

describe('authentication expiry navigation', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    localStorage.clear()
    tokenStorage.set('token')
    const auth = useAuthStore()
    auth.user = { id: 'user001', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' }
    auth.bindHttpSession()
    await router.push('/profiles')
  })

  it('navigates away from a protected page after authentication expires', async () => {
    const interceptor = http.interceptors.response.handlers?.[0]?.rejected
    if (!interceptor) throw new Error('response interceptor missing')
    const response = { status: 401, data: { code: 'AUTHENTICATION_REQUIRED', message: 'expired', correlationId: 'c', retryable: false }, config: { url: '/api/v2/llm-profiles' } }
    await expect(interceptor({ response })).rejects.toBeTruthy()
    await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
  })

  it('registers the resume lifecycle routes', () => {
    for (const path of ['/resumes', '/match', '/matches/task001', '/recovery', '/admin/recovery']) {
      expect(router.resolve(path).matched.length).toBeGreaterThan(0)
    }
  })

  it('redirects a USER away from administrator recovery', async () => {
    await router.push('/admin/recovery')
    expect(router.currentRoute.value.path).toBe('/resumes')
  })
})
