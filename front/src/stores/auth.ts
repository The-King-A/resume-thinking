import { defineStore } from 'pinia'
import { registerAuthSessionClearer, request, tokenStorage } from '../api/http'
import { ApiError, type AuthResponse, type LoginRequest, type PasswordResetRequest, type RegisterRequest, type User } from '../api/contracts'
import { authMessages, friendlyError } from '../i18n/messages'
import { showTopNotification } from '../ui/notifications'

const IDENTITY_KEY = 'resume-matching.identity'
const readIdentity = (): User | null => { try { const raw = localStorage.getItem(IDENTITY_KEY); return raw ? JSON.parse(raw) as User : null } catch { return null } }
const showAuthNotification = (message: string, type: 'success' | 'error') => {
  try { showTopNotification(message, type) } catch { /* Notification rendering must not change an authentication result. */ }
}
const loginFailureMessage = (error: unknown) => {
  if (error instanceof ApiError && error.status === 401 && error.code === 'AUTHENTICATION_REQUIRED') return authMessages.loginFailure
  return friendlyError(error, authMessages.loginFailure)
}
/** Username matching is case-sensitive; email matching is deliberately canonicalized. */
export const normalizeLoginIdentifier = (value: string) => {
  const trimmed = value.trim()
  return trimmed.includes('@') ? trimmed.toLowerCase() : trimmed
}
export const useAuthStore = defineStore('auth', {
  state: () => ({ user: readIdentity() as User | null, loading: false, error: null as string | null }),
  getters: { isAuthenticated: (state) => {
    const user = state.user
    return Boolean(tokenStorage.get() && user)
  } },
  actions: {
    async register(payload: RegisterRequest) { this.loading = true; this.error = null; try { const data = await request<AuthResponse>({ method: 'POST', url: '/api/v2/auth/register', data: { ...payload, username: payload.username.trim(), email: payload.email.trim().toLowerCase() } }); this.setSession(data); showAuthNotification(authMessages.registerSuccess, 'success'); return data } catch (error) { this.error = friendlyError(error, authMessages.registerFailure); showAuthNotification(this.error, 'error'); throw error } finally { this.loading = false } },
    async login(payload: LoginRequest) { this.loading = true; this.error = null; try { const data = await request<AuthResponse>({ method: 'POST', url: '/api/v2/auth/login', data: { ...payload, identifier: normalizeLoginIdentifier(payload.identifier) } }); this.setSession(data); showAuthNotification(authMessages.loginSuccess, 'success'); return data } catch (error) { this.error = loginFailureMessage(error); showAuthNotification(this.error, 'error'); throw error } finally { this.loading = false } },
    async resetPassword(payload: PasswordResetRequest) { this.loading = true; this.error = null; try { await request<void>({ method: 'POST', url: '/api/v2/auth/password-reset', data: { identifier: normalizeLoginIdentifier(payload.identifier), newPassword: payload.newPassword } }); this.logout(); showAuthNotification(authMessages.resetSuccess, 'success') } catch (error) { this.error = friendlyError(error, authMessages.resetFailure); showAuthNotification(this.error, 'error'); throw error } finally { this.loading = false } },
    bindHttpSession() { registerAuthSessionClearer(() => { this.user = null }) },
    setSession(data: AuthResponse) {
      this.bindHttpSession()
      const previousToken = tokenStorage.get()
      const previousIdentity = localStorage.getItem(IDENTITY_KEY)
      try {
        localStorage.setItem(IDENTITY_KEY, JSON.stringify(data.user))
        tokenStorage.set(data.accessToken)
      } catch (error) {
        try {
          if (previousIdentity === null) localStorage.removeItem(IDENTITY_KEY)
          else localStorage.setItem(IDENTITY_KEY, previousIdentity)
        } catch { /* Best-effort rollback for a storage failure. */ }
        try {
          if (previousToken === null) tokenStorage.clear()
          else tokenStorage.set(previousToken)
        } catch { /* Best-effort rollback for a storage failure. */ }
        throw error
      }
      this.user = data.user
    },
    logout() { tokenStorage.clear(); localStorage.removeItem(IDENTITY_KEY); this.user = null },
  },
})
