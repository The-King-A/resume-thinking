// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  getQuestions: vi.fn(),
  submitAnswer: vi.fn(),
  deleteSession: vi.fn(),
  push: vi.fn(),
  replace: vi.fn(),
}))

vi.mock('../api/interview', () => ({
  interviewApi: {
    getQuestions: mocks.getQuestions,
    submitAnswer: mocks.submitAnswer,
    deleteSession: mocks.deleteSession,
  },
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { sessionId: 'session007' } }),
  useRouter: () => ({ push: mocks.push, replace: mocks.replace }),
}))

import InterviewSessionView from './InterviewSessionView.vue'

const questions = [
  { id: 'question001', sequence: 1, questionType: 'BASIC_CONFIRMATION', difficulty: 'BASIC', questionText: '非常长的题目文本 A', requirementId: 'requirement001', requirementText: 'Java 服务开发', evidenceIds: ['evidence001'], generationReason: '核对', confidence: .9, answered: true },
  { id: 'question002', sequence: 2, questionType: 'PROJECT_DEEP_DIVE', difficulty: 'INTERMEDIATE', questionText: '非常长的题目文本 B', requirementId: 'requirement002', requirementText: '测试与质量保障', evidenceIds: ['evidence002'], generationReason: '追问', confidence: .9, answered: false },
  { id: 'question003', sequence: 3, questionType: 'JOB_SCENARIO', difficulty: 'ADVANCED', questionText: '非常长的题目文本 C', requirementId: 'requirement003', requirementText: '故障排查', evidenceIds: ['evidence003'], generationReason: '场景', confidence: .9, answered: false },
]

describe('InterviewSessionView question navigation', () => {
  beforeEach(() => {
    mocks.getQuestions.mockReset()
    mocks.submitAnswer.mockReset()
    mocks.getQuestions.mockResolvedValue({
      session: { id: 'session007', state: 'WAITING_FOR_ANSWER', version: 3, activeQuestionId: 'question002' },
      questions,
    })
  })

  it('uses compact question controls instead of a long native select', async () => {
    const wrapper = mount(InterviewSessionView)
    await flushPromises()

    expect(wrapper.find('select').exists()).toBe(false)
    expect(wrapper.findAll('[data-test="question-switcher-item"]')).toHaveLength(3)
    expect(wrapper.text()).toContain('非常长的题目文本 B')
    expect(wrapper.findAll('[data-test="question-switcher-item"]')[0].attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('已完成')

    await wrapper.findAll('[data-test="question-switcher-item"]')[2].trigger('click')
    expect(wrapper.text()).toContain('非常长的题目文本 C')
  })
})
