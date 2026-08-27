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

export type SourceType = 'TXT' | 'DOCX'
export type VisibilityState = 'ACTIVE' | 'USER_SOFT_DELETED' | 'ADMIN_SOFT_DELETED' | 'USER_CACHE_ARCHIVED' | 'ADMIN_CACHE_ARCHIVED'
export type TaskState = 'QUEUED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'BLOCKED'
export type RequirementType = 'MANDATORY' | 'PREFERRED'
export type MatchStatus = 'SATISFIED' | 'PARTIALLY_SATISFIED' | 'RELATED_BUT_EVIDENCE_INSUFFICIENT' | 'UNMET'
export type MatchType = 'EXACT' | 'SEMANTIC' | 'RELATED' | 'NO_MATCH'
export type EvidenceStrength = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH'
export type SuggestionState = 'SUPPORTED_FACT' | 'WORDING_ONLY_REWRITE' | 'NEEDS_USER_CONFIRMATION' | 'RISKY_OR_UNSUPPORTED'
export type ScoreComponent = 'SKILLS' | 'PROJECT_EXPERIENCE' | 'WORK_CONTENT' | 'EDUCATION_EXPERIENCE' | 'SOFT_SKILLS'

export interface Resume {
  id: string
  ownerId: string
  title: string
  sourceType: SourceType
  status: 0 | 1
  visibilityState: VisibilityState
  version: number
  visibleUntil?: string | null
  softDeletedAt?: string | null
  archivedAt?: string | null
  restoredAt?: string | null
  createdAt: string
  updatedAt: string
}
export interface ResumePage { items: Resume[]; page: number; pageSize: number; totalItems: number; totalPages: number }
export interface DeleteResumeRequest { confirmationText: '确认删除简历'; expectedVersion: number }
export interface RestoreResumeRequest { expectedVersion: number }
export interface CreateMatchTaskRequest { resumeId: string; llmProfileId: string; jobDescriptionText: string; idempotencyKey: string }
export interface MatchTask {
  id: string
  resumeId: string
  llmProfileId: string
  state: TaskState
  attempt: number
  resumeVersion: number
  failureCode?: 'MODEL_UNAVAILABLE' | 'MODEL_OUTPUT_INVALID' | 'MODEL_ENDPOINT_REJECTED' | 'UNSUPPORTED_FILE' | 'TASK_GONE' | 'STALE_ATTEMPT' | null
  resultAvailable?: boolean
  createdAt: string
  updatedAt: string
}
export interface Evidence {
  id: string
  sourceType: SourceType
  sourceLocation: string
  sourceStart?: number
  sourceEnd?: number
  excerpt: string
  confidence: number
  strength: EvidenceStrength
}
export interface RequirementMatch {
  requirementId: string
  requirementText: string
  requirementType: RequirementType
  matchStatus: MatchStatus
  matchType: MatchType
  component: ScoreComponent
  componentScore: number
  evidence: Evidence[]
  gap: string | null
  suggestionState: SuggestionState
}
export interface MatchSuggestion {
  id: string
  requirementId: string
  state: SuggestionState
  originalText?: string | null
  proposedText: string
  evidenceIds: string[]
  requiresUserConfirmation?: boolean
}
export interface ScoreBreakdown { skills: number; projectExperience: number; workContent: number; educationExperience: number; softSkills: number; composite: number }
export interface MatchResult {
  taskId: string
  resumeId: string
  resumeVersion: number
  jobDescriptionText: string
  score: ScoreBreakdown
  requirements: RequirementMatch[]
  suggestions: MatchSuggestion[]
  completedAt: string
}
