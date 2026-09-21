export type UserRole = 'USER' | 'ADMIN'

export interface User { id: string; username: string; email: string; role: UserRole; createdAt: string }
export interface RegisterRequest { username: string; email: string; password: string; role: UserRole }
export interface LoginRequest { identifier: string; password: string }
export interface PasswordResetRequest { identifier: string; newPassword: string }
export interface AuthResponse { accessToken: string; tokenType: 'Bearer'; expiresIn: number; user: User }
export interface ApiErrorPayload { code: string; message: string; correlationId: string; retryable: boolean; detailCode?: string | null; details?: Array<{ field: string; reason: string }> }
export class ApiError extends Error {
  readonly payload: ApiErrorPayload
  readonly status: number
  constructor(payload: ApiErrorPayload, status: number) { super(payload.message); this.name = 'ApiError'; this.payload = payload; this.status = status }
  get code() { return this.payload.code }
}
export interface CreateLlmProfileRequest { displayName: string; endpointUrl: string; modelName: string; apiKey: string; selected?: boolean }
export interface LlmProfileScanRequest { endpointUrl: string; apiKey: string }
export interface UpdateLlmProfileRequest { displayName: string; endpointUrl: string; modelName: string; apiKey?: string; selected?: boolean }
export interface LlmProfile { id: string; displayName: string; endpointUrl: string; modelName: string; hasApiKey: boolean; selected: boolean; lastTestStatus?: 'SUCCEEDED' | 'FAILED' | null; lastTestedAt?: string | null; createdAt: string; updatedAt: string }
export interface LlmProfileTestResponse {
  available: boolean
  testedAt: string
  models?: string[]
  diagnostic?: string | null
  diagnosticCode?: 'INVALID_API_KEY' | 'PROVIDER_FORBIDDEN' | 'PROVIDER_ENDPOINT_NOT_FOUND' | 'PROVIDER_RATE_LIMITED' | 'PROVIDER_UNAVAILABLE' | 'PROVIDER_RESPONSE_INVALID' | null
}

export type SourceType = 'TXT' | 'DOCX'
export type JobFamily = 'JAVA_BACKEND'
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
export interface CreateMatchTaskRequest { resumeId: string; llmProfileId: string; jobFamily: JobFamily; jobDescriptionText: string; idempotencyKey: string }
export interface MatchTask {
  id: string
  resumeId: string
  llmProfileId: string
  jobFamily: JobFamily
  state: TaskState
  attempt: number
  resumeVersion: number
  failureCode?: 'MODEL_UNAVAILABLE' | 'MODEL_OUTPUT_INVALID' | 'MODEL_ENDPOINT_REJECTED' | 'UNSUPPORTED_FILE' | 'TASK_GONE' | 'STALE_ATTEMPT' | 'PYTHON_SERVICE_UNAVAILABLE' | 'PYTHON_SERVICE_AUTHENTICATION_FAILED' | 'CALLBACK_DELIVERY_FAILED' | null
  resultAvailable?: boolean
  createdAt: string
  updatedAt: string
}
export type PublicationState = 'NOT_REQUESTED' | 'PENDING' | 'PUBLISHED' | 'REJECTED_DUPLICATE_TITLE'
export interface V3MatchTask extends MatchTask {
  revisionId: string
  publicationState: PublicationState
  resultAvailable: boolean
}
export interface InitialMatchSubmissionRequest {
  file: File
  title?: string
  llmProfileId: string
  jobFamily: JobFamily
  jobDescriptionText: string
  idempotencyKey: string
}
export interface RematchSubmissionRequest {
  expectedEffectiveRevisionId: string
  file?: File
  title?: string
  llmProfileId: string
  jobFamily: JobFamily
  jobDescriptionText: string
  idempotencyKey: string
}
export interface EffectiveResume {
  id: string
  ownerId: string
  title: string
  sourceType: SourceType
  status: 0
  version: number
  effectiveRevisionId: string | null
  pendingRevisionId: string | null
  latestSuccessfulTaskId: string | null
  createdAt: string
  updatedAt: string
}
export interface EffectiveResumePage { items: EffectiveResume[]; page: number; pageSize: number; totalItems: number; totalPages: number }
export interface ResumeMatchContext {
  title: string
  effectiveRevisionId: string | null
  pendingRevisionId: string | null
  latestSuccessfulTaskId: string | null
  llmProfileId: string
  jobDescriptionText: string
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

export type InterviewSessionState = 'QUESTION_GENERATING' | 'WAITING_FOR_ANSWER' | 'ANSWER_ANALYZING' | 'FEEDBACK_READY' | 'COMPLETED' | 'FAILED' | 'DELETED'
export type InterviewQuestionType = 'BASIC_CONFIRMATION' | 'PROJECT_DEEP_DIVE' | 'JOB_SCENARIO' | 'SYNTHESIS_FOLLOW_UP'
export type InterviewDifficulty = 'BASIC' | 'INTERMEDIATE' | 'ADVANCED'
export type FeedbackLevel = 'HIGH' | 'MEDIUM' | 'LOW' | 'INSUFFICIENT_EVIDENCE'
export interface InterviewSession {
  id: string; resumeId: string; revisionId: string; matchTaskId: string; jobFamily: JobFamily
  state: InterviewSessionState; workType: 'QUESTION_GENERATION' | 'ANSWER_ANALYSIS' | null
  questionCount: number; answeredQuestionId: string | null; activeQuestionId?: string | null; feedbackId: string | null; failureCode: string | null
  version: number; createdAt: string; updatedAt: string; deletedAt: string | null
}
export interface InterviewQuestion {
  id: string; sequence: number; questionType: InterviewQuestionType; difficulty: InterviewDifficulty
  questionText: string; requirementId: string; requirementText: string; evidenceIds: string[]; generationReason: string; confidence: number; answered?: boolean
}
export interface CreateInterviewSessionRequest { matchTaskId: string; idempotencyKey: string }
export interface SubmitInterviewAnswerRequest { questionId: string; answerText: string; expectedSessionVersion: number; idempotencyKey: string }
export interface InterviewFeedback {
  id: string; sessionId: string; answerId: string; state: 'FEEDBACK_READY'
  relevance: FeedbackLevel; completeness: FeedbackLevel; technicalAccuracy: FeedbackLevel; factualConsistency: FeedbackLevel; clarity: FeedbackLevel
  evidenceIds: string[]; riskFlags: Array<{ code: string; message: string; level: FeedbackLevel; claimState: SuggestionState }>
  claims: Array<{ id: string; claimText: string; state: SuggestionState; evidenceIds: string[]; applied: false }>
  improvementSuggestion: string; suggestedAnswer?: string | null; answerComparison?: string | null; submittedAnswer?: string | null
  nextQuestionId?: string | null; version: number; createdAt: string
}
