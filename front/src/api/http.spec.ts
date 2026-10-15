// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from './contracts'
import { DEFAULT_API_BASE_URL, http, normalizeApiBaseUrl, registerAuthSessionExpiredHandler, tokenStorage } from './http'
import { useAuthStore } from '../stores/auth'

describe('http auth failure handling', () => {
  it('does not clear a session when the login endpoint rejects credentials', async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    localStorage.setItem('resume-matching.token', 'token')
    localStorage.setItem('resume-matching.identity', '{"id":"user001"}')
    auth.user = { id: 'user001', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' }
    auth.bindHttpSession()
    const expired = vi.fn()
    registerAuthSessionExpiredHandler(expired)
    const interceptor = http.interceptors.response.handlers?.[0]?.rejected
    if (!interceptor) throw new Error('response interceptor missing')

    const response = {
      status: 401,
      data: { code: 'AUTHENTICATION_REQUIRED', message: 'no', correlationId: 'c', retryable: false },
      config: { method: 'post', url: '/api/v2/auth/login' },
    }

    await expect(interceptor({ response })).rejects.toBeInstanceOf(ApiError)
    expect(tokenStorage.get()).toBe('token')
    expect(localStorage.getItem('resume-matching.identity')).toBe('{"id":"user001"}')
    expect(auth.user).toEqual({ id: 'user001', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' })
    expect(expired).not.toHaveBeenCalled()
  })

  it('does not clear a session when the login endpoint returns the current invalid-credentials code', async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    localStorage.setItem('resume-matching.token', 'token')
    localStorage.setItem('resume-matching.identity', '{"id":"user001"}')
    auth.user = { id: 'user001', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' }
    auth.bindHttpSession()
    const expired = vi.fn()
    registerAuthSessionExpiredHandler(expired)
    const interceptor = http.interceptors.response.handlers?.[0]?.rejected
    if (!interceptor) throw new Error('response interceptor missing')

    const response = {
      status: 401,
      data: { code: 'INVALID_CREDENTIALS', message: 'no', correlationId: 'c', retryable: false },
      config: { method: 'post', url: '/api/v2/auth/login' },
    }

    await expect(interceptor({ response })).rejects.toBeInstanceOf(ApiError)
    expect(tokenStorage.get()).toBe('token')
    expect(localStorage.getItem('resume-matching.identity')).toBe('{"id":"user001"}')
    expect(auth.user).toEqual({ id: 'user001', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' })
    expect(expired).not.toHaveBeenCalled()
  })

  it('clears token and identity for a confirmed authentication failure from a protected endpoint', async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    localStorage.setItem('resume-matching.token', 'token')
    localStorage.setItem('resume-matching.identity', '{"id":"user001"}')
    auth.user = { id: 'user001', username: 'user', email: 'user@example.com', role: 'USER', createdAt: '' }
    auth.bindHttpSession()
    const expired = vi.fn()
    registerAuthSessionExpiredHandler(expired)
    const interceptor = http.interceptors.response.handlers?.[0]?.rejected
    if (!interceptor) throw new Error('response interceptor missing')

    const response = {
      status: 401,
      data: { code: 'AUTHENTICATION_REQUIRED', message: 'no', correlationId: 'c', retryable: false },
      config: { method: 'get', url: '/api/v2/llm-profiles' },
    }

    await expect(interceptor({ response })).rejects.toBeInstanceOf(ApiError)
    expect(tokenStorage.get()).toBeNull()
    expect(localStorage.getItem('resume-matching.identity')).toBeNull()
    expect(auth.user).toBeNull()
    expect(expired).toHaveBeenCalledOnce()
  })
})

describe('API base URL configuration', () => {
  it('uses the local Java API when the setting is empty', () => {
    expect(normalizeApiBaseUrl(undefined)).toBe(DEFAULT_API_BASE_URL)
    expect(normalizeApiBaseUrl('   ')).toBe(DEFAULT_API_BASE_URL)
    expect(normalizeApiBaseUrl('/')).toBe(DEFAULT_API_BASE_URL)
  })

  it('trims whitespace and trailing slashes from a configured origin', () => {
    expect(normalizeApiBaseUrl('  http://localhost:8080///  ')).toBe('http://localhost:8080')
  })

  it('configures Axios with the normalized base URL', () => {
    expect(http.defaults.baseURL).toBe(normalizeApiBaseUrl(import.meta.env.VITE_API_BASE_URL))
  })
})
