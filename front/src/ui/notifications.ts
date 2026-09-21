import { ElMessage, type MessageType } from 'element-plus'

/** A small boundary around Element Plus so application notifications stay mockable in tests. */
export function showTopNotification(message: string, type: MessageType) {
  ElMessage({ message, type, grouping: true })
}
