import { defineStore } from 'pinia'
import { registerAuthSessionClearer, request, tokenStorage } from '../api/http'
import type { AuthResponse, LoginRequest, RegisterRequest, User } from '../api/contracts'
import { friendlyError } from '../i18n/messages'

const IDENTITY_KEY = 'resume-matching.identity'
const readIdentity = (): User | null => { try { const raw = localStorage.getItem(IDENTITY_KEY); return raw ? JSON.parse(raw) as User : null } catch { return null } }
export const useAuthStore = defineStore('auth', {
  state: () => ({ user: readIdentity() as User | null, loading: false, error: null as string | null }),
  getters: { isAuthenticated: (state) => Boolean(tokenStorage.get() && state.user) },
  actions: {
    async register(payload: RegisterRequest) { this.loading = true; this.error = null; try { const data = await request<AuthResponse>({ method: 'POST', url: '/api/v1/auth/register', data: payload }); this.setSession(data); return data } catch (error) { this.error = friendlyError(error, '注册失败，请稍后重试。'); throw error } finally { this.loading = false } },
    async login(payload: LoginRequest) { this.loading = true; this.error = null; try { const data = await request<AuthResponse>({ method: 'POST', url: '/api/v1/auth/login', data: payload }); this.setSession(data); return data } catch (error) { this.error = friendlyError(error, '登录失败，请稍后重试。'); throw error } finally { this.loading = false } },
    bindHttpSession() { registerAuthSessionClearer(() => { this.user = null }) },
    setSession(data: AuthResponse) { this.bindHttpSession(); tokenStorage.set(data.accessToken); this.user = data.user; localStorage.setItem(IDENTITY_KEY, JSON.stringify(data.user)) },
    logout() { tokenStorage.clear(); localStorage.removeItem(IDENTITY_KEY); this.user = null },
  },
})
