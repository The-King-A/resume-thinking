<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DeleteResumeDialog from '../components/DeleteResumeDialog.vue'
import { lifecycleApi } from '../api/lifecycle'
import type { DeleteResumeRequest, Resume } from '../api/contracts'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const resumes = ref<Resume[]>([])
const selected = ref<Resume | null>(null)
const loading = ref(true)
const deleting = ref(false)
const error = ref('')
const page = ref(1)
const totalPages = ref(1)
const isAdmin = computed(() => auth.user?.role === 'ADMIN')

async function loadResumes(nextPage = page.value) {
  loading.value = true
  error.value = ''
  try {
    const response = await lifecycleApi.listResumes(nextPage)
    page.value = response.page
    totalPages.value = response.totalPages
    resumes.value = response.items.filter((item) => item.visibilityState === 'ACTIVE' && item.status === 0)
  }
  catch { error.value = '无法加载有效简历。' }
  finally { loading.value = false }
}

async function deleteResume(payload: DeleteResumeRequest) {
  if (!selected.value) return
  deleting.value = true
  error.value = ''
  const id = selected.value.id
  try {
    if (isAdmin.value) await lifecycleApi.adminSoftDeleteResume(id, payload)
    else await lifecycleApi.deleteResume(id, payload)
    resumes.value = resumes.value.filter((item) => item.id !== id)
    selected.value = null
  } catch { error.value = '无法删除此简历，请刷新页面并确认当前版本。' }
  finally { deleting.value = false }
}

onMounted(loadResumes)
</script>

<template>
  <main class="workspace">
    <header class="workspace-header">
      <div><p class="eyebrow">简历工作区</p><h1>有效简历</h1><p class="muted">此处仅显示 Java 服务返回的有效简历元数据。</p></div>
      <nav class="workspace-nav" aria-label="简历操作">
        <RouterLink class="button-link" to="/match">上传并匹配</RouterLink>
        <RouterLink v-if="!isAdmin" to="/recovery">恢复简历</RouterLink>
        <RouterLink v-else to="/admin/recovery">管理员恢复</RouterLink>
        <RouterLink to="/profiles">模型配置</RouterLink>
      </nav>
    </header>
    <p class="retention-note">软删除后，简历会立即从此列表移除；可恢复的元数据会根据保留策略继续保存在 MySQL 中。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" class="status-panel">正在加载有效简历…</p>
    <section v-else-if="resumes.length" class="record-list" aria-label="有效简历列表">
      <article v-for="resume in resumes" :key="resume.id" class="record-row">
        <div><strong>{{ resume.title }}</strong><span>{{ resume.sourceType }} · 第 {{ resume.version }} 版 · 更新于 {{ new Date(resume.updatedAt).toLocaleString() }}</span></div>
        <button type="button" class="button-danger-quiet" @click="selected = resume">删除</button>
      </article>
    </section>
    <section v-else class="empty-state"><h2>暂无有效简历</h2><p>上传 TXT 或 DOCX 简历，开始一次匹配。</p><RouterLink to="/match">上传简历</RouterLink></section>
    <nav v-if="totalPages > 1" class="pagination" aria-label="简历分页"><button type="button" :disabled="page <= 1 || loading" @click="loadResumes(page - 1)">上一页</button><span>第 {{ page }} 页，共 {{ totalPages }} 页</span><button type="button" :disabled="page >= totalPages || loading" @click="loadResumes(page + 1)">下一页</button></nav>
    <DeleteResumeDialog :open="Boolean(selected)" :resume-id="selected?.id || ''" :version="selected?.version || 0" :busy="deleting" @cancel="selected = null" @confirm="deleteResume" />
  </main>
</template>
