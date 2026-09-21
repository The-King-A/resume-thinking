import { request } from './http'
import type {
  CreateMatchTaskRequest,
  DeleteResumeRequest,
  EffectiveResumePage,
  InitialMatchSubmissionRequest,
  MatchResult,
  MatchTask,
  RematchSubmissionRequest,
  ResumeMatchContext,
  RestoreResumeRequest,
  Resume,
  ResumePage,
  V3MatchTask,
} from './contracts'

export function normalizeMatchResult(result: MatchResult): MatchResult {
  if (!/^task[0-9]{3,}$/.test(result.taskId) || !/^resume[0-9]{3,}$/.test(result.resumeId)) {
    throw new Error('匹配结果必须使用 v2 业务编号')
  }
  return {
    ...result,
    requirements: result.requirements.map((requirement) => ({
      ...requirement,
      evidence: requirement.evidence.map((evidence) => {
        const record = evidence as unknown as Record<string, unknown>
        const allowedKeys = new Set(['id', 'sourceType', 'sourceLocation', 'sourceStart', 'sourceEnd', 'excerpt', 'confidence', 'strength'])
        if (typeof record.id !== 'string' || !/^evidence[0-9]{3,}$/.test(record.id) || 'evidenceId' in record || Object.keys(record).some((key) => !allowedKeys.has(key))) {
          throw new Error('Evidence must include a v2 id')
        }
        return evidence
      }),
    })),
  }
}

export const lifecycleApi = {
  submitInitialMatch: (payload: InitialMatchSubmissionRequest) => {
    const data = new FormData()
    data.append('file', payload.file)
    if (payload.title?.trim()) data.append('title', payload.title.trim())
    data.append('llmProfileId', payload.llmProfileId)
    data.append('jobFamily', payload.jobFamily)
    data.append('jobDescriptionText', payload.jobDescriptionText)
    data.append('idempotencyKey', payload.idempotencyKey)
    return request<V3MatchTask>({ method: 'POST', url: '/api/v3/match-submissions', data })
  },
  submitResumeRematch: (resumeId: string, payload: RematchSubmissionRequest) => {
    const data = new FormData()
    data.append('expectedEffectiveRevisionId', payload.expectedEffectiveRevisionId)
    if (payload.file) data.append('file', payload.file)
    if (payload.title?.trim()) data.append('title', payload.title.trim())
    data.append('llmProfileId', payload.llmProfileId)
    data.append('jobFamily', payload.jobFamily)
    data.append('jobDescriptionText', payload.jobDescriptionText)
    data.append('idempotencyKey', payload.idempotencyKey)
    return request<V3MatchTask>({ method: 'POST', url: `/api/v3/resumes/${resumeId}/match-submissions`, data })
  },
  listEffectiveResumes: (page = 1, pageSize = 20) => request<EffectiveResumePage>({ method: 'GET', url: '/api/v3/resumes', params: { page, pageSize } }),
  getResumeMatchContext: (resumeId: string) => request<ResumeMatchContext>({ method: 'GET', url: `/api/v3/resumes/${resumeId}/match-context` }),
  deleteV3Resume: (resumeId: string, data: DeleteResumeRequest) => request<void>({ method: 'DELETE', url: `/api/v3/resumes/${resumeId}`, data }),
  listResumes: (page = 1, pageSize = 20) => request<ResumePage>({ method: 'GET', url: '/api/v2/resumes', params: { page, pageSize } }),
  uploadResume: (file: File, title?: string) => {
    const data = new FormData()
    data.append('file', file)
    if (title?.trim()) data.append('title', title.trim())
    return request<Resume>({ method: 'POST', url: '/api/v2/resumes', data })
  },
  deleteResume: (resumeId: string, data: DeleteResumeRequest) => request<Resume>({ method: 'DELETE', url: `/api/v2/resumes/${resumeId}`, data }),
  listUserRecovery: (page = 1, pageSize = 20) => request<ResumePage>({ method: 'GET', url: '/api/v2/recovery/resumes', params: { page, pageSize } }),
  restoreUserResume: (resumeId: string, data: RestoreResumeRequest) => request<Resume>({ method: 'POST', url: `/api/v2/recovery/resumes/${resumeId}/restore`, data }),
  listAdminRecovery: (ownerId?: string, page = 1, pageSize = 20) => request<ResumePage>({ method: 'GET', url: '/api/v2/admin/recovery/resumes', params: { page, pageSize, ...(ownerId ? { ownerId } : {}) } }),
  adminSoftDeleteResume: (resumeId: string, data: DeleteResumeRequest) => request<Resume>({ method: 'DELETE', url: `/api/v2/admin/recovery/resumes/${resumeId}`, data }),
  restoreAdminResume: (resumeId: string, data: RestoreResumeRequest) => request<Resume>({ method: 'POST', url: `/api/v2/admin/recovery/resumes/${resumeId}/restore`, data }),
  createMatchTask: (data: CreateMatchTaskRequest) => request<MatchTask>({ method: 'POST', url: '/api/v2/match-tasks', data }),
  getMatchTask: (taskId: string) => request<MatchTask>({ method: 'GET', url: `/api/v2/match-tasks/${taskId}` }),
  getMatchResult: (taskId: string) => request<MatchResult>({ method: 'GET', url: `/api/v2/match-tasks/${taskId}/result` }).then(normalizeMatchResult),
}
