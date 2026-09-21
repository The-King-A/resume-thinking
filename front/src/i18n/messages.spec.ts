// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/contracts'
import { friendlyError, friendlyFailureCode } from './messages'

describe('friendlyError', () => {
  it('translates stable API codes without exposing backend details', () => {
    const error = new ApiError({ code: 'AUTHENTICATION_REQUIRED', message: 'private backend detail', correlationId: 'c', retryable: false }, 401)

    expect(friendlyError(error, '默认提示')).toBe('登录状态已失效，请重新登录。')
    expect(friendlyError(error, '默认提示')).not.toContain('private backend detail')
  })

  it('uses the caller-safe fallback for unknown errors', () => {
    expect(friendlyError(new Error('private backend detail'), '操作失败，请稍后重试。')).toBe('操作失败，请稍后重试。')
  })

  it('maps task failure codes without exposing the internal code', () => {
    expect(friendlyFailureCode('MODEL_OUTPUT_INVALID')).toBe('模型返回的数据无法解析，请稍后重试。')
    expect(friendlyFailureCode('PYTHON_SERVICE_UNAVAILABLE')).toBe('分析服务不可用，请确认 Python 分析服务已启动后重试。')
    expect(friendlyFailureCode('PYTHON_SERVICE_AUTHENTICATION_FAILED')).toBe('分析服务内部授权配置不一致，请检查后重试。')
    expect(friendlyFailureCode('CALLBACK_DELIVERY_FAILED')).toBe('分析回调超时，可以稍后重新发起匹配。')
    expect(friendlyFailureCode('INTERNAL_ONLY_CODE')).toBe('匹配任务处理失败，请稍后重试。')
    expect(friendlyFailureCode('INTERNAL_ONLY_CODE')).not.toContain('INTERNAL_ONLY_CODE')
  })
})
