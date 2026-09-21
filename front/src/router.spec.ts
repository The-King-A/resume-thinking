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

  it('preserves the interview target when its request expires the session', async () => {
    await router.push({ path: '/interviews/new', query: { matchTaskId: 'task001' } })
    const interceptor = http.interceptors.response.handlers?.[0]?.rejected
    if (!interceptor) throw new Error('response interceptor missing')
    const response = { status: 401, data: { code: 'AUTHENTICATION_REQUIRED', message: 'expired', correlationId: 'c', retryable: false }, config: { url: '/api/v4/interview-sessions' } }

    await expect(interceptor({ response })).rejects.toBeTruthy()
    await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.query.redirect).toBe('/interviews/new?matchTaskId=task001'))
  })

  it('preserves the interview target when an unauthenticated user opens it directly', async () => {
    const auth = useAuthStore()
    auth.logout()

    await router.push({ path: '/interviews/new', query: { matchTaskId: 'task001' } })

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/interviews/new?matchTaskId=task001')
  })

  it('registers the resume lifecycle routes', () => {
    for (const path of ['/resumes', '/match', '/resumes/resume001/rematch', '/matches/task001', '/recovery', '/admin/recovery', '/forgot-password']) {
      expect(router.resolve(path).matched.length).toBeGreaterThan(0)
    }
  })

  it('loads the re-match route for an authenticated user', async () => {
    await router.push('/resumes/resume001/rematch')
    expect(router.currentRoute.value.path).toBe('/resumes/resume001/rematch')
  })

  it('keeps password recovery accessible to an active local session', async () => {
    await router.push('/forgot-password')
    expect(router.currentRoute.value.path).toBe('/forgot-password')
  })

  it('keeps login guest-only for an active local session', async () => {
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/profiles')
  })

  it('redirects a USER away from administrator recovery', async () => {
    await router.push('/admin/recovery')
    expect(router.currentRoute.value.path).toBe('/resumes')
  })
})
