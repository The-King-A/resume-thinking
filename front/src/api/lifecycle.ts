import { request } from './http'
import type {
  CreateMatchTaskRequest,
  DeleteResumeRequest,
  MatchResult,
  MatchTask,
  RestoreResumeRequest,
  Resume,
  ResumePage,
} from './contracts'

export function normalizeMatchResult(result: MatchResult): MatchResult {
  return {
    ...result,
    requirements: result.requirements.map((requirement) => ({
      ...requirement,
      evidence: requirement.evidence.map((evidence) => {
        const record = evidence as unknown as Record<string, unknown>
        const allowedKeys = new Set(['id', 'sourceType', 'sourceLocation', 'sourceStart', 'sourceEnd', 'excerpt', 'confidence', 'strength'])
        if (typeof record.id !== 'string' || !record.id || 'evidenceId' in record || Object.keys(record).some((key) => !allowedKeys.has(key))) {
          throw new Error('Evidence must include a v1 id')
        }
        return evidence
      }),
    })),
  }
}

export const lifecycleApi = {
  listResumes: (page = 1, pageSize = 20) => request<ResumePage>({ method: 'GET', url: '/api/v1/resumes', params: { page, pageSize } }),
  uploadResume: (file: File, title?: string) => {
    const data = new FormData()
    data.append('file', file)
    if (title?.trim()) data.append('title', title.trim())
    return request<Resume>({ method: 'POST', url: '/api/v1/resumes', data })
  },
  deleteResume: (resumeId: string, data: DeleteResumeRequest) => request<Resume>({ method: 'DELETE', url: `/api/v1/resumes/${resumeId}`, data }),
  listUserRecovery: (page = 1, pageSize = 20) => request<ResumePage>({ method: 'GET', url: '/api/v1/recovery/resumes', params: { page, pageSize } }),
  restoreUserResume: (resumeId: string, data: RestoreResumeRequest) => request<Resume>({ method: 'POST', url: `/api/v1/recovery/resumes/${resumeId}/restore`, data }),
  listAdminRecovery: (ownerId?: string, page = 1, pageSize = 20) => request<ResumePage>({ method: 'GET', url: '/api/v1/admin/recovery/resumes', params: { page, pageSize, ...(ownerId ? { ownerId } : {}) } }),
  adminSoftDeleteResume: (resumeId: string, data: DeleteResumeRequest) => request<Resume>({ method: 'DELETE', url: `/api/v1/admin/recovery/resumes/${resumeId}`, data }),
  restoreAdminResume: (resumeId: string, data: RestoreResumeRequest) => request<Resume>({ method: 'POST', url: `/api/v1/admin/recovery/resumes/${resumeId}/restore`, data }),
  createMatchTask: (data: CreateMatchTaskRequest) => request<MatchTask>({ method: 'POST', url: '/api/v1/match-tasks', data }),
  getMatchTask: (taskId: string) => request<MatchTask>({ method: 'GET', url: `/api/v1/match-tasks/${taskId}` }),
  getMatchResult: (taskId: string) => request<MatchResult>({ method: 'GET', url: `/api/v1/match-tasks/${taskId}/result` }).then(normalizeMatchResult),
}
