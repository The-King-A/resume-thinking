import { ApiError } from '../api/contracts'

const API_MESSAGES: Record<string, string> = {
  AUTHENTICATION_REQUIRED: '登录状态已失效，请重新登录。',
  FORBIDDEN: '当前账号没有执行此操作的权限。',
  DUPLICATE_RESOURCE: '用户名或邮箱已存在。',
  VALIDATION_ERROR: '提交内容不符合要求，请检查后重试。',
  INVALID_CONFIRMATION: '删除确认文字不正确。',
  VERSION_CONFLICT: '数据已更新，请刷新后重试。',
  RESOURCE_NOT_FOUND: '未找到对应资源。',
  RESUME_ARCHIVED: '简历已归档，请先恢复。',
  RESUME_SOFT_DELETED: '简历已删除，无法执行此操作。',
  TASK_GONE: '任务已归档或删除。',
  TASK_NOT_READY: '结果尚未就绪，请稍后再试。',
  MODEL_UNAVAILABLE: '模型服务暂时不可用，请稍后重试。',
  MODEL_OUTPUT_INVALID: '模型返回的数据无法解析，请稍后重试。',
  MODEL_ENDPOINT_REJECTED: '模型接口地址不安全或不受支持。',
  UNSUPPORTED_FILE: '不支持此文件类型。',
  PAYLOAD_TOO_LARGE: '文件或请求内容过大。',
  IDEMPOTENCY_CONFLICT: '重复请求的数据不一致。',
  STALE_ATTEMPT: '任务版本已过期，请重新提交。',
}

export function friendlyError(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return API_MESSAGES[error.code] ?? fallback
  return fallback
}

/** Convert an internal task failure code into a safe user-facing message. */
export function friendlyFailureCode(code: string | null | undefined): string {
  if (!code) return ''
  return API_MESSAGES[code] ?? '匹配任务处理失败，请稍后重试。'
}
