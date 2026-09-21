import { ApiError } from '../api/contracts'

export const authMessages = {
  loginSuccess: '登录成功。',
  loginFailure: '登录失败，请稍后重试。',
  registerSuccess: '注册成功，已为你登录。',
  registerFailure: '注册失败，请稍后重试。',
  resetSuccess: '密码已重置，请使用新密码登录。',
  resetFailure: '密码重置失败，请稍后重试。',
}

export const lifecycleMessages = {
  duplicateTitle: '简历标题已存在，请修改标题后重新提交。',
  restoreDuplicateTitle: '简历标题已存在：已有相同标题的有效简历，请先处理冲突简历后再恢复。',
  resumeNotEffective: '该简历尚未完成证据匹配，不能恢复到有效简历。请重新上传并完成匹配。',
  staleEffectiveRevision: '有效版本已更新，请刷新当前简历后重新匹配。',
  submissionFailed: '无法提交匹配任务，请检查内容后重试。',
  rematchContextFailed: '无法加载重新匹配所需的信息。',
}

const API_MESSAGES: Record<string, string> = {
  AUTHENTICATION_REQUIRED: '登录状态已失效，请重新登录。',
  INVALID_CREDENTIALS: '用户名或密码不正确，请检查后重试。',
  FORBIDDEN: '当前账号没有执行此操作的权限。',
  DUPLICATE_RESOURCE: '用户名或邮箱已存在。',
  VALIDATION_ERROR: '提交内容不符合要求，请检查后重试。',
  INVALID_CONFIRMATION: '删除确认文字不正确。',
  VERSION_CONFLICT: '数据已更新，请刷新后重试。',
  RESOURCE_NOT_FOUND: '未找到对应资源。',
  RESUME_NOT_EFFECTIVE: '该简历尚未完成证据匹配，不能恢复到有效简历。请重新上传并完成匹配。',
  RESUME_ARCHIVED: '简历已归档，请先恢复。',
  RESUME_SOFT_DELETED: '简历已删除，无法执行此操作。',
  TASK_GONE: '任务已归档或删除。',
  TASK_NOT_READY: '结果尚未就绪，请稍后再试。',
  MODEL_UNAVAILABLE: '模型服务暂时不可用，请稍后重试。',
  MODEL_OUTPUT_INVALID: '模型返回的数据无法解析，请稍后重试。',
  MODEL_ENDPOINT_REJECTED: '模型接口地址不安全或不受支持。',
  PYTHON_SERVICE_UNAVAILABLE: '分析服务不可用，请确认 Python 分析服务已启动后重试。',
  PYTHON_SERVICE_AUTHENTICATION_FAILED: '分析服务内部授权配置不一致，请检查后重试。',
  CALLBACK_DELIVERY_FAILED: '分析回调超时，可以稍后重新发起匹配。',
  UNSUPPORTED_FILE: '不支持此文件类型。',
  PAYLOAD_TOO_LARGE: '文件或请求内容过大。',
  IDEMPOTENCY_CONFLICT: '重复请求的数据不一致。',
  STALE_ATTEMPT: '任务版本已过期，请重新提交。',
  INTERVIEW_MATCH_NOT_READY: '当前匹配报告尚不具备面试练习条件，请先完成证据匹配。',
  INTERVIEW_SESSION_NOT_FOUND: '未找到对应的面试会话。',
  INTERVIEW_SESSION_GONE: '面试会话已结束或已清理。',
  INTERVIEW_QUESTION_NOT_READY: '面试题仍在生成，请稍后查看。',
  INTERVIEW_FEEDBACK_NOT_READY: '回答反馈仍在生成，请稍后查看。',
  INTERVIEW_ANSWER_CONFLICT: '回答状态已更新，请刷新后重试。',
  INTERVIEW_CALLBACK_STALE: '面试任务版本已过期，请重新开始练习。',
  INTERVIEW_MODEL_UNAVAILABLE: '面试分析服务暂时不可用，请稍后重试。',
  INTERVIEW_MODEL_OUTPUT_INVALID: '面试反馈格式无效，请重新开始练习。',
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
