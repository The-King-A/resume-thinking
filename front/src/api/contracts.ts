export type UserRole = 'USER' | 'ADMIN'

export interface User { id: string; username: string; email: string; role: UserRole; createdAt: string }
export interface RegisterRequest { username: string; email: string; password: string; role: UserRole }
export interface LoginRequest { identifier: string; password: string }
export interface AuthResponse { accessToken: string; tokenType: 'Bearer'; expiresIn: number; user: User }
export interface ApiErrorPayload { code: string; message: string; correlationId: string; retryable: boolean; details?: Array<{ field: string; reason: string }> }
export class ApiError extends Error {
  readonly payload: ApiErrorPayload
  readonly status: number
  constructor(payload: ApiErrorPayload, status: number) { super(payload.message); this.name = 'ApiError'; this.payload = payload; this.status = status }
  get code() { return this.payload.code }
}
export interface CreateLlmProfileRequest { displayName: string; endpointUrl: string; modelName: string; apiKey: string; selected?: boolean }
export type UpdateLlmProfileRequest = CreateLlmProfileRequest
export interface LlmProfile { id: string; displayName: string; endpointUrl: string; modelName: string; hasApiKey: boolean; selected: boolean; lastTestStatus?: 'SUCCEEDED' | 'FAILED' | null; lastTestedAt?: string | null; createdAt: string; updatedAt: string }
export interface LlmProfileTestResponse { available: boolean; testedAt: string; models?: string[]; diagnostic?: string | null }
