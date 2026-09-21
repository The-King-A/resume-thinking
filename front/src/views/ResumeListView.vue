<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DeleteResumeDialog from '../components/DeleteResumeDialog.vue'
import { lifecycleApi } from '../api/lifecycle'
import type { DeleteResumeRequest, EffectiveResume } from '../api/contracts'
import { showTopNotification } from '../ui/notifications'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const resumes = ref<EffectiveResume[]>([])
const selected = ref<EffectiveResume | null>(null)
const loading = ref(true)
const deleting = ref(false)
const error = ref('')
const page = ref(1)
const totalPages = ref(1)
const isAdmin = computed(() => auth.user?.role === 'ADMIN')

function report(message: string, type: 'success' | 'error') {
  try { showTopNotification(message, type) } catch { /* Notification rendering must not change lifecycle state. */ }
}

async function loadResumes(nextPage = page.value) {
  loading.value = true
  error.value = ''
  try {
    const response = await lifecycleApi.listEffectiveResumes(nextPage)
    page.value = response.page
    totalPages.value = response.totalPages
    resumes.value = response.items
  }
  catch { error.value = '无法加载有效简历。' }
  finally { loading.value = false }
}

async function deleteResume(payload: DeleteResumeRequest) {
  if (!selected.value) return
  deleting.value = true
  error.value = ''
  const id = selected.value.id
  const title = selected.value.title
  try {
    await lifecycleApi.deleteV3Resume(id, payload)
    resumes.value = resumes.value.filter((item) => item.id !== id)
    selected.value = null
    report(`已删除“${title}”简历。`, 'success')
  } catch {
    error.value = '无法删除此简历，请刷新页面并确认当前版本。'
    report(error.value, 'error')
  }
  finally { deleting.value = false }
}

onMounted(loadResumes)
</script>

<template>
  <main class="workspace">
    <header class="workspace-header">
      <div><p class="eyebrow">简历工作区</p><h1>有效简历</h1><p class="muted">此处仅显示 Java 服务返回的有效简历元数据。</p></div>
      <nav class="workspace-nav" aria-label="简历操作">
        <RouterLink class="button-link workspace-command workspace-command-primary" to="/match">上传并匹配</RouterLink>
        <RouterLink v-if="!isAdmin" class="workspace-command" to="/recovery">恢复简历</RouterLink>
        <RouterLink v-else class="workspace-command" to="/admin/recovery">管理员恢复</RouterLink>
        <RouterLink class="workspace-command" to="/profiles">模型配置</RouterLink>
      </nav>
    </header>
    <p class="retention-note">软删除后，简历会立即从此列表移除；可恢复的元数据会根据保留策略继续保存在 MySQL 中。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" class="status-panel">正在加载有效简历…</p>
    <section v-else-if="resumes.length" class="record-list" aria-label="有效简历列表">
      <article v-for="resume in resumes" :key="resume.id" class="record-row">
        <div><strong><code class="record-id">{{ resume.id }}</code>{{ resume.title }}</strong><span>{{ resume.sourceType }} · 第 {{ resume.version }} 版 · 更新于 {{ new Date(resume.updatedAt).toLocaleString() }}</span></div>
        <div class="record-row-actions"><RouterLink class="record-action-link record-action-primary" :to="`/resumes/${resume.id}/rematch`">重新匹配</RouterLink><RouterLink v-if="resume.latestSuccessfulTaskId" class="record-action-link" :to="`/matches/${resume.latestSuccessfulTaskId}`">查看匹配报告</RouterLink><button type="button" class="button-danger-quiet" @click="selected = resume">删除</button></div>
      </article>
    </section>
    <section v-else class="empty-state"><h2>暂无有效简历</h2><p>上传 TXT 或 DOCX 简历，开始一次匹配。</p><RouterLink to="/match">上传简历</RouterLink></section>
    <nav v-if="totalPages > 1" class="pagination" aria-label="简历分页"><button type="button" :disabled="page <= 1 || loading" @click="loadResumes(page - 1)">上一页</button><span>第 {{ page }} 页，共 {{ totalPages }} 页</span><button type="button" :disabled="page >= totalPages || loading" @click="loadResumes(page + 1)">下一页</button></nav>
    <DeleteResumeDialog :open="Boolean(selected)" :resume-id="selected?.id || ''" :version="selected?.version || 0" :busy="deleting" @cancel="selected = null" @confirm="deleteResume" />
  </main>
</template>
