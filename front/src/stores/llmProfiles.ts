import { defineStore } from 'pinia'
import { request } from '../api/http'
import type { CreateLlmProfileRequest, LlmProfile, LlmProfileTestResponse, UpdateLlmProfileRequest } from '../api/contracts'
export const useLlmProfileStore = defineStore('llmProfiles', {
  state: () => ({ profiles: [] as LlmProfile[], loading: false, error: null as string | null }),
  actions: {
    async list() { this.loading = true; try { this.profiles = await request<LlmProfile[]>({ method: 'GET', url: '/api/v1/llm-profiles' }); return this.profiles } finally { this.loading = false } },
    async create(payload: CreateLlmProfileRequest) { const result = await request<LlmProfile>({ method: 'POST', url: '/api/v1/llm-profiles', data: payload }); this.profiles.push(result); return result },
    async update(id: string, payload: UpdateLlmProfileRequest) { const result = await request<LlmProfile>({ method: 'PUT', url: `/api/v1/llm-profiles/${id}`, data: payload }); const index = this.profiles.findIndex((item) => item.id === id); if (index >= 0) this.profiles[index] = result; return result },
    async testConnection(id: string) { return request<LlmProfileTestResponse>({ method: 'POST', url: `/api/v1/llm-profiles/${id}/test` }) },
    async testDraft(payload: CreateLlmProfileRequest) { const created = await this.create(payload); try { return await this.testConnection(created.id) } finally { await request<void>({ method: 'DELETE', url: `/api/v1/llm-profiles/${created.id}` }); this.profiles = this.profiles.filter((profile) => profile.id !== created.id) } },
  },
})
