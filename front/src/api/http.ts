import axios, { type AxiosRequestConfig } from 'axios'
import { ApiError, type ApiErrorPayload } from './contracts'

const TOKEN_KEY = 'resume-matching.token'
export const http = axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080' })
export const tokenStorage = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}
http.interceptors.request.use((config) => {
  const token = tokenStorage.get()
  if (token) config.headers.set('Authorization', `Bearer ${token}`)
  return config
})
http.interceptors.response.use(undefined, (error) => {
  const status = error.response?.status ?? 0
  const data = error.response?.data
  if (data && typeof data.code === 'string' && typeof data.message === 'string' && typeof data.correlationId === 'string' && typeof data.retryable === 'boolean') {
    if (status === 401 && data.code === 'AUTHENTICATION_REQUIRED' && error.config?.url !== '/api/v1/auth/login') {
      tokenStorage.clear()
      localStorage.removeItem('resume-matching.identity')
    }
    return Promise.reject(new ApiError(data as ApiErrorPayload, status))
  }
  return Promise.reject(error)
})
export const request = <T>(config: AxiosRequestConfig) => http.request<T>(config).then((response) => response.data)
