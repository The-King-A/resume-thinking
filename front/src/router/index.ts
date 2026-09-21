import { createRouter, createWebHistory } from 'vue-router'
import { registerAuthSessionExpiredHandler } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { UserRole } from '../api/contracts'
import RouteUnavailableView from '../views/RouteUnavailableView.vue'

function retryPath(fullPath: string) {
  if (!fullPath.startsWith('/') || fullPath.startsWith('//') || fullPath.startsWith('/route-unavailable')) return '/resumes'
  return fullPath
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/resumes' },
    { path: '/login', component: () => import('../views/LoginView.vue'), meta: { guest: true } },
    { path: '/register', component: () => import('../views/RegisterView.vue'), meta: { guest: true } },
    { path: '/forgot-password', component: () => import('../views/ForgotPasswordView.vue') },
    { path: '/route-unavailable', name: 'route-unavailable', component: RouteUnavailableView },
    { path: '/profiles', component: () => import('../views/ModelProfilesView.vue'), meta: { requiresAuth: true } },
    { path: '/resumes', component: () => import('../views/ResumeListView.vue'), meta: { requiresAuth: true } },
    { path: '/resumes/:resumeId/rematch', component: () => import('../views/ResumeRematchView.vue'), meta: { requiresAuth: true } },
    { path: '/match', component: () => import('../views/UploadMatchView.vue'), meta: { requiresAuth: true } },
    { path: '/matches/:taskId', component: () => import('../views/MatchResultView.vue'), meta: { requiresAuth: true } },
    { path: '/interviews/new', component: () => import('../views/InterviewPrepareView.vue'), meta: { requiresAuth: true } },
    { path: '/interviews/:sessionId', component: () => import('../views/InterviewSessionView.vue'), meta: { requiresAuth: true } },
    { path: '/interviews/:sessionId/feedback', component: () => import('../views/InterviewFeedbackView.vue'), meta: { requiresAuth: true } },
    { path: '/recovery', component: () => import('../views/RecoveryView.vue'), meta: { requiresAuth: true } },
    { path: '/admin/recovery', component: () => import('../views/AdminRecoveryView.vue'), meta: { requiresAuth: true, roles: ['ADMIN'] satisfies UserRole[] } },
  ],
})

registerAuthSessionExpiredHandler(() => {
  if (router.currentRoute.value.path !== '/login') {
    const redirect = retryPath(router.currentRoute.value.fullPath)
    void router.push({ path: '/login', query: { redirect } }).catch(() => undefined)
  }
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  auth.bindHttpSession()
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { path: '/login', query: { redirect: retryPath(to.fullPath) } }
  }
  if (to.meta.guest && auth.isAuthenticated) return '/profiles'
  const roles = to.meta.roles as UserRole[] | undefined
  if (roles && (!auth.user || !roles.includes(auth.user.role))) return '/resumes'
})

let recoveringRouteError = false
router.onError((_error, to) => {
  if (recoveringRouteError || to.name === 'route-unavailable') return
  recoveringRouteError = true
  void router.replace({
    name: 'route-unavailable',
    query: { retry: retryPath(to.fullPath) },
  }).catch(() => undefined).finally(() => {
    recoveringRouteError = false
  })
})

export default router
