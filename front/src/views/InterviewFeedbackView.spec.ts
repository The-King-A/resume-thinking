// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/contracts'

const mocks = vi.hoisted(() => ({
  getFeedback: vi.fn(),
  getSession: vi.fn(),
  nextQuestion: vi.fn(),
  deleteSession: vi.fn(),
  replace: vi.fn(),
  sessionId: 'session007',
}))

vi.mock('../api/interview', () => ({
  interviewApi: {
    getFeedback: mocks.getFeedback,
    getSession: mocks.getSession,
    nextQuestion: mocks.nextQuestion,
    deleteSession: mocks.deleteSession,
    confirmClaim: vi.fn(),
  },
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { sessionId: mocks.sessionId } }),
  useRouter: () => ({ replace: mocks.replace }),
}))

import InterviewFeedbackView from './InterviewFeedbackView.vue'

describe('InterviewFeedbackView terminal failures', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mocks.getFeedback.mockReset()
    mocks.getSession.mockReset()
    mocks.nextQuestion.mockReset()
    mocks.deleteSession.mockReset()
    mocks.replace.mockReset()
  })

  it('stops polling when Java reports invalid model output', async () => {
    mocks.getSession.mockResolvedValue({ state: 'FEEDBACK_READY' })
    mocks.getFeedback.mockRejectedValue(new ApiError({
      code: 'INTERVIEW_MODEL_OUTPUT_INVALID', message: 'safe', correlationId: 'c', retryable: false,
    }, 409))
    const wrapper = mount(InterviewFeedbackView)
    await flushPromises()

    expect(wrapper.text()).toContain('面试反馈格式无效，请重新开始练习。')
    await vi.advanceTimersByTimeAsync(4000)
    expect(mocks.getFeedback).toHaveBeenCalledTimes(1)
  })

  it('stops polling when the session status is failed', async () => {
    mocks.getSession.mockResolvedValue({ state: 'FAILED', failureCode: 'INTERVIEW_MODEL_UNAVAILABLE' })
    const wrapper = mount(InterviewFeedbackView)
    await flushPromises()

    expect(wrapper.text()).toContain('面试分析服务暂时不可用，请稍后重试。')
    await vi.advanceTimersByTimeAsync(4000)
    expect(mocks.getFeedback).toHaveBeenCalledTimes(0)
  })

  it('waits for a ready session before requesting feedback', async () => {
    mocks.getSession
      .mockResolvedValueOnce({ state: 'ANSWER_ANALYZING' })
      .mockResolvedValueOnce({ state: 'FEEDBACK_READY' })
    mocks.getFeedback.mockResolvedValue({
      state: 'FEEDBACK_READY', relevance: 'HIGH', completeness: 'MEDIUM', technicalAccuracy: 'HIGH',
      factualConsistency: 'HIGH', clarity: 'MEDIUM', evidenceIds: [], riskFlags: [], claims: [],
      submittedAnswer: '回答', suggestedAnswer: '建议', answerComparison: '对比', version: 1,
    })

    const wrapper = mount(InterviewFeedbackView)
    await flushPromises()

    expect(mocks.getSession).toHaveBeenCalledTimes(1)
    expect(mocks.getFeedback).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1200)
    await flushPromises()

    expect(mocks.getSession).toHaveBeenCalledTimes(2)
    expect(mocks.getFeedback).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('建议')
  })

  it('clears a pending retry after feedback becomes available', async () => {
    mocks.getFeedback.mockResolvedValueOnce({
        state: 'FEEDBACK_READY', relevance: 'HIGH', completeness: 'MEDIUM', technicalAccuracy: 'HIGH',
        factualConsistency: 'HIGH', clarity: 'MEDIUM', evidenceIds: [], riskFlags: [], claims: [],
        submittedAnswer: '回答', suggestedAnswer: '建议', answerComparison: '对比', version: 1,
      })
    mocks.getSession
      .mockResolvedValueOnce({ state: 'ANSWER_ANALYZING' })
      .mockResolvedValueOnce({ state: 'FEEDBACK_READY' })
    const wrapper = mount(InterviewFeedbackView)
    await flushPromises()

    await vi.advanceTimersByTimeAsync(1200)
    await flushPromises()
    expect(wrapper.text()).toContain('建议')

    await vi.advanceTimersByTimeAsync(5000)
    expect(mocks.getFeedback).toHaveBeenCalledTimes(1)
  })

  it('renders the submitted answer, suggested answer, and comparison without risk or claim panels', async () => {
    mocks.getSession.mockResolvedValue({ state: 'FEEDBACK_READY' })
    mocks.getFeedback.mockResolvedValue({
      state: 'FEEDBACK_READY', relevance: 'HIGH', completeness: 'MEDIUM', technicalAccuracy: 'HIGH',
      factualConsistency: 'HIGH', clarity: 'MEDIUM', evidenceIds: ['evidence001'], riskFlags: [], claims: [],
      submittedAnswer: '我负责 Java 服务开发。',
      suggestedAnswer: '我会说明职责、技术取舍和结果。',
      answerComparison: '已说明职责，但还缺少技术取舍。',
      improvementSuggestion: 'legacy fallback', version: 1,
    })
    const wrapper = mount(InterviewFeedbackView)
    await flushPromises()

    expect(wrapper.text()).toContain('你的回答')
    expect(wrapper.text()).toContain('我负责 Java 服务开发。')
    expect(wrapper.text()).toContain('建议回答')
    expect(wrapper.text()).toContain('我会说明职责、技术取舍和结果。')
    expect(wrapper.text()).toContain('回答对比分析')
    expect(wrapper.text()).toContain('已说明职责，但还缺少技术取舍。')
    expect(wrapper.text()).not.toContain('风险提示')
    expect(wrapper.text()).not.toContain('待确认内容')
  })

  it('opens the next unanswered question without ending the session', async () => {
    mocks.getFeedback.mockResolvedValue({
      state: 'FEEDBACK_READY', relevance: 'HIGH', completeness: 'MEDIUM', technicalAccuracy: 'HIGH',
      factualConsistency: 'HIGH', clarity: 'MEDIUM', evidenceIds: [], riskFlags: [], claims: [],
      submittedAnswer: '我的回答', suggestedAnswer: '建议回答', answerComparison: '对比分析',
      improvementSuggestion: 'legacy fallback', version: 1,
    })
    mocks.getSession.mockResolvedValue({ state: 'FEEDBACK_READY', version: 7 })
    mocks.nextQuestion.mockResolvedValue({ state: 'WAITING_FOR_ANSWER', activeQuestionId: 'question002' })
    const wrapper = mount(InterviewFeedbackView)
    await flushPromises()

    await wrapper.get('[data-test="next-question"]').trigger('click')
    await flushPromises()

    expect(mocks.nextQuestion).toHaveBeenCalledWith('session007', { expectedSessionVersion: 7 })
    expect(mocks.replace).toHaveBeenCalledWith('/interviews/session007')
  })

  it('marks the session complete after the final answered question', async () => {
    mocks.getFeedback.mockResolvedValue({
      state: 'FEEDBACK_READY', relevance: 'HIGH', completeness: 'MEDIUM', technicalAccuracy: 'HIGH',
      factualConsistency: 'HIGH', clarity: 'MEDIUM', evidenceIds: [], riskFlags: [], claims: [],
      submittedAnswer: '最后一题回答', suggestedAnswer: '建议回答', answerComparison: '对比分析',
      improvementSuggestion: 'legacy fallback', nextQuestionId: null, version: 1,
    })
    mocks.getSession.mockResolvedValue({ state: 'FEEDBACK_READY', version: 9 })
    mocks.nextQuestion.mockResolvedValue({ state: 'COMPLETED', activeQuestionId: 'question004' })
    const wrapper = mount(InterviewFeedbackView)
    await flushPromises()

    await wrapper.get('[data-test="finish-practice"]').trigger('click')
    await flushPromises()

    expect(mocks.nextQuestion).toHaveBeenCalledWith('session007', { expectedSessionVersion: 9 })
    expect(mocks.replace).toHaveBeenCalledWith('/interviews/session007')
  })
})
