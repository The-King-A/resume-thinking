import { request } from './http'
import type { CreateInterviewSessionRequest, InterviewFeedback, InterviewQuestion, InterviewSession, SubmitInterviewAnswerRequest } from './contracts'

export const interviewApi = {
  createSession: (data: CreateInterviewSessionRequest) => request<InterviewSession>({ method: 'POST', url: '/api/v4/interview-sessions', data }),
  getSession: (sessionId: string) => request<InterviewSession>({ method: 'GET', url: `/api/v4/interview-sessions/${sessionId}` }),
  getQuestions: (sessionId: string) => request<{ session: InterviewSession; questions: InterviewQuestion[] }>({ method: 'GET', url: `/api/v4/interview-sessions/${sessionId}/questions` }),
  submitAnswer: (sessionId: string, data: SubmitInterviewAnswerRequest) => request<{ session: InterviewSession; answerId: string; state: 'ANSWER_ANALYZING'; feedbackAvailable: false }>({ method: 'POST', url: `/api/v4/interview-sessions/${sessionId}/answers`, data }),
  getFeedback: (sessionId: string) => request<InterviewFeedback>({ method: 'GET', url: `/api/v4/interview-sessions/${sessionId}/feedback` }),
  nextQuestion: (sessionId: string, data: { expectedSessionVersion: number }) => request<InterviewSession>({ method: 'POST', url: `/api/v4/interview-sessions/${sessionId}/next-question`, data }),
  confirmClaim: (sessionId: string, data: { claimId: string; decision: 'CONFIRMED' | 'REJECTED'; expectedFeedbackVersion: number }) => request({ method: 'POST', url: `/api/v4/interview-sessions/${sessionId}/confirmations`, data }),
  deleteSession: (sessionId: string) => request<void>({ method: 'DELETE', url: `/api/v4/interview-sessions/${sessionId}` }),
}
